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
import android.bluetooth.le.BluetoothLeScanner;
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
 * confirmation. A bonded pen gets no notification: the Bluetooth stack
 * reconnects its HID link.
 *
 * While the HID link of a Lenovo pen is up, the touch panel is told that a pen
 * is linked (setPenMode) and which pen it is (pen type); both are reset when
 * the link goes down, and the panel is told that no pen pairing is in progress
 * any more (setPenBle).
 *
 * A pen that reports "needs recharge" (1029) on the Lenovo command channel
 * makes the charging pad start charging it (403). A linked pen that is put on
 * the pad stops the pad receiver (402).
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
    private String mLinkedMac;
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
            if (!device.createBond(BluetoothDevice.TRANSPORT_LE)) {
                Log.w(TAG, "cannot start the bond to " + mTargetMac);
                mTargetMac = null;
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "pairing scan failed: " + errorCode);
            mHandler.removeCallbacks(mScanTimeout);
            stopScan();
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
        mHandler.post(() -> handleUEvent(intent));
    }

    private void handleUEvent(Intent intent) {
        final String type = intent.getStringExtra(PenIntents.EXTRA_TYPE);
        if (!"QI".equals(type) && !"NFC".equals(type)) {
            return;
        }
        final String attachedValue = intent.getStringExtra(PenIntents.EXTRA_ATTACHED);
        if (attachedValue == null) {
            return;
        }
        if ("1".equals(attachedValue)) {
            final String mac = intent.getStringExtra(PenIntents.EXTRA_MAC);
            final String attachedMac = mac != null ? mac.toUpperCase() : null;
            if (!isMacValid(attachedMac)) {
                return;
            }
            mTargetMac = attachedMac;
            // The linked pen was put on the pad: the pad receiver is shut down
            if (mLinkedMac != null && mLinkedMac.equalsIgnoreCase(attachedMac)) {
                closeChargingPad();
            }
            promptIfUnbonded(attachedMac);
        } else {
            mHandler.removeCallbacks(mScanTimeout);
            stopScan();
            mNotifications.cancel();
            mTargetMac = null;
            mCloseRxSent = false;
        }
    }

    /** A usable pen address: not the all-zero or all-ones placeholder. */
    private static boolean isMacValid(String mac) {
        if (mac == null || !BluetoothAdapter.checkBluetoothAddress(mac)) {
            return false;
        }
        return !"00:00:00:00:00:00".equals(mac) && !"FF:FF:FF:FF:FF:FF".equals(mac);
    }

    /** The user tapped the pairing notification: find the pen and bond it. */
    void bond(String mac) {
        mHandler.post(() -> startBonding(mac));
    }

    private void startBonding(String mac) {
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
        if (device == null) {
            return;
        }
        if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
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
        final BluetoothLeScanner scanner = mAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            Log.w(TAG, "no LE scanner, cannot scan for " + mTargetMac);
            mTargetMac = null;
            mHandler.removeCallbacks(mScanTimeout);
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
        scanner.startScan(Arrays.asList(filter), settings, mScanCallback);
        mHandler.removeCallbacks(mScanTimeout);
        mHandler.postDelayed(mScanTimeout, SCAN_TIMEOUT_MS);
    }

    private void stopScan() {
        if (mAdapter == null || !mAdapter.isEnabled()) {
            return;
        }
        final BluetoothLeScanner scanner = mAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            Log.w(TAG, "no LE scanner, no scan to stop");
            return;
        }
        scanner.stopScan(mScanCallback);
    }

    private void onHidStateChanged(Intent intent) {
        final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,
                BluetoothDevice.class);
        if (device == null) {
            return;
        }
        final int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                BluetoothProfile.STATE_DISCONNECTED);
        Log.d(TAG, "pen hid state " + state);
        if (state == BluetoothProfile.STATE_CONNECTED) {
            if (TextUtils.equals(device.getAddress(), mTargetMac)) {
                // The pen that was being paired is up, the scan is over
                mHandler.removeCallbacks(mScanTimeout);
                stopScan();
                mTargetMac = null;
                mNotifications.cancel();
            }
            final int penType = penTypeByName(device.getName());
            if (penType == PEN_TYPE_NONE) {
                return;
            }
            mLinkedMac = device.getAddress();
            openGatt(device);
            mHal.setPenMode(true);
            mHal.setPenType(touchPenType(penType));
            mHal.setPenBle(false);
        } else if (state == BluetoothProfile.STATE_DISCONNECTED
                && TextUtils.equals(mLinkedMac, device.getAddress())) {
            mLinkedMac = null;
            mHal.setPenType(PEN_TYPE_NONE);
            mHal.setPenMode(false);
            closeGatt();
        }
    }

    private void openGatt(BluetoothDevice device) {
        if (mGatt != null) {
            if (mDevice != null && TextUtils.equals(mDevice.getAddress(), device.getAddress())) {
                return;
            }
            closeGatt();
        }
        mDevice = device;
        mGatt = device.connectGatt(mContext, true, mGattCallback,
                BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, mHandler);
    }

    /** The pen is gone: the command channel is torn down. */
    private void closeGatt() {
        if (mGatt != null) {
            mGatt.close();
            mGatt = null;
        }
        mDevice = null;
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

    /** A linked pen is on the pad: the pad receiver is stopped once. */
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
     * A name holding "Lenovo Tab Pen Pro" that is not the Pro 2 is a Tab Pen
     * Pro, so a "Lenovo Tab Pen Pro 3" is linked as one.
     */
    private static int penTypeByName(String name) {
        if (name == null) {
            return PEN_TYPE_NONE;
        }
        if (name.contains(NAME_PRECISION_PEN_3)) {
            return PEN_TYPE_PRECISION_PEN_3;
        }
        if (name.contains(NAME_TAB_PEN_PLUS)) {
            return PEN_TYPE_TAB_PEN_PLUS;
        }
        if (name.contains(NAME_TAB_PEN_PRO) && !name.contains(NAME_TAB_PEN_PRO_2)) {
            return PEN_TYPE_TAB_PEN_PRO;
        }
        if (name.contains(NAME_TAB_PEN_PRO_2)) {
            return PEN_TYPE_TAB_PEN_PRO_2;
        }
        return PEN_TYPE_NONE;
    }
}
