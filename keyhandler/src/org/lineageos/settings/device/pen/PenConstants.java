/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device.pen;

/**
 * Contract between this system_server key handler and the LenovoPen app.
 *
 * The uevent keys come from the kernel driver (qti_battery_charger.c:
 * qi_uevent_report()).
 */
public final class PenConstants {
    /** The app that owns the pairing prompt and the pen link. */
    public static final String PEN_PACKAGE = "org.lineageos.pen";
    /**
     * Signature permission of all pen broadcasts. Declared by this APK (the
     * sender); the pen app holds it.
     */
    public static final String PERMISSION = "org.lineageos.pen.permission.PEN_EVENTS";

    /** Attach / detach / battery report of the pen charger. */
    public static final String ACTION_UEVENT = "org.lineageos.pen.action.PEN_UEVENT";

    /** UEventObserver match string of the pen charger (qti_battery_charger.c). */
    public static final String UEVENT_MATCH = "UEVENT_TO=PEN_FRAMEWORK";

    // Uevent environment, exactly as qi_uevent_report() builds it.
    public static final String UEVENT_TYPE = "TYPE";
    public static final String UEVENT_MAC = "MAC";
    public static final String UEVENT_ATTACHED = "ATTACHED";
    public static final String UEVENT_LEVEL = "LEVEL";
    public static final String UEVENT_CHARGING_STATE = "CHARGING_STATE";

    // Extras of ACTION_UEVENT.
    public static final String EXTRA_ATTACHED = "attached";
    public static final String EXTRA_LEVEL = "level";
    public static final String EXTRA_CHARGING_STATE = "charging_state";
    public static final String EXTRA_MAC = "mac";
    public static final String EXTRA_TYPE = "type";

    private PenConstants() {
    }
}
