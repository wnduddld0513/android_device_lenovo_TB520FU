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
 * The two stock Lenovo vendor HALs the pen needs, called as raw binder
 * transactions.
 *
 * Stock ZUI talks to them through the Java stubs in framework.jar
 * (vendor.lenovo.hardware.touchscreen.ITouchscreen VERSION 6 and
 * vendor.lenovo.hardware.battery.IBattery). We do not copy those interface
 * files: only the few methods the pen uses are called, with the transaction
 * codes of the stock stubs and the parcel layout AIDL generates (interface
 * token, then the arguments; the reply starts with the exception status).
 *
 * Transaction codes, verified against framework.jar classes6.dex and against
 * the vendor NDK libraries (vendor.lenovo.hardware.*-V*-ndk.so):
 *   ITouchscreen#setPenMode(boolean)  11    setPenBle(boolean)          12
 *   ITouchscreen#ioctl(int, String)   13
 *   IBattery#setStylusQiCommand(int)  25 (25 = 0x19, AIDL VERSION 5)
 *
 * The pen type is not a transaction of its own: the vendor touchscreen
 * library has no setPentype (the Java stub in framework.jar declares one at
 * code 21, but the HAL does not implement it), and stock sets it with
 * ioctl(IOCTL_CMD_PEN_TYPE = 3, penType) - TouchscreenServiceManager#setPenType.
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
        callBool(TOUCHSCREEN, TS_SET_PEN_MODE, parcel -> parcel.writeInt(enabled ? 1 : 0));
    }

    /** Touch panel: true while the pen link is being established. */
    void setPenBle(boolean enabled) {
        callBool(TOUCHSCREEN, TS_SET_PEN_BLE, parcel -> parcel.writeInt(enabled ? 1 : 0));
    }

    /** Touch panel: pen type reported to the IC. */
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

    /** Pen charger: stock IBattery#setStylusQiCommand (writes wls_tx/cmd). */
    boolean setStylusQiCommand(int command) {
        return callBool(BATTERY, BATTERY_SET_STYLUS_QI_COMMAND, parcel -> parcel.writeInt(command));
    }

    private boolean callBool(String descriptor, int code, Args args) {
        final Parcel reply = call(descriptor, code, args);
        if (reply == null) {
            return false;
        }
        try {
            return reply.readInt() != 0;
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
