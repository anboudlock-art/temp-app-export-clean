package com.example.blepassword;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 蓝牙连接管理器 —— 基于固件源码逆向工程
 *
 * BLE Service UUIDs（来自 ABD_ble_lock_service.h 的 VendorV2 定义）:
 * - Service:  6E40000A-B5A3-F393-E0A9-E50E24DCCA9E
 * - Notify:   6E40000B-B5A3-F393-E0A9-E50E24DCCA9E  (设备→手机)
 * - Write:    6E40000C-B5A3-F393-E0A9-E50E24DCCA9E  (手机→设备)
 *
 * 所有通信都经过 AES-128-ECB 加密。
 * 连接流程：connect → discoverServices → enableNotification → onConnected
 */
public class BluetoothConnectionManager {
    private static final String TAG = "BleManager";

    // 正确的 UUID（来自固件 ABD_ble_lock_service.h VendorV2）
    private static final UUID SERVICE_UUID =
            UUID.fromString("6E40000A-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NOTIFY_UUID =
            UUID.fromString("6E40000B-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID WRITE_UUID =
            UUID.fromString("6E40000C-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CCC_DESCRIPTOR_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private static final int SCAN_TIMEOUT_MS = 10000;
    private static final int CONNECTION_TIMEOUT_MS = 15000;

    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;

    // 加密密钥
    private byte[] aesKey1;
    private byte[] aesKey2;
    private boolean timeSetSuccess = false;  // SET_TIME 是否成功
    private String connectedMac;

    // 回调
    private ScanCallback scanCallback;
    private ConnectionCallback connectionCallback;
    private DataCallback currentDataCallback;

    // 扫描回调实例（用于正确停止扫描）
    private android.bluetooth.le.ScanCallback leScanCallback;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler scanTimeoutHandler = new Handler(Looper.getMainLooper());
    private final Handler connectionTimeoutHandler = new Handler(Looper.getMainLooper());

    private boolean isScanning = false;

    // ===================== 回调接口 =====================

    public interface ScanCallback {
        void onDeviceFound(BluetoothDevice device, int rssi);
        void onScanComplete();
        default void onScanError(int errorCode, String message) { }
    }

    public interface ConnectionCallback {
        void onConnected();
        void onDisconnected();
        void onConnectionFailed(String error);
    }

    public interface ConnectionCallbackWithInfo extends ConnectionCallback {
        void onServicesDiscovered(String info);
    }

    public interface DataCallback {
        void onDataReceived(byte[] data);
        void onDataSent(boolean success);
    }

    public static String describeScanError(int errorCode) {
        switch (errorCode) {
            case 1: return "SCAN_FAILED_ALREADY_STARTED";
            case 2: return "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED (权限或注册失败)";
            case 3: return "SCAN_FAILED_INTERNAL_ERROR";
            case 4: return "SCAN_FAILED_FEATURE_UNSUPPORTED";
            case 5: return "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES";
            case 6: return "SCAN_FAILED_SCANNING_TOO_FREQUENTLY (请等 30 秒)";
            default: return "未知错误: " + errorCode;
        }
    }

    // ===================== 初始化 =====================

    public BluetoothConnectionManager(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.bluetoothAdapter = bm.getAdapter();
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    @SuppressLint("MissingPermission")
    public void enableBluetooth(Activity activity) {
        if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
            activity.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        }
    }

    /**
     * 获取当前使用的 AES 密钥
     * 未发送 SET_TIME 前用 key1，发送后用 key2
     */
    public byte[] getCurrentKey() {
        return timeSetSuccess ? aesKey2 : aesKey1;
    }

    public void setTimeSetSuccess(boolean success) {
        this.timeSetSuccess = success;
    }

    public byte[] getAesKey1() { return aesKey1; }
    public byte[] getAesKey2() { return aesKey2; }

    // ===================== 扫描 =====================

    @SuppressLint("MissingPermission")
    public void startScan(ScanCallback callback) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            if (callback != null) callback.onScanComplete();
            return;
        }

        this.scanCallback = callback;
        isScanning = true;

        scanTimeoutHandler.postDelayed(() -> {
            if (isScanning) stopScan();
        }, SCAN_TIMEOUT_MS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            startScanLollipop();
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private void startScanLollipop() {
        android.bluetooth.le.BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            Log.e(TAG, "BluetoothLeScanner 为空");
            finishScan();
            return;
        }

        android.bluetooth.le.ScanSettings settings = new android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        // 使用和老版本一样的 setDeviceName("LOCK_") 过滤器
        // （Vivo/MIUI 上无过滤器可能导致 error 2）
        List<android.bluetooth.le.ScanFilter> filters = new ArrayList<>();
        filters.add(new android.bluetooth.le.ScanFilter.Builder()
                .setDeviceName("LOCK_")
                .build());

        leScanCallback = new android.bluetooth.le.ScanCallback() {
            @Override
            public void onScanResult(int callbackType, android.bluetooth.le.ScanResult result) {
                BluetoothDevice device = result.getDevice();
                String name = device.getName();
                if (name != null && name.startsWith("LOCK_")) {
                    if (scanCallback != null) {
                        scanCallback.onDeviceFound(device, result.getRssi());
                    }
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                String reason = describeScanError(errorCode);
                Log.e(TAG, "扫描失败: " + errorCode + " - " + reason);
                isScanning = false;
                scanTimeoutHandler.removeCallbacksAndMessages(null);
                ScanCallback cb = scanCallback;
                scanCallback = null;
                if (cb != null) {
                    cb.onScanError(errorCode, reason);
                    cb.onScanComplete();
                }
            }
        };

        scanner.startScan(filters, settings, leScanCallback);
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (!isScanning) return;
        isScanning = false;
        scanTimeoutHandler.removeCallbacksAndMessages(null);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && bluetoothAdapter != null) {
            android.bluetooth.le.BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
            if (scanner != null && leScanCallback != null) {
                scanner.stopScan(leScanCallback);
            }
            leScanCallback = null;
        }

        finishScan();
    }

    private void finishScan() {
        ScanCallback cb = scanCallback;
        scanCallback = null;
        if (cb != null) cb.onScanComplete();
    }

    // ===================== 连接 =====================

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device, final ConnectionCallbackWithInfo callback) {
        if (device == null) {
            if (callback != null) callback.onConnectionFailed("设备为空");
            return;
        }

        String mac = device.getAddress();
        Log.d(TAG, "连接设备: " + device.getName() + " (" + mac + ")");

        // 断开现有连接
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }

        this.connectionCallback = callback;
        this.connectedMac = mac;
        this.timeSetSuccess = false;

        // 生成 AES 密钥
        aesKey1 = ProtoUtil.generateKey1(mac);
        aesKey2 = ProtoUtil.generateKey2(aesKey1, new Date());

        Log.d(TAG, "Key1: " + HexStringUtils.bytesToHexString(aesKey1));
        Log.d(TAG, "Key2: " + HexStringUtils.bytesToHexString(aesKey2));

        // 连接超时
        connectionTimeoutHandler.postDelayed(() -> {
            if (bluetoothGatt != null) {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
                bluetoothGatt = null;
                if (connectionCallback != null) {
                    connectionCallback.onConnectionFailed("连接超时");
                }
            }
        }, CONNECTION_TIMEOUT_MS);

        bluetoothGatt = device.connectGatt(context, false, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connectionTimeoutHandler.removeCallbacksAndMessages(null);
                Log.d(TAG, "已连接，开始发现服务");
                gatt.discoverServices();
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                connectionTimeoutHandler.removeCallbacksAndMessages(null);
                if (connectionCallback != null) {
                    connectionCallback.onDisconnected();
                }
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (connectionCallback != null) {
                    connectionCallback.onConnectionFailed("服务发现失败: " + status);
                }
                return;
            }

            // 查找服务和特征
            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                Log.e(TAG, "未找到 VendorV2 服务 (0x000A)");
                if (connectionCallback != null) {
                    connectionCallback.onConnectionFailed("未找到 BLE 锁服务");
                }
                return;
            }

            notifyCharacteristic = service.getCharacteristic(NOTIFY_UUID);
            writeCharacteristic = service.getCharacteristic(WRITE_UUID);

            if (notifyCharacteristic == null || writeCharacteristic == null) {
                if (connectionCallback != null) {
                    connectionCallback.onConnectionFailed("未找到 Notify/Write 特征");
                }
                return;
            }

            Log.d(TAG, "找到 VendorV2 服务和特征，启用通知");

            // 启用 Notify
            gatt.setCharacteristicNotification(notifyCharacteristic, true);
            BluetoothGattDescriptor descriptor =
                    notifyCharacteristic.getDescriptor(CCC_DESCRIPTOR_UUID);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor);
            }

            if (connectionCallback instanceof ConnectionCallbackWithInfo) {
                ((ConnectionCallbackWithInfo) connectionCallback)
                        .onServicesDiscovered("VendorV2 服务已就绪 (UUID: 0x000A)");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                                      int status) {
            if (status == BluetoothGatt.GATT_SUCCESS &&
                    descriptor.getUuid().equals(CCC_DESCRIPTOR_UUID)) {
                Log.d(TAG, "Notify 已启用，连接完成");
                if (connectionCallback != null) {
                    connectionCallback.onConnected();
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            byte[] rawData = characteristic.getValue();
            Log.d(TAG, "收到通知（加密）: " + HexStringUtils.bytesToHexString(rawData));

            if (currentDataCallback != null) {
                currentDataCallback.onDataReceived(rawData);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic, int status) {
            if (currentDataCallback != null) {
                currentDataCallback.onDataSent(status == BluetoothGatt.GATT_SUCCESS);
            }
        }
    };

    // ===================== 数据收发（带加密） =====================

    /**
     * 发送加密数据
     *
     * @param request  原始请求字节（未加密，含帧头和校验位占位）
     * @param key      AES 密钥（key1 或 key2）
     * @param callback 数据回调
     * @return true=写入成功提交
     */
    @SuppressLint("MissingPermission")
    public boolean sendEncryptedData(byte[] request, byte[] key, final DataCallback callback) {
        if (bluetoothGatt == null || writeCharacteristic == null) {
            Log.e(TAG, "未连接或 Write 特征不可用");
            return false;
        }

        // 加密
        byte[] encrypted = ProtoUtil.encryptRequest(key, request);
        if (encrypted == null) {
            Log.e(TAG, "加密失败");
            return false;
        }

        Log.d(TAG, "发送加密数据: " + HexStringUtils.bytesToHexString(encrypted));

        currentDataCallback = callback;
        writeCharacteristic.setValue(encrypted);
        writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        return bluetoothGatt.writeCharacteristic(writeCharacteristic);
    }

    /**
     * 断开连接
     */
    @SuppressLint("MissingPermission")
    public void disconnect() {
        scanTimeoutHandler.removeCallbacksAndMessages(null);
        connectionTimeoutHandler.removeCallbacksAndMessages(null);
        isScanning = false;
        timeSetSuccess = false;

        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }

        writeCharacteristic = null;
        notifyCharacteristic = null;
        connectionCallback = null;
        currentDataCallback = null;
    }
}
