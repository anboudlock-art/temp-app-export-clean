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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 蓝牙连接管理器 - 负责扫描、连接、服务发现和数据传输
 * 
 * Nordic UART Service UUIDs (SYD8811协议):
 * - Service: 6E400007-B5A3-F393-E0A9-E50E24DCCA9E
 * - TX Characteristic: 6E400008-B5A3-F393-E0A9-E50E24DCCA9E (Device → Phone)
 * - RX Characteristic: 6E400009-B5A3-F393-E0A9-E50E24DCCA9E (Phone → Device)
 * - CCC Descriptor: 00002902-0000-1000-8000-00805F9B34FB
 */
public class BluetoothConnectionManager {
    private static final String TAG = "BluetoothManager";

    // Nordic UART Service UUIDs (SYD8811协议标准配置)
    private static final UUID NORDIC_UART_SERVICE_UUID = UUID.fromString("6E400007-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID TX_CHARACTERISTIC_UUID = UUID.fromString("6E400008-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID RX_CHARACTERISTIC_UUID = UUID.fromString("6E400009-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private static final int SCAN_TIMEOUT_MS = 10000;
    private static final int CONNECTION_TIMEOUT_MS = 15000;

    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic rxCharacteristic;
    private BluetoothGattCharacteristic txCharacteristic;

    private ScanCallback scanCallback;
    private ConnectionCallback connectionCallback;
    private DataCallback currentDataCallback;

    private final Handler scanTimeoutHandler = new Handler(Looper.getMainLooper());
    private final Handler connectionTimeoutHandler = new Handler(Looper.getMainLooper());
    private final Handler dataTimeoutHandler = new Handler(Looper.getMainLooper());

    private boolean isScanning = false;
    private boolean isConnecting = false;

    private final CopyOnWriteArraySet<String> pendingCallbacks = new CopyOnWriteArraySet<>();

    /**
     * 扫描回调
     */
    public interface ScanCallback {
        void onDeviceFound(BluetoothDevice device, int rssi);
        void onScanComplete();
    }

    /**
     * 连接回调
     */
    public interface ConnectionCallback {
        void onConnected();
        void onDisconnected();
        void onConnectionFailed(String error);
    }

    /**
     * 带详细信息的连接回调
     */
    public interface ConnectionCallbackWithInfo extends ConnectionCallback {
        void onServicesDiscovered(String info);
    }

    /**
     * 数据回调
     */
    public interface DataCallback {
        void onDataReceived(byte[] data);
        void onDataSent(boolean success);
    }

    public BluetoothConnectionManager(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.bluetoothAdapter = bluetoothManager.getAdapter();
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    @SuppressLint("MissingPermission")
    public void enableBluetooth(android.app.Activity activity) {
        if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
            android.content.Intent enableBtIntent = new android.content.Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activity.startActivityForResult(enableBtIntent, 1);
        }
    }

    /**
     * 开始扫描蓝牙设备
     */
    @SuppressLint("MissingPermission")
    public void startScan(ScanCallback callback) {
        Log.d(TAG, "开始扫描蓝牙设备");

        if (bluetoothAdapter == null) {
            Log.e(TAG, "蓝牙适配器为空");
            if (callback != null) {
                callback.onScanComplete();
            }
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "蓝牙未开启");
            if (callback != null) {
                callback.onScanComplete();
            }
            return;
        }

        this.scanCallback = callback;
        isScanning = true;

        // 扫描超时
        scanTimeoutHandler.postDelayed(() -> {
            if (isScanning) {
                Log.d(TAG, "扫描超时，停止扫描");
                stopScan();
            }
        }, SCAN_TIMEOUT_MS);

        // 根据Android版本使用不同的扫描API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Android 5.0+ 使用新API
            startScanLollipop();
        } else {
            // Android 4.3-4.4 使用旧API
            startScanLegacy();
        }
    }

    /**
     * Android 5.0+ 扫描
     */
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private void startScanLollipop() {
        android.bluetooth.le.BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner != null) {
            android.bluetooth.le.ScanSettings settings = new android.bluetooth.le.ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            
            android.bluetooth.le.ScanFilter filter = new android.bluetooth.le.ScanFilter.Builder()
                    .setDeviceName("LOCK_")
                    .build();
            
            List<android.bluetooth.le.ScanFilter> filters = new ArrayList<>();
            filters.add(filter);

            scanner.startScan(filters, settings, new android.bluetooth.le.ScanCallback() {
                @Override
                public void onScanResult(int callbackType, android.bluetooth.le.ScanResult result) {
                    BluetoothDevice device = result.getDevice();
                    String name = device.getName();
                    
                    Log.d(TAG, "发现设备: " + name + " (" + device.getAddress() + ")");
                    
                    // 只处理名称以LOCK_开头的设备
                    if (name != null && name.startsWith("LOCK_")) {
                        if (scanCallback != null) {
                            scanCallback.onDeviceFound(device, result.getRssi());
                        }
                    }
                }

                @Override
                public void onBatchScanResults(List<android.bluetooth.le.ScanResult> results) {
                    Log.d(TAG, "批量扫描结果: " + results.size() + "个设备");
                }

                @Override
                public void onScanFailed(int errorCode) {
                    Log.e(TAG, "扫描失败: " + errorCode);
                    if (scanCallback != null) {
                        scanCallback.onScanComplete();
                    }
                }
            });
        } else {
            Log.e(TAG, "扫描器为空");
            if (scanCallback != null) {
                scanCallback.onScanComplete();
            }
        }
    }

    /**
     * Android 4.3-4.4 扫描（旧API）
     */
    @SuppressLint("MissingPermission")
    private void startScanLegacy() {
        bluetoothAdapter.startLeScan(new BluetoothAdapter.LeScanCallback() {
            @Override
            public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
                String name = device.getName();
                
                Log.d(TAG, "发现设备: " + name + " (" + device.getAddress() + ")");
                
                // 只处理名称以LOCK_开头的设备
                if (name != null && name.startsWith("LOCK_")) {
                    if (scanCallback != null) {
                        scanCallback.onDeviceFound(device, rssi);
                    }
                }
            }
        });
    }

    /**
     * 停止扫描
     */
    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (!isScanning) {
            return;
        }

        isScanning = false;
        scanTimeoutHandler.removeCallbacksAndMessages(null);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && bluetoothAdapter != null) {
            android.bluetooth.le.BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
            if (scanner != null) {
                scanner.stopScan(new android.bluetooth.le.ScanCallback() {});
            }
        } else if (bluetoothAdapter != null) {
            bluetoothAdapter.stopLeScan(null);
        }

        Log.d(TAG, "扫描已停止");
        
        ScanCallback callback = scanCallback;
        scanCallback = null;
        if (callback != null) {
            callback.onScanComplete();
        }
    }

    /**
     * 连接设备
     */
    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device, final ConnectionCallbackWithInfo callback) {
        Log.d(TAG, "连接设备: " + device.getName() + " (" + device.getAddress() + ")");

        if (device == null) {
            Log.e(TAG, "设备为空");
            if (callback != null) {
                callback.onConnectionFailed("设备为空");
            }
            return;
        }

        // 断开现有连接
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }

        this.connectionCallback = callback;
        isConnecting = true;

        // 连接超时
        connectionTimeoutHandler.postDelayed(() -> {
            if (isConnecting && bluetoothGatt != null) {
                Log.e(TAG, "连接超时，断开连接");
                isConnecting = false;
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
                bluetoothGatt = null;
                
                if (connectionCallback != null) {
                    connectionCallback.onConnectionFailed("连接超时");
                }
            }
        }, CONNECTION_TIMEOUT_MS);

        // 连接设备（autoConnect=false，确保立即连接）
        bluetoothGatt = device.connectGatt(context, false, gattCallback);
        Log.d(TAG, "已发起连接请求");
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "连接状态变化 - status: " + status + ", newState: " + newState);
            
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d(TAG, "已连接到设备: " + gatt.getDevice().getName());
                isConnecting = false;
                connectionTimeoutHandler.removeCallbacksAndMessages(null);
                
                // 发现服务
                gatt.discoverServices();
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.d(TAG, "与设备断开连接: " + gatt.getDevice().getName());
                
                // 清理连接超时
                connectionTimeoutHandler.removeCallbacksAndMessages(null);
                dataTimeoutHandler.removeCallbacksAndMessages(null);
                isConnecting = false;

                if (connectionCallback != null) {
                    connectionCallback.onDisconnected();
                }
            } else {
                Log.d(TAG, "未知连接状态: " + newState);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "服务发现 - status: " + status);
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "服务发现成功");
                
                // 设置Nordic UART服务
                boolean setupResult = setupNordicUartService(gatt);
                
                if (setupResult) {
                    Log.d(TAG, "Nordic UART服务设置成功");
                    
                    if (connectionCallback != null && connectionCallback instanceof ConnectionCallbackWithInfo) {
                        ((ConnectionCallbackWithInfo) connectionCallback).onServicesDiscovered(
                            "Nordic UART服务已就绪");
                    }
                    
                    if (connectionCallback != null) {
                        connectionCallback.onConnected();
                    }
                } else {
                    Log.e(TAG, "Nordic UART服务设置失败");
                    
                    if (connectionCallback != null) {
                        connectionCallback.onConnectionFailed("Nordic UART服务设置失败");
                    }
                }
            } else {
                Log.e(TAG, "服务发现失败，状态码: " + status);
                
                if (connectionCallback != null) {
                    connectionCallback.onConnectionFailed("服务发现失败: " + status);
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            Log.d(TAG, "收到数据 (通知): " + HexStringUtils.bytesToHexString(data));
            
            if (currentDataCallback != null) {
                currentDataCallback.onDataReceived(data);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "特征读取 - status: " + status);
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                byte[] data = characteristic.getValue();
                Log.d(TAG, "读取数据: " + HexStringUtils.bytesToHexString(data));
                
                if (currentDataCallback != null) {
                    currentDataCallback.onDataReceived(data);
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "特征写入 - status: " + status);
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (currentDataCallback != null) {
                    currentDataCallback.onDataSent(true);
                }
            } else {
                Log.e(TAG, "特征写入失败: " + status);
                
                if (currentDataCallback != null) {
                    currentDataCallback.onDataSent(false);
                }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "描述符写入 - status: " + status + ", UUID: " + descriptor.getUuid());
            
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.getUuid().equals(CCC_DESCRIPTOR_UUID)) {
                // CCC 描述符写入成功
                Log.d(TAG, "通知/指示已启用");
            }
        }
    };

    /**
     * 设置Nordic UART服务
     */
    @SuppressLint("MissingPermission")
    private boolean setupNordicUartService(BluetoothGatt gatt) {
        Log.d(TAG, "设置 Nordic UART 服务");
        
        // 查找Nordic UART服务
        BluetoothGattService service = gatt.getService(NORDIC_UART_SERVICE_UUID);
        
        if (service == null) {
            Log.e(TAG, "Nordic UART 服务未找到");
            Log.e(TAG, "期望的服务UUID: " + NORDIC_UART_SERVICE_UUID);
            return false;
        }
        
        Log.d(TAG, "找到 Nordic UART 服务");

        // 查找TX特征（设备→手机）
        txCharacteristic = service.getCharacteristic(TX_CHARACTERISTIC_UUID);
        if (txCharacteristic == null) {
            Log.e(TAG, "TX特征未找到");
            Log.e(TAG, "期望的TX UUID: " + TX_CHARACTERISTIC_UUID);
            return false;
        }
        
        Log.d(TAG, "找到 TX 特征: " + TX_CHARACTERISTIC_UUID);

        // 查找RX特征（手机→设备）
        rxCharacteristic = service.getCharacteristic(RX_CHARACTERISTIC_UUID);
        if (rxCharacteristic == null) {
            Log.e(TAG, "RX特征未找到");
            Log.e(TAG, "期望的RX UUID: " + RX_CHARACTERISTIC_UUID);
            return false;
        }
        
        Log.d(TAG, "找到 RX 特征: " + RX_CHARACTERISTIC_UUID);

        // 启用TX特征的通知
        return enableNotification(gatt, txCharacteristic);
    }

    /**
     * 启用通知
     */
    @SuppressLint("MissingPermission")
    private boolean enableNotification(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        Log.d(TAG, "启用通知");
        
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.e(TAG, "setCharacteristicNotification 失败");
            return false;
        }
        
        // 获取CCC描述符
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID);
        if (descriptor == null) {
            Log.e(TAG, "CCC描述符未找到");
            return false;
        }
        
        Log.d(TAG, "找到 CCC 描述符");

        // 启用通知
        byte[] value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        
        // 检查特征属性
        int properties = characteristic.getProperties();
        if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
            value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            Log.d(TAG, "使用 INDICATION 模式");
        } else if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            Log.d(TAG, "使用 NOTIFICATION 模式");
        } else {
            Log.e(TAG, "特征不支持通知或指示");
            return false;
        }

        descriptor.setValue(value);
        
        boolean result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 使用新API
            result = gatt.writeDescriptor(descriptor);
        } else {
            // Android 12及以下使用旧API
            result = gatt.writeDescriptor(descriptor);
        }
        
        if (!result) {
            Log.e(TAG, "writeDescriptor 失败");
            return false;
        }
        
        Log.d(TAG, "writeDescriptor 成功");
        return true;
    }

    /**
     * 发送数据
     */
    @SuppressLint("MissingPermission")
    public boolean sendData(byte[] data, final DataCallback callback) {
        Log.d(TAG, "发送数据: " + HexStringUtils.bytesToHexString(data));
        
        if (bluetoothGatt == null) {
            Log.e(TAG, "未连接到设备");
            return false;
        }

        if (rxCharacteristic == null) {
            Log.e(TAG, "RX特征未找到");
            return false;
        }

        currentDataCallback = callback;
        
        // 写入数据到RX特征
        rxCharacteristic.setValue(data);
        
        // 设置写入类型
        int writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        if ((rxCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            Log.d(TAG, "使用 WRITE_NO_RESPONSE");
        }
        rxCharacteristic.setWriteType(writeType);
        
        boolean result = bluetoothGatt.writeCharacteristic(rxCharacteristic);
        
        if (result) {
            Log.d(TAG, "数据已提交写入");
        } else {
            Log.e(TAG, "数据写入失败");
        }
        
        return result;
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        Log.d(TAG, "断开连接");
        
        // 清理超时
        scanTimeoutHandler.removeCallbacksAndMessages(null);
        connectionTimeoutHandler.removeCallbacksAndMessages(null);
        dataTimeoutHandler.removeCallbacksAndMessages(null);
        
        isConnecting = false;
        isScanning = false;

        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        
        rxCharacteristic = null;
        txCharacteristic = null;
        connectionCallback = null;
        currentDataCallback = null;
    }
}
