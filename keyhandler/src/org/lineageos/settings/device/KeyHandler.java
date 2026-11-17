/*
 * SPDX-FileCopyrightText: The LineageOS Project
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
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;

import com.android.internal.os.DeviceKeyHandler;

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
 * still reports a stylus near the screen with KEY_SKIN_STYLUS, which has no key
 * code. Stock raises such a display to 120 Hz with its own SurfaceFlinger pen
 * timer (ro.surface_flinger.set_pen_timer_ms), above the refresh rate the user
 * picked. Here, while the reports come and the user setting allows 90 Hz or
 * more, the user's minimum refresh rate is raised to 120 Hz, which also lifts
 * the user's peak vote to max(peak, min), and DisplayManager#requestMaxRefreshRate()
 * keeps a 144 Hz setting from going above 120 Hz. The peak refresh rate the
 * Settings list shows is left untouched. The minimum is saved first and put
 * back STYLUS_TIMEOUT_MS after the last report, like the stock pen timer; a
 * value still saved at boot or on a user switch is put back then. A 30 or 60 Hz
 * setting keeps the scanning on and is left alone. This follows the pen mode
 * of the LineageOS xiaomi pipa tree (peripheralmanager RefreshUtils), which
 * saves and rewrites the refresh rate settings while its pen is in use. The
 * panel has no VRR, so DisplayManager#requestDisplayModes() is ignored, and an
 * application frame rate vote cannot go above the user's peak.
 *
 * Double tap to wake: see DoubleTapToWake.
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
    // Settings.Secure, per user: Settings.System.MIN_REFRESH_RATE as it was
    // before the stylus raised it
    private static final String KEY_SAVED_MIN_REFRESH_RATE = "tb520fu_stylus_min_refresh_rate";

    private final Context mContext;
    private final PowerManager mPowerManager;
    private final SensorManager mSensorManager;
    private final Sensor mLightSensor;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private boolean mLightRegistered;
    // The cover was closed on a dark screen, so opening it wakes the device
    private boolean mWakeOnOpen;

    private boolean mStylusNear;
    // User whose minimum refresh rate the stylus raised, USER_NULL if none
    private int mStylusUserId = UserHandle.USER_NULL;
    private final Runnable mStylusGoneRunnable = this::onStylusGone;

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
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to raise the refresh rate for the stylus", e);
        }
    }

    private void onStylusGone() {
        mStylusNear = false;
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
