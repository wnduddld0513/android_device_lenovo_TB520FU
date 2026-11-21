/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.dap

import android.app.Application

class DolbyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Every process start passes here before the tile, the screen or the boot receiver can
        // touch the effect, so the context is always known when an effect is (re)created.
        DolbyCore.init(this)
    }
}
