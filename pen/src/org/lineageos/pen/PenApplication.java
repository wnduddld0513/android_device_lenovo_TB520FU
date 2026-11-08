/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pen;

import android.app.Application;
import android.content.Intent;

/**
 * The app is persistent, so the pen service is running from boot: a pen put on
 * the charging pad has to get its pairing prompt without any user action.
 */
public class PenApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        startService(new Intent(this, PenService.class));
    }
}
