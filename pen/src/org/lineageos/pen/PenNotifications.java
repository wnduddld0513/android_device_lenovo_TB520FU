/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * The pen notification.
 *
 * One IMPORTANCE_HIGH notification for a pen that lies on the charging pad and
 * is not bonded yet: "Pen attached" / "Tap to connect". Tapping it asks this app
 * to scan for the address the charger reported and to bond the pen, and the
 * standard Android pairing dialog takes the confirmation. A bonded pen gets
 * nothing, and the notification is cancelled as soon as the pen leaves the
 * pad.
 */
final class PenNotifications {
    /** Channel of the pairing prompt. */
    static final String CHANNEL = "LenovoPen";
    /** One prompt at a time. */
    private static final int ID_ATTACHED = 1000;

    private final Context mContext;
    private final NotificationManager mNotificationManager;

    PenNotifications(Context context) {
        mContext = context;
        mNotificationManager = context.getSystemService(NotificationManager.class);
    }

    void createChannel() {
        if (mNotificationManager == null) {
            return;
        }
        mNotificationManager.createNotificationChannel(new NotificationChannel(
                CHANNEL, mContext.getString(R.string.app_name),
                NotificationManager.IMPORTANCE_HIGH));
    }

    /** An unbonded pen was put on the pad: offer to pair it. */
    void notifyPenAttached(String mac) {
        if (mNotificationManager == null) {
            return;
        }
        final Intent intent = new Intent(mContext, PenService.class)
                .setAction(PenIntents.ACTION_BOND)
                .putExtra(PenIntents.EXTRA_MAC, mac);
        final PendingIntent pending = PendingIntent.getService(mContext, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        final Notification notification = new Notification.Builder(mContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_pen)
                .setContentTitle(mContext.getString(R.string.pen_attached))
                .setContentText(mContext.getString(R.string.tap_to_connect))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build();
        mNotificationManager.notify(ID_ATTACHED, notification);
    }

    /** The pen left the pad, or the prompt is no longer valid. */
    void cancel() {
        if (mNotificationManager != null) {
            mNotificationManager.cancel(ID_ATTACHED);
        }
    }
}
