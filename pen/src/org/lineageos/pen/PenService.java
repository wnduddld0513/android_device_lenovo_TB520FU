/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pen;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.UserHandle;

/**
 * The pen background service.
 *
 * It is started at boot, so that a pen put on the charging pad gets its pairing
 * prompt and a connected pen is set up without any user action. It runs in the
 * system user only, the pen is a device accessory and not a per profile one.
 */
public class PenService extends Service {
    private HandlerThread mThread;
    private PenLink mLink;

    @Override
    public void onCreate() {
        super.onCreate();
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) {
            stopSelf();
            return;
        }
        mThread = new HandlerThread("pen");
        mThread.start();
        final PenNotifications notifications = new PenNotifications(this);
        notifications.createChannel();
        mLink = new PenLink(this, new Handler(mThread.getLooper()), new PenHal(),
                notifications);
        mLink.start();
        // The charger only reports on change, ask for the state it has
        final Intent query = new Intent(PenIntents.ACTION_QUERY);
        query.setPackage(PenIntents.KEY_HANDLER_PACKAGE);
        PenIntents.send(this, query);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mLink == null) {
            return START_NOT_STICKY;
        }
        if (intent != null) {
            final String action = intent.getAction();
            if (PenIntents.ACTION_UEVENT.equals(action)) {
                mLink.onUEvent(intent);
            } else if (PenIntents.ACTION_BOND.equals(action)) {
                // The pairing notification was tapped
                mLink.bond(intent.getStringExtra(PenIntents.EXTRA_MAC));
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mThread != null) {
            mThread.quitSafely();
            mThread = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
