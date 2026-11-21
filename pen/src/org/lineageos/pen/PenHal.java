/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pen;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * The two Lenovo vendor HALs the pen drives, called as raw binder transactions.
 *
 * Only the pen methods of either interface are used, so the AIDL interface
 * files are not copied into the app: every call is built here with the
 * transaction codes of the interfaces and the parcel layout AIDL generates
 * (interface token, then the arguments; the reply starts with the exception
 * status).
 *
 *   ITouchscreen#setPenMode(boolean)   11
 *   ITouchscreen#setPenBle(boolean)    12
 *   ITouchscreen#ioctl(int, String)    13
 *   IBattery#setStylusQiCommand(int)   25
 *
 * The touch panel pen type has no transaction of its own: the touchscreen HAL
 * implements no setPentype, so the type is sent with
 * ioctl(IOCTL_CMD_PEN_TYPE = 3, penType) instead.
 */
final class PenHal {
    private static final String TAG = "PenHal";

    private static final String TOUCHSCREEN = "vendor.lenovo.hardware.touchscreen.ITouchscreen";
    private static final String BATTERY = "vendor.lenovo.hardware.battery.IBattery";

    private static final int TS_SET_PEN_MODE = 11;
    private static final int TS_SET_PEN_BLE = 12;
    private static final int TS_IOCTL = 13;
    private static final int BATTERY_SET_STYLUS_QI_COMMAND = 25;

    /** Touch panel ioctl command of the pen type. */
    private static final int IOCTL_CMD_PEN_TYPE = 3;

    /** Pen charger commands. */
    static final int CMD_PAD_CLOSE_RX = 402;
    static final int CMD_QI_START_BOTTOM_CHARGE = 403;

    private interface Args {
        void write(Parcel parcel);
    }

    /** Touch panel: true while the pen (HID) link is up. */
    void setPenMode(boolean enabled) {
        callBool(TOUCHSCREEN, TS_SET_PEN_MODE, parcel -> parcel.writeBoolean(enabled));
    }

    /** Touch panel: pairing mode, true while a pen link is being set up. */
    void setPenBle(boolean enabled) {
        callBool(TOUCHSCREEN, TS_SET_PEN_BLE, parcel -> parcel.writeBoolean(enabled));
    }

    /** Touch panel: pen type reported to the IC, 0 when no pen is linked. */
    void setPenType(int penType) {
        ioctl(IOCTL_CMD_PEN_TYPE, String.valueOf(penType));
    }

    private String ioctl(int command, String data) {
        final Parcel reply = call(TOUCHSCREEN, TS_IOCTL, parcel -> {
            parcel.writeInt(command);
            parcel.writeString(data);
        });
        if (reply == null) {
            return null;
        }
        try {
            return reply.readString();
        } finally {
            reply.recycle();
        }
    }

    /** Pen charger: IBattery#setStylusQiCommand (writes wls_tx/cmd). */
    boolean setStylusQiCommand(int command) {
        return callBool(BATTERY, BATTERY_SET_STYLUS_QI_COMMAND, parcel -> parcel.writeInt(command));
    }

    private boolean callBool(String descriptor, int code, Args args) {
        final Parcel reply = call(descriptor, code, args);
        if (reply == null) {
            return false;
        }
        try {
            return reply.readBoolean();
        } finally {
            reply.recycle();
        }
    }

    private Parcel call(String descriptor, int code, Args args) {
        final IBinder binder;
        try {
            binder = ServiceManager.checkService(descriptor + "/default");
        } catch (RuntimeException e) {
            Log.w(TAG, descriptor + " is not reachable", e);
            return null;
        }
        if (binder == null) {
            return null;
        }
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            if (args != null) {
                args.write(data);
            }
            if (!binder.transact(code, data, reply, 0)) {
                reply.recycle();
                return null;
            }
            reply.readException();
            return reply;
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "call " + descriptor + "#" + code + " failed", e);
            reply.recycle();
            return null;
        } finally {
            data.recycle();
        }
    }
}
