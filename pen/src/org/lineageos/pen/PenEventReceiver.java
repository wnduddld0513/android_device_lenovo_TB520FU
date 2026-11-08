/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pen;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Receives the charger uevents the key handler in system_server relays and
 * hands them to the pen service.
 *
 * Only the platform signed apps can send this broadcast, the permission is
 * declared by the KeyHandler APK.
 */
public class PenEventReceiver extends BroadcastReceiver {
    private static final String TAG = "PenEventReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            context.startService(new Intent(intent).setClass(context, PenService.class));
        } catch (IllegalStateException e) {
            Log.w(TAG, "cannot reach the pen service", e);
        }
    }
}
