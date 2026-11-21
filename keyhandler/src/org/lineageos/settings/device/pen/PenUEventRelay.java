/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device.pen;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.UEventObserver;
import android.os.UserHandle;
import android.util.Log;

/**
 * Forwards the pen charger uevents to the LenovoPen app.
 *
 * The charger driver (qti_battery_charger.c, qi_uevent_report()) emits one
 * kobject uevent on UEVENT_TO=PEN_FRAMEWORK whenever the pen is placed on or
 * taken off the magnetic pad, carrying TYPE, MAC, ATTACHED, LEVEL and
 * CHARGING_STATE. Only system_server may observe kernel uevents
 * (system/sepolicy: allow system_server self:netlink_kobject_uevent_socket),
 * which is why the observer lives here and the app gets a broadcast.
 *
 * The last uevent is cached and replayed when the app asks for it, so a restart
 * of the app does not lose the attach state: the driver only reports on change.
 */
public final class PenUEventRelay {
    private static final String TAG = "PenUEventRelay";

    /** Asks for the cached uevent, for example after the app was restarted. */
    static final String ACTION_QUERY = "org.lineageos.pen.action.QUERY_PEN";

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final String[] mLast = new String[5];
    private boolean mHasLast;

    private final UEventObserver mObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEvent event) {
            final String type = event.get(PenConstants.UEVENT_TYPE);
            final String attached = event.get(PenConstants.UEVENT_ATTACHED);
            final String mac = event.get(PenConstants.UEVENT_MAC);
            final String level = event.get(PenConstants.UEVENT_LEVEL);
            final String charging = event.get(PenConstants.UEVENT_CHARGING_STATE);
            synchronized (PenUEventRelay.this) {
                mLast[0] = type;
                mLast[1] = mac;
                mLast[2] = attached;
                mLast[3] = level;
                mLast[4] = charging;
                mHasLast = true;
            }
            Log.d(TAG, "pen uevent type=" + type + " mac=" + mac + " attached=" + attached
                    + " level=" + level + " charging=" + charging);
            send(type, mac, attached, level, charging);
        }
    };

    private final BroadcastReceiver mQueryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String[] last;
            synchronized (PenUEventRelay.this) {
                if (!mHasLast) {
                    return;
                }
                last = mLast.clone();
            }
            send(last[0], last[1], last[2], last[3], last[4]);
        }
    };

    public PenUEventRelay(Context context) {
        mContext = context;
    }

    public void start() {
        mObserver.startObserving(PenConstants.UEVENT_MATCH);
        // The pen app is another app, so the query arrives from outside this
        // package; the signature permission keeps every other one out.
        mContext.registerReceiver(mQueryReceiver, new IntentFilter(ACTION_QUERY),
                PenConstants.PERMISSION, mHandler, Context.RECEIVER_EXPORTED);
    }

    private void send(String type, String mac, String attached, String level, String charging) {
        final Intent intent = new Intent(PenConstants.ACTION_UEVENT);
        intent.setPackage(PenConstants.PEN_PACKAGE);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        intent.putExtra(PenConstants.EXTRA_TYPE, type);
        intent.putExtra(PenConstants.EXTRA_MAC, mac);
        intent.putExtra(PenConstants.EXTRA_ATTACHED, attached);
        intent.putExtra(PenConstants.EXTRA_LEVEL, level);
        intent.putExtra(PenConstants.EXTRA_CHARGING_STATE, charging);
        // A pen belongs to the device, not to a user profile.
        mContext.sendBroadcastAsUser(intent, UserHandle.SYSTEM, PenConstants.PERMISSION);
    }
}
