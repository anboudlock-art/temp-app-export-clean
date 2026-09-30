package com.example.blepassword;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.UUID;

/**
 * 蓝牙连接管理：扫描、连接、开通知、原样收发 16 字节密文。
 * 加密/解密不在这里做，见 BleLockSdk。
 *
 * 锁的 GATT（《智能锁4G+蓝牙通讯协议 v2.0》）：
 *   Service  6E40000A-B5A3-F393-E0A9-E50E24DCCA9E
 *   Notify   6E40000B-…   锁 → 手机
 *   Write    6E40000C-…   手机 → 锁
 */
public class BluetoothConnectionManager {
    private static final String TAG = "BluetoothManager";

    private static final UUID CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private static final int SCAN_TIMEOUT_MS = 10000;
    private static final int CONNECTION_TIMEOUT_MS = 15000;

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;

    private ScanCallback scanCallback;
    private ConnectionCallback connectionCallback;
    private DataListener dataListener;

    private final Handler scanTimeoutHandler = new Handler(Looper.getMainLooper());
    private final Handler connectionTimeoutHandler = new Handler(Looper.getMainLooper());

    private boolean isScanning = false;
    private boolean isConnecting = false;
    private String connectedAddress = null;

    public interface ScanCallback {
        void onDeviceFound(BluetoothDevice device, int rssi);
        void onScanComplete();
    }

    /** onConnected 在「通知已开启」之后才会回调，此时可以直接发命令 */
    public interface ConnectionCallback {
        void onConnected();
        void onDisconnected();
        void onConnectionFailed(String error);
    }

    /** 所有通知都走这一个监听，由上层按 CMD 分发 */
    public interface DataListener {
        void onNotify(byte[] encrypted16);
        void onWriteResult(boolean success);
    }

    public BluetoothConnectionManager(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.bluetoothAdapter = bm != null ? bm.getAdapter() : null;
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    @SuppressLint("MissingPermission")
    public void enableBluetooth(android.app.Activity activity) {
        if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
            activity.startActivityForResult(new android.content.Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 1);
        }
    }

    public void setDataListener(DataListener listener) {
        this.dataListener = listener;
    }

    /** 已连接锁的 MAC（"AA:BB:CC:DD:EE:FF"），用来算 key1 */
    public String getConnectedAddress() {
        return connectedAddress;
    }

    // ---------------- 扫描 ----------------

    @SuppressLint("MissingPermission")
    public void startScan(ScanCallback callback) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            if (callback != null) callback.onScanComplete();
            return;
        }
        this.scanCallback = callback;
        isScanning = true;
        scanTimeoutHandler.postDelayed(() -> { if (isScanning) stopScan(); }, SCAN_TIMEOUT_MS);
        bluetoothAdapter.startLeScan(leScanCallback);
    }

    private final BluetoothAdapter.LeScanCallback leScanCallback = (device, rssi, scanRecord) -> {
        String name = device.getName();
        if (name != null && name.startsWith("LOCK_") && scanCallback != null) {
            scanCallback.onDeviceFound(device, rssi);
        }
    };

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (!isScanning) return;
        isScanning = false;
        scanTimeoutHandler.removeCallbacksAndMessages(null);
        if (bluetoothAdapter != null) bluetoothAdapter.stopLeScan(leScanCallback);
        ScanCallback cb = scanCallback;
        scanCallback = null;
        if (cb != null) cb.onScanComplete();
    }

    // ---------------- 连接 ----------------

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device, ConnectionCallback callback) {
        if (device == null) {
            if (callback != null) callback.onConnectionFailed("设备为空");
            return;
        }
        closeGatt();
        this.connectionCallback = callback;
        this.connectedAddress = device.getAddress();
        isConnecting = true;

        connectionTimeoutHandler.postDelayed(() -> {
            if (isConnecting) {
                Log.e(TAG, "连接超时");
                isConnecting = false;
                closeGatt();
                if (connectionCallback != null) connectionCallback.onConnectionFailed("连接超时");
            }
        }, CONNECTION_TIMEOUT_MS);

        bluetoothGatt = device.connectGatt(context, false, gattCallback);
        Log.d(TAG, "已发起连接: " + device.getAddress());
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "连接状态变化 status=" + status + " newState=" + newState);
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                gatt.discoverServices();
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                boolean wasConnecting = isConnecting;
                isConnecting = false;
                connectionTimeoutHandler.removeCallbacksAndMessages(null);
                if (connectionCallback != null) {
                    if (wasConnecting) connectionCallback.onConnectionFailed("连接断开 status=" + status);
                    else connectionCallback.onDisconnected();
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("服务发现失败: " + status);
                return;
            }
            BluetoothGattService service = gatt.getService(BleLockSdk.SERVICE_UUID);
            if (service == null) { fail("未找到锁服务 6E40000A（不是我们的锁，或固件不对）"); return; }
            notifyCharacteristic = service.getCharacteristic(BleLockSdk.NOTIFY_UUID);
            writeCharacteristic = service.getCharacteristic(BleLockSdk.WRITE_UUID);
            if (notifyCharacteristic == null || writeCharacteristic == null) { fail("特征缺失"); return; }
            if (!enableNotification(gatt, notifyCharacteristic)) fail("开启通知失败");
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (!descriptor.getUuid().equals(CCC_DESCRIPTOR_UUID)) return;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "通知已开启");
                isConnecting = false;
                connectionTimeoutHandler.removeCallbacksAndMessages(null);
                if (connectionCallback != null) connectionCallback.onConnected();
            } else {
                fail("CCC 写入失败: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            Log.d(TAG, "通知: " + HexStringUtils.bytesToHexString(data));
            if (dataListener != null && data != null) dataListener.onNotify(data);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (dataListener != null) dataListener.onWriteResult(status == BluetoothGatt.GATT_SUCCESS);
        }

        private void fail(String msg) {
            Log.e(TAG, msg);
            isConnecting = false;
            connectionTimeoutHandler.removeCallbacksAndMessages(null);
            closeGatt();
            if (connectionCallback != null) connectionCallback.onConnectionFailed(msg);
        }
    };

    @SuppressLint("MissingPermission")
    private boolean enableNotification(BluetoothGatt gatt, BluetoothGattCharacteristic ch) {
        if (!gatt.setCharacteristicNotification(ch, true)) return false;
        BluetoothGattDescriptor d = ch.getDescriptor(CCC_DESCRIPTOR_UUID);
        if (d == null) return false;
        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        return gatt.writeDescriptor(d);
    }

    // ---------------- 收发 ----------------

    /** 写 16 字节密文到锁 */
    @SuppressLint("MissingPermission")
    public boolean write(byte[] encrypted16) {
        if (bluetoothGatt == null || writeCharacteristic == null) return false;
        writeCharacteristic.setValue(encrypted16);
        writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        boolean ok = bluetoothGatt.writeCharacteristic(writeCharacteristic);
        Log.d(TAG, "写入 " + HexStringUtils.bytesToHexString(encrypted16) + " -> " + ok);
        return ok;
    }

    @SuppressLint("MissingPermission")
    public void disconnect() {
        scanTimeoutHandler.removeCallbacksAndMessages(null);
        connectionTimeoutHandler.removeCallbacksAndMessages(null);
        isConnecting = false;
        isScanning = false;
        closeGatt();
        connectionCallback = null;
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        if (bluetoothGatt != null) {
            try { bluetoothGatt.disconnect(); bluetoothGatt.close(); } catch (Exception ignored) {}
            bluetoothGatt = null;
        }
        writeCharacteristic = null;
        notifyCharacteristic = null;
    }
}
