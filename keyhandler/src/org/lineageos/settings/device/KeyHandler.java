/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.android.internal.os.DeviceKeyHandler;

import org.lineageos.settings.device.pen.PenUEventRelay;

/**
 * Folio cover: puts the device to sleep when the cover is closed and wakes it
 * when the cover is opened.
 *
 * The cover magnet is reported by the wake-up hall effect sensor of the sensor
 * HAL (qti.sensor.hall_effect). The hall_irq input device only advertises
 * KEY 0x2ee-0x2f1 and never reports them: its two hall sensors serve the pen
 * charger. A magnet near the back of the tablet (keyboard folded behind it)
 * also trips the hall sensor, so a closed event is confirmed by the light
 * sensor reading dark before the device goes to sleep.
 *
 * Stylus: the display driver turns the stylus scanning of the touch controller
 * off at 90 and 144 Hz (dsi_panel_match_fps_setting), but the touch controller
 * still reports the stylus entering its detection range through KEY_SKIN_STYLUS,
 * which has no key code. The pen only works at 30, 60 and 120 Hz, and a display
 * in the 90 and 144 Hz modes has to reach 120 Hz for it. While the user setting
 * allows 90 Hz or more, the report raises the user's minimum refresh rate to
 * 120 Hz, which also lifts the user's peak vote to max(peak, min), and
 * DisplayManager#requestMaxRefreshRate() keeps a 144 Hz setting from going above
 * 120 Hz. The peak refresh rate the Settings list shows is left untouched.
 *
 * The key marks one approach only, while a writing session lasts as long as the
 * pen keeps sending motion: for as long as the raised rate is in effect, a
 * gesture monitor of the default display watches the pen's own hover and touch
 * events (TOOL_TYPE_STYLUS and TOOL_TYPE_ERASER) and re-arms the timeout, at
 * most once a second. The monitor only observes; it never consumes or pilfers a
 * pointer, and it exists only while the rate is raised.
 *
 * The minimum is saved first and put back STYLUS_TIMEOUT_MS after the last
 * event; a value still saved at boot or on a user switch is put back then. A 30
 * or 60 Hz setting keeps the scanning on and is left alone. The panel has no
 * VRR, so DisplayManager#requestDisplayModes() is ignored, and an application
 * frame rate vote cannot go above the user's peak.
 *
 * Double tap to wake: see DoubleTapToWake.
 *
 * Pen: the pen charger uevents are relayed to the LenovoPen app (see the pen
 * package).
 */
public class KeyHandler implements DeviceKeyHandler {
    private static final String TAG = "KeyHandler";

    private static final int TYPE_HALL_EFFECT = 33171002;
    private static final float COVERED_MAX_LUX = 20f;

    private static final int SCAN_CODE_STYLUS_NEAR = 249; // KEY_SKIN_STYLUS
    private static final float STYLUS_REFRESH_RATE = 120f;
    private static final String STYLUS_MIN_REFRESH_RATE = "120.0";
    // Below this user peak the display only runs at 30 or 60 Hz, where the
    // stylus is scanned
    private static final float STYLUS_SWITCH_MIN_PEAK = 90f;
    private static final long STYLUS_TIMEOUT_MS = 10000;
    // The pen streams motion events continuously, so the timeout does not need
    // to be re-armed on every one of them
    private static final long STYLUS_REARM_INTERVAL_MS = 1000;
    // Settings.Secure, per user: Settings.System.MIN_REFRESH_RATE as it was
    // before the stylus raised it
    private static final String KEY_SAVED_MIN_REFRESH_RATE = "tb520fu_stylus_min_refresh_rate";

    private final Context mContext;
    private final PowerManager mPowerManager;
    private final SensorManager mSensorManager;
    private final Sensor mLightSensor;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    /** Null when the pen bridge could not be created; the rest still works. */
    private PenUEventRelay mPenUEvent;

    private boolean mLightRegistered;
    // The cover was closed on a dark screen, so opening it wakes the device
    private boolean mWakeOnOpen;

    private boolean mStylusNear;
    // User whose minimum refresh rate the stylus raised, USER_NULL if none
    private int mStylusUserId = UserHandle.USER_NULL;
    private final Runnable mStylusGoneRunnable = this::onStylusGone;
    // Set only while the raised rate is in effect
    private InputMonitor mStylusMonitor;
    private InputEventReceiver mStylusReceiver;
    private long mStylusRearmTime;

    /**
     * Watches the pen on the default display and keeps the raised rate alive.
     * The events stay with the window they were sent to: a gesture monitor is a
     * spy, and this one finishes every event as unconsumed.
     */
    private final class StylusEventReceiver extends InputEventReceiver {
        StylusEventReceiver(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            boolean stylus = false;
            try {
                stylus = event instanceof MotionEvent && isStylus((MotionEvent) event);
            } finally {
                finishInputEvent(event, false);
            }
            if (stylus) {
                rearmStylusTimeout();
            }
        }
    }

    private final SensorEventListener mHallListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            // 1: cover away (open), anything else: cover near (closed)
            onCover((int) event.values[0] != 1);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private final SensorEventListener mLightListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.values[0] <= COVERED_MAX_LUX) {
                unregisterLight();
                sleepIfInteractive();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    public KeyHandler(Context context) {
        mContext = context;
        mPowerManager = context.getSystemService(PowerManager.class);
        mSensorManager = context.getSystemService(SensorManager.class);
        mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

        new DoubleTapToWake(context, mHandler);

        // The pen bridge must never keep the rest of the key handler from
        // loading: this class runs in system_server, so a failure here would
        // take the folio cover, double tap to wake and the stylus refresh rate
        // down with it.
        try {
            final PenUEventRelay relay = new PenUEventRelay(context);
            relay.start();
            mPenUEvent = relay;
        } catch (RuntimeException e) {
            Log.e(TAG, "Pen bridge unavailable", e);
        }

        // A minimum the stylus raised before a reboot or under another user
        // goes back to what the user had. The system user is the current one
        // at boot.
        restoreSavedMinRefreshRate(UserHandle.USER_SYSTEM);
        context.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mHandler.removeCallbacks(mStylusGoneRunnable);
                onStylusGone();
                restoreSavedMinRefreshRate(intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        UserHandle.USER_NULL));
            }
        }, UserHandle.ALL, new IntentFilter(Intent.ACTION_USER_SWITCHED), null, mHandler);

        // The wake-up variant also reports while the screen is off
        final Sensor hall = mSensorManager.getDefaultSensor(TYPE_HALL_EFFECT, true);
        if (hall == null) {
            Log.w(TAG, "No hall effect sensor, folio cover disabled");
            return;
        }
        mSensorManager.registerListener(mHallListener, hall, SensorManager.SENSOR_DELAY_NORMAL,
                mHandler);

        // The light sensor only confirms a cover that closed on a lit screen,
        // so stop it as soon as the screen is off
        context.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                unregisterLight();
            }
        }, UserHandle.ALL, new IntentFilter(Intent.ACTION_SCREEN_OFF), null, mHandler);
    }

    @Override
    public KeyEvent handleKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_UNKNOWN
                || event.getScanCode() != SCAN_CODE_STYLUS_NEAR) {
            return event;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            mHandler.post(this::onStylusNear);
        }
        // Only the refresh rate needs it; apps never see it
        return null;
    }

    private void onStylusNear() {
        mHandler.removeCallbacks(mStylusGoneRunnable);
        mHandler.postDelayed(mStylusGoneRunnable, STYLUS_TIMEOUT_MS);
        if (mStylusNear) {
            return;
        }
        mStylusNear = true;
        // Runs in system_server: losing the stylus refresh rate beats a crash
        try {
            final int userId = ActivityManager.getCurrentUser();
            if (getUserMaxRefreshRate(userId) < STYLUS_SWITCH_MIN_PEAK) {
                return;
            }
            final ContentResolver cr = mContext.getContentResolver();
            // Saved before it is changed, so that a reboot in between still
            // finds it; a value already saved is the user's, keep it
            if (Settings.Secure.getStringForUser(cr, KEY_SAVED_MIN_REFRESH_RATE,
                    userId) == null) {
                final String min = Settings.System.getStringForUser(cr,
                        Settings.System.MIN_REFRESH_RATE, userId);
                // Unset reads as 0 in DisplayModeDirector
                Settings.Secure.putStringForUser(cr, KEY_SAVED_MIN_REFRESH_RATE,
                        min != null ? min : "0", userId);
            }
            mStylusUserId = userId;
            Settings.System.putStringForUser(cr, Settings.System.MIN_REFRESH_RATE,
                    STYLUS_MIN_REFRESH_RATE, userId);
            mContext.getSystemService(DisplayManager.class).requestMaxRefreshRate(
                    Display.DEFAULT_DISPLAY, STYLUS_REFRESH_RATE);
            startStylusMonitor();
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to raise the refresh rate for the stylus", e);
        }
    }

    private void onStylusGone() {
        mStylusNear = false;
        stopStylusMonitor();
        if (mStylusUserId == UserHandle.USER_NULL) {
            return;
        }
        final int userId = mStylusUserId;
        mStylusUserId = UserHandle.USER_NULL;
        try {
            mContext.getSystemService(DisplayManager.class).requestMaxRefreshRate(
                    Display.DEFAULT_DISPLAY, 0f);
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to drop the stylus refresh rate limit", e);
        }
        restoreSavedMinRefreshRate(userId);
    }

    /** Starts watching the pen; only called while the raised rate is in effect. */
    private void startStylusMonitor() {
        if (mStylusReceiver != null) {
            return;
        }
        try {
            final InputManager inputManager = mContext.getSystemService(InputManager.class);
            if (inputManager == null) {
                return;
            }
            final InputMonitor monitor = inputManager.monitorGestureInput("TB520FU stylus",
                    Display.DEFAULT_DISPLAY);
            try {
                mStylusReceiver = new StylusEventReceiver(monitor.getInputChannel(),
                        mHandler.getLooper());
                mStylusMonitor = monitor;
            } catch (RuntimeException e) {
                monitor.dispose();
                throw e;
            }
            mStylusRearmTime = SystemClock.uptimeMillis();
        } catch (RuntimeException e) {
            Log.w(TAG, "No stylus input monitor", e);
            stopStylusMonitor();
        }
    }

    private void stopStylusMonitor() {
        final InputMonitor monitor = mStylusMonitor;
        final InputEventReceiver receiver = mStylusReceiver;
        mStylusMonitor = null;
        mStylusReceiver = null;
        // The monitor owns the input channel
        if (monitor != null) {
            monitor.dispose();
        }
        if (receiver != null) {
            receiver.dispose();
        }
    }

    /** The pen keeps writing; push the end of the raised rate back. */
    private void rearmStylusTimeout() {
        if (mStylusReceiver == null) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        if (now - mStylusRearmTime < STYLUS_REARM_INTERVAL_MS) {
            return;
        }
        mStylusRearmTime = now;
        mHandler.removeCallbacks(mStylusGoneRunnable);
        mHandler.postDelayed(mStylusGoneRunnable, STYLUS_TIMEOUT_MS);
    }

    private static boolean isStylus(MotionEvent event) {
        for (int i = 0; i < event.getPointerCount(); i++) {
            final int toolType = event.getToolType(i);
            if (toolType == MotionEvent.TOOL_TYPE_STYLUS
                    || toolType == MotionEvent.TOOL_TYPE_ERASER) {
                return true;
            }
        }
        return false;
    }

    /**
     * The highest refresh rate the user setting allows, resolved the way
     * DisplayModeDirector does: max(peak, min), Infinity being the highest mode.
     */
    private float getUserMaxRefreshRate(int userId) {
        final ContentResolver cr = mContext.getContentResolver();
        float highest = 0f;
        final Display display = mContext.getSystemService(DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY);
        if (display != null) {
            for (Display.Mode mode : display.getSupportedModes()) {
                highest = Math.max(highest, mode.getRefreshRate());
            }
        }
        float peak = Settings.System.getFloatForUser(cr, Settings.System.PEAK_REFRESH_RATE,
                mContext.getResources().getInteger(
                        com.android.internal.R.integer.config_defaultPeakRefreshRate),
                userId);
        if (Float.isInfinite(peak)) {
            peak = highest;
        }
        float min = Settings.System.getFloatForUser(cr, Settings.System.MIN_REFRESH_RATE, 0f,
                userId);
        if (Float.isInfinite(min)) {
            min = highest;
        }
        return Math.max(peak, min);
    }

    private void restoreSavedMinRefreshRate(int userId) {
        if (userId == UserHandle.USER_NULL) {
            return;
        }
        final ContentResolver cr = mContext.getContentResolver();
        try {
            final String saved = Settings.Secure.getStringForUser(cr,
                    KEY_SAVED_MIN_REFRESH_RATE, userId);
            if (saved == null) {
                return;
            }
            // A minimum the user picked in the meantime (Force peak refresh
            // rate) stays
            if (STYLUS_MIN_REFRESH_RATE.equals(Settings.System.getStringForUser(cr,
                    Settings.System.MIN_REFRESH_RATE, userId))) {
                Settings.System.putStringForUser(cr, Settings.System.MIN_REFRESH_RATE, saved,
                        userId);
            }
            Settings.Secure.putStringForUser(cr, KEY_SAVED_MIN_REFRESH_RATE, null, userId);
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to restore the minimum refresh rate", e);
        }
    }

    private void onCover(boolean closed) {
        if (!closed) {
            unregisterLight();
            if (mWakeOnOpen && !mPowerManager.isInteractive()) {
                mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_LID,
                        "TB520FU:folio");
            }
            mWakeOnOpen = false;
        } else if (!mPowerManager.isInteractive()) {
            // Closed on a screen that is already off: opening wakes it
            mWakeOnOpen = true;
        } else if (mLightSensor == null) {
            sleepIfInteractive();
        } else if (!mLightRegistered) {
            mLightRegistered = mSensorManager.registerListener(mLightListener, mLightSensor,
                    SensorManager.SENSOR_DELAY_NORMAL, mHandler);
        }
    }

    private void sleepIfInteractive() {
        if (mPowerManager.isInteractive()) {
            mWakeOnOpen = true;
            mPowerManager.goToSleep(SystemClock.uptimeMillis(),
                    PowerManager.GO_TO_SLEEP_REASON_LID_SWITCH, 0);
        }
    }

    private void unregisterLight() {
        if (mLightRegistered) {
            mSensorManager.unregisterListener(mLightListener);
            mLightRegistered = false;
        }
    }
}
