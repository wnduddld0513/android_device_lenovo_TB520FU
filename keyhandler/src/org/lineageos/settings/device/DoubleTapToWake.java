/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Double tap to wake: Settings > Display > Tap to wake
 * (Settings.Secure.DOUBLE_TAP_TO_WAKE, shown by config_supportDoubleTapWake).
 *
 * The Novatek touch driver (nt36xxx) keeps the controller in its wakeup gesture
 * mode across suspend while /proc/gesture_control is 1, skips it while the folio
 * cover is closed, and reports a double tap as KEY_POWER. PowerManagerService
 * hands the setting to the power HAL as Mode.DOUBLE_TAP_TO_WAKE, but the stock
 * QTI power HAL does not implement it: stock ZUI switches the node through its
 * own touchscreen HAL (setDoubleGesture). So apply the setting of the current
 * user here, like PowerManagerService does.
 */
final class DoubleTapToWake {
    private static final String TAG = "DoubleTapToWake";
    private static final String GESTURE_CONTROL = "/proc/gesture_control";

    private final Context mContext;

    DoubleTapToWake(Context context, Handler handler) {
        mContext = context;
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DOUBLE_TAP_TO_WAKE), false,
                new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        update();
                    }
                }, UserHandle.USER_ALL);
        context.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                update();
            }
        }, UserHandle.ALL, new IntentFilter(Intent.ACTION_USER_SWITCHED), null, handler);
        update();
    }

    private void update() {
        final boolean enabled = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.DOUBLE_TAP_TO_WAKE, 0, UserHandle.USER_CURRENT) != 0;
        try (FileOutputStream out = new FileOutputStream(GESTURE_CONTROL)) {
            out.write(enabled ? '1' : '0');
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + GESTURE_CONTROL, e);
        }
    }
}
