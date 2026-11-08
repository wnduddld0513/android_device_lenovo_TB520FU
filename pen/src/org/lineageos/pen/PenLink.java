/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pen;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import java.util.Arrays;
import java.util.UUID;

/**
 * The pen link: the pairing prompt and what the touch panel and the charging
 * pad need to know about the pen.
 *
 * The bond is the user's decision. A pen that the charger reports and that is
 * not bonded yet gets one notification ("Pen attached" / "Tap to connect");
 * tapping it runs a directed LE scan on the reported address and calls
 * createBond(), and the standard Android pairing dialog asks for the
 * confirmation. That is the flow of the LineageOS OnePlus pen app
 * (hardware/oplus packages/Pen, PenService.kt). A bonded pen gets no
 * notification: the Bluetooth stack reconnects its HID link.
 *
 * While the HID link of a Lenovo pen is up, the touch panel is told that a pen
 * is linked (setPenMode) and which pen it is (pen type), and the charging pad
 * is told to stop transmitting (402). A pen that reports "needs recharge"
 * (1029) on the Lenovo command channel makes the pad start charging it (403).
 */
final class PenLink {
    private static final String TAG = "PenLink";

    private static final int PEN_TYPE_NONE = 0;
    private static final int PEN_TYPE_TAB_PEN_PLUS = 1;
    private static final int PEN_TYPE_TAB_PEN_PRO = 2;
    private static final int PEN_TYPE_PRECISION_PEN_3 = 3;
    private static final int PEN_TYPE_TAB_PEN_PRO_2 = 4;

    // Bluetooth names of the Lenovo pens
    private static final String NAME_TAB_PEN_PLUS = "Lenovo Tab Pen Plus";
    private static final String NAME_TAB_PEN_PRO = "Lenovo Tab Pen Pro";
    private static final String NAME_TAB_PEN_PRO_2 = "Lenovo Tab Pen Pro 2";
    private static final String NAME_PRECISION_PEN_3 = "Lenovo Precision Pen 3";

    // Lenovo command channel of the pen
    private static final UUID SERVICE_LENOVO =
            UUID.fromString("0000fe40-cc7a-482a-984a-7f2ed5b3e512");
    private static final UUID CHAR_LENOVO_COMMAND =
            UUID.fromString("0000fe41-cc7a-482a-984a-7f2ed5b3e512");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** How long the pairing scan of the tapped address runs. */
    private static final long SCAN_TIMEOUT_MS = 30000;

    /** Command channel: the pen asks to be charged. */
    private static final int COMMAND_NEED_RECHARGE = 1029;

    private final Context mContext;
    private final Handler mHandler;
    private final PenHal mHal;
    private final PenNotifications mNotifications;
    private final BluetoothAdapter mAdapter;

    private BluetoothDevice mDevice;
    private BluetoothGatt mGatt;
    private String mTargetMac;
    private boolean mAttached;
    private boolean mCloseRxSent;

    private final Runnable mScanTimeout = () -> {
        Log.d(TAG, "pairing scan timed out");
        stopScan();
    };

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            final BluetoothDevice device = result.getDevice();
            if (device == null || !TextUtils.equals(device.getAddress(), mTargetMac)) {
                return;
            }
            Log.d(TAG, "pen found, asking for the bond");
            mHandler.removeCallbacks(mScanTimeout);
            stopScan();
            // The system pairing dialog asks the user, nothing is confirmed here
            device.createBond(BluetoothDevice.TRANSPORT_LE);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "pairing scan failed: " + errorCode);
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED.equals(intent.getAction())) {
                onHidStateChanged(intent);
            }
        }
    };

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "gatt state " + newState + " status " + status);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "service discovery failed: " + status);
                return;
            }
            final BluetoothGattService lenovo = gatt.getService(SERVICE_LENOVO);
            if (lenovo != null) {
                subscribe(gatt, lenovo.getCharacteristic(CHAR_LENOVO_COMMAND));
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic c,
                byte[] value) {
            if (CHAR_LENOVO_COMMAND.equals(c.getUuid())) {
                onPenCommand(value);
            }
        }
    };

    PenLink(Context context, Handler handler, PenHal hal, PenNotifications notifications) {
        mContext = context;
        mHandler = handler;
        mHal = hal;
        mNotifications = notifications;
        final BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        mAdapter = manager != null ? manager.getAdapter() : null;
    }

    void start() {
        mContext.registerReceiver(mReceiver,
                new IntentFilter(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED), null,
                mHandler);
    }

    /** The charger uevent relayed by the key handler. */
    void onUEvent(Intent intent) {
        final String type = intent.getStringExtra(PenIntents.EXTRA_TYPE);
        if (!"QI".equals(type) && !"NFC".equals(type)) {
            return;
        }
        final String mac = intent.getStringExtra(PenIntents.EXTRA_MAC);
        final boolean attached = "1".equals(intent.getStringExtra(PenIntents.EXTRA_ATTACHED));
        final boolean wasAttached = mAttached;
        mAttached = attached;
        if (attached) {
            mTargetMac = mac != null ? mac.toUpperCase() : null;
            mCloseRxSent = false;
            if (!wasAttached) {
                mHal.setPenBle(true);
            }
            if (mTargetMac != null) {
                promptIfUnbonded(mTargetMac);
            }
        } else {
            mHandler.removeCallbacks(mScanTimeout);
            stopScan();
            mNotifications.cancel();
            if (wasAttached) {
                mHal.setPenBle(false);
            }
        }
    }

    /** The user tapped the pairing notification: find the pen and bond it. */
    void bond(String mac) {
        if (mac == null || mAdapter == null || !mAdapter.isEnabled()) {
            return;
        }
        final BluetoothDevice device = remoteDevice(mac);
        if (device == null) {
            return;
        }
        if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
            Log.d(TAG, "pen already bonded");
            return;
        }
        mTargetMac = mac.toUpperCase();
        startScan();
    }

    /** One prompt per attached pen, and none for a pen that is already bonded. */
    private void promptIfUnbonded(String mac) {
        if (mAdapter == null || !mAdapter.isEnabled()) {
            return;
        }
        final BluetoothDevice device = remoteDevice(mac);
        if (device != null && device.getBondState() == BluetoothDevice.BOND_BONDED) {
            Log.d(TAG, "pen " + mac + " is bonded, no pairing prompt");
            return;
        }
        mNotifications.notifyPenAttached(mac);
    }

    private BluetoothDevice remoteDevice(String mac) {
        if (mAdapter == null) {
            return null;
        }
        try {
            return mAdapter.getRemoteDevice(mac.toUpperCase());
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "bad pen address " + mac, e);
            return null;
        }
    }

    private void startScan() {
        if (mAdapter == null || !mAdapter.isEnabled() || mTargetMac == null) {
            return;
        }
        Log.d(TAG, "scanning for " + mTargetMac);
        final ScanFilter filter = new ScanFilter.Builder()
                .setDeviceAddress(mTargetMac)
                .build();
        final ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setLegacy(false)
                .build();
        mAdapter.getBluetoothLeScanner().startScan(Arrays.asList(filter), settings,
                mScanCallback);
        mHandler.removeCallbacks(mScanTimeout);
        mHandler.postDelayed(mScanTimeout, SCAN_TIMEOUT_MS);
    }

    private void stopScan() {
        if (mAdapter != null && mAdapter.isEnabled()) {
            mAdapter.getBluetoothLeScanner().stopScan(mScanCallback);
        }
    }

    private void onHidStateChanged(Intent intent) {
        final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,
                BluetoothDevice.class);
        if (device == null) {
            return;
        }
        final int penType = penTypeByName(device.getName());
        if (penType == PEN_TYPE_NONE) {
            return;
        }
        final int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                BluetoothProfile.STATE_DISCONNECTED);
        Log.d(TAG, "pen hid state " + state);
        if (state == BluetoothProfile.STATE_CONNECTED) {
            mHal.setPenMode(true);
            mHal.setPenType(touchPenType(penType));
            closeChargingPad();
            openGatt(device);
        } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
            mHal.setPenMode(false);
        }
    }

    private void openGatt(BluetoothDevice device) {
        if (mGatt != null) {
            if (mDevice != null && TextUtils.equals(mDevice.getAddress(), device.getAddress())) {
                return;
            }
            mGatt.close();
            mGatt = null;
        }
        mDevice = device;
        mGatt = device.connectGatt(mContext, true, mGattCallback,
                BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, mHandler);
    }

    private void subscribe(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) {
            return;
        }
        gatt.setCharacteristicNotification(characteristic, true);
        final BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD);
        if (cccd != null) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        }
    }

    /** Notifications of the Lenovo command channel: a 16 bit little endian command. */
    private void onPenCommand(byte[] value) {
        if (value == null || value.length < 2) {
            return;
        }
        final int command = (value[0] & 0xff) | ((value[1] & 0xff) << 8);
        Log.d(TAG, "pen command " + command);
        if (command == COMMAND_NEED_RECHARGE) {
            mHal.setStylusQiCommand(PenHal.CMD_QI_START_BOTTOM_CHARGE);
        }
    }

    /** The pad stops transmitting while the pen is linked. */
    private void closeChargingPad() {
        if (mCloseRxSent) {
            return;
        }
        mCloseRxSent = true;
        mHal.setStylusQiCommand(PenHal.CMD_PAD_CLOSE_RX);
    }

    /** The pen type the touch panel expects for a pen. */
    private static int touchPenType(int penType) {
        switch (penType) {
            case PEN_TYPE_TAB_PEN_PLUS:
                return 2;
            case PEN_TYPE_TAB_PEN_PRO:
                return 5;
            case PEN_TYPE_PRECISION_PEN_3:
                return 1;
            case PEN_TYPE_TAB_PEN_PRO_2:
                return 6;
            default:
                return 0;
        }
    }

    /*
     * As stock getPenTypeByName: a name holding "Lenovo Tab Pen Pro" that is
     * not the Pro 2 is a Tab Pen Pro, so a Tab Pen Pro 3 is linked as one.
     */
    private static int penTypeByName(String name) {
        if (name == null) {
            return PEN_TYPE_NONE;
        }
        if (name.contains(NAME_TAB_PEN_PRO_2)) {
            return PEN_TYPE_TAB_PEN_PRO_2;
        }
        if (name.contains(NAME_TAB_PEN_PRO)) {
            return PEN_TYPE_TAB_PEN_PRO;
        }
        if (name.contains(NAME_TAB_PEN_PLUS)) {
            return PEN_TYPE_TAB_PEN_PLUS;
        }
        if (name.contains(NAME_PRECISION_PEN_3)) {
            return PEN_TYPE_PRECISION_PEN_3;
        }
        return PEN_TYPE_NONE;
    }
}
