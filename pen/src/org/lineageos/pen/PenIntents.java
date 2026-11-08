/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pen;

import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

/**
 * Broadcast and service contract of the pen.
 *
 * Inbound (from the key handler in system_server, protected by the signature
 * permission declared by the KeyHandler APK):
 *   org.lineageos.pen.action.PEN_UEVENT   charger uevent, extras type, mac,
 *                                         attached, level, charging_state
 *
 * Outbound: org.lineageos.pen.action.QUERY_PEN asks the key handler for the
 * last charger uevent.
 *
 * Service (the pairing notification of this app): ACTION_BOND with the pen MAC.
 */
final class PenIntents {
    static final String PERMISSION = "org.lineageos.pen.permission.PEN_EVENTS";
    /** The key handler APK that observes the charger uevents. */
    static final String KEY_HANDLER_PACKAGE = "org.lineageos.settings.device";

    static final String ACTION_UEVENT = "org.lineageos.pen.action.PEN_UEVENT";
    static final String ACTION_QUERY = "org.lineageos.pen.action.QUERY_PEN";
    /** The user tapped the pairing notification. */
    static final String ACTION_BOND = "org.lineageos.pen.action.BOND_PEN";

    static final String EXTRA_TYPE = "type";
    static final String EXTRA_MAC = "mac";
    static final String EXTRA_ATTACHED = "attached";

    private PenIntents() {
    }

    static void send(Context context, Intent intent) {
        context.sendBroadcastAsUser(intent, UserHandle.SYSTEM, PERMISSION);
    }
}
