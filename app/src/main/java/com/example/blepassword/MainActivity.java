package com.example.blepassword;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主界面：蓝牙锁密码修改工具（最终修复版 - 基于SYD8811_FINAL_REAL_FIX.bin固件）
 * 
 * 固件功能确认：
 * - 支持0x21密码修改命令（REAL_0x21_FUNCTION）
 * - 密码编码：ASCII字符编码（例如："123456" -> 0x31 0x32 0x33 0x34 0x35 0x36）
 * - 校验和算法：累加+异或双重校验 checksum = XOR(all bytes) + SUM(all bytes)
 * - 两步验证：先0x20验证旧密码 → 再0x21修改新密码
 * - 默认密码：000000
 * 
 * 工作流程：
 * 1. 扫描设备
 * 2. 连接设备
 * 3. 验证密码（0x20命令）
 * 4. 修改密码（0x21命令）
 * 5. 等待设备响应
 * 6. 显示修改成功或失败
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1;
    private static final int REQUEST_LOCATION_PERMISSION = 2;
    
    private BluetoothConnectionManager bluetoothManager;

    private Button btnScan;
    private Button btnConnect;
    private Button btnChangePassword;
    private Button btnReset;
    private Button btnTestSend;
    private Button btnViewLog;
    private ListView listViewDevices;
    private EditText editOldPassword;
    private EditText editNewPassword;
    private TextView textStatus;
    private TextView textLog;
    private ScrollView scrollView;
    
    private ArrayAdapter<String> devicesAdapter;
    private List<BluetoothDevice> devicesList = new ArrayList<>();
    private Map<String, BluetoothDevice> devicesMap = new HashMap<>();
    
    private BluetoothDevice selectedDevice;
    private int selectedDevicePosition = -1;
    private boolean isConnected = false;

    // 日志缓存
    private StringBuilder logBuffer = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        initBluetoothManager();
        setupListeners();
    }
    
    private void initViews() {
        btnScan = findViewById(R.id.btnScan);
        btnConnect = findViewById(R.id.btnConnect);
        btnChangePassword = findViewById(R.id.btnChangePassword);
        btnReset = findViewById(R.id.btnReset);
        btnTestSend = findViewById(R.id.btnTestSend);
        btnViewLog = findViewById(R.id.btnViewLog);
        listViewDevices = findViewById(R.id.listViewDevices);
        editOldPassword = findViewById(R.id.editOldPassword);
        editNewPassword = findViewById(R.id.editNewPassword);
        textStatus = findViewById(R.id.textStatus);
        textLog = findViewById(R.id.textLog);
        scrollView = findViewById(R.id.scrollView);

        devicesAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        listViewDevices.setAdapter(devicesAdapter);

        // 使日志可滚动
        textLog.setMovementMethod(new ScrollingMovementMethod());

        updateUIState();
        textStatus.setText("准备就绪");
        appendLog("========================================");
        appendLog("🚀 应用启动完成");
        appendLog("版本：v11 最终修复版");
        appendLog("协议：SYD8811");
        appendLog("密码编码：ASCII编码（固件确认）");
        appendLog("校验和：累加+异或双重校验（固件确认）");
        appendLog("默认密码：000000");
        appendLog("========================================");
    }
    
    private void initBluetoothManager() {
        bluetoothManager = new BluetoothConnectionManager(this);
        appendLog("✓ 蓝牙管理器初始化完成");
    }
    
    private void setupListeners() {
        btnScan.setOnClickListener(v -> {
            if (checkPermissions()) {
                startScan();
            }
        });

        btnConnect.setOnClickListener(v -> connectDevice());

        btnChangePassword.setOnClickListener(v -> changePassword());

        btnTestSend.setOnClickListener(v -> testSend());

        btnViewLog.setOnClickListener(v -> showLogDialog());

        btnReset.setOnClickListener(v -> resetState());

        listViewDevices.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < devicesList.size()) {
                selectedDevice = devicesList.get(position);
                selectedDevicePosition = position;
                String name = selectedDevice.getName() != null ? selectedDevice.getName() : "未知设备";
                String address = selectedDevice.getAddress();

                appendLog("✓ 已选择设备 [" + position + "]: " + name);
                appendLog("  MAC地址: " + address);
                Toast.makeText(MainActivity.this, "已选择: " + name, Toast.LENGTH_SHORT).show();

                // 高亮选中的项
                listViewDevices.setItemChecked(position, true);
            } else {
                appendLog("错误：无效的设备索引 " + position);
            }
        });
    }

    /**
     * 显示日志对话框
     */
    private void showLogDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("完整日志");
        
        // 创建自定义视图
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_log, null);
        builder.setView(dialogView);
        
        TextView logTextView = dialogView.findViewById(R.id.logTextView);
        Button btnCopy = dialogView.findViewById(R.id.btnCopy);
        Button btnClear = dialogView.findViewById(R.id.btnClear);
        Button btnClose = dialogView.findViewById(R.id.btnClose);
        
        // 设置日志内容
        logTextView.setText(logBuffer.toString());
        logTextView.setMovementMethod(new ScrollingMovementMethod());
        
        // 创建对话框
        AlertDialog dialog = builder.create();
        dialog.show();
        
        // 复制按钮
        btnCopy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("日志", logBuffer.toString());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(MainActivity.this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
        });
        
        // 清空按钮
        btnClear.setOnClickListener(v -> {
            logBuffer.setLength(0);
            textLog.setText("");
            logTextView.setText("");
            Toast.makeText(MainActivity.this, "日志已清空", Toast.LENGTH_SHORT).show();
        });
        
        // 关闭按钮
        btnClose.setOnClickListener(v -> dialog.dismiss());
    }

    private boolean checkPermissions() {
        // Android 12+ 需要 BLUETOOTH_SCAN 和 BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                }, REQUEST_BLUETOOTH_PERMISSIONS);
                return false;
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
                return false;
            }
        }
        
        // 检查蓝牙是否开启
        if (!bluetoothManager.isBluetoothEnabled()) {
            appendLog("⚠️  蓝牙未开启");
            bluetoothManager.enableBluetooth(this);
            return false;
        }
        
        return true;
    }

    private void startScan() {
        devicesList.clear();
        devicesMap.clear();
        devicesAdapter.clear();
        selectedDevice = null;
        selectedDevicePosition = -1;

        appendLog("========================================");
        appendLog("📡 开始扫描设备（仅显示 LOCK_ 开头的设备）...");
        textStatus.setText("正在扫描设备...");
        btnScan.setEnabled(false);

        bluetoothManager.startScan(new BluetoothConnectionManager.ScanCallback() {
            @Override
            public void onDeviceFound(BluetoothDevice device, int rssi) {
                String name = device.getName();
                
                // 只显示 LOCK_ 开头的设备
                if (name != null && name.startsWith("LOCK_")) {
                    if (!devicesMap.containsKey(device.getAddress())) {
                        devicesMap.put(device.getAddress(), device);
                        devicesList.add(device);
                        String displayText = String.format("%s (%ddBm)", name, rssi);
                        devicesAdapter.add(displayText);
                        
                        appendLog("  📱 找到设备: " + displayText);
                        runOnUiThread(() -> devicesAdapter.notifyDataSetChanged());
                    }
                }
            }

            @Override
            public void onScanComplete() {
                appendLog("✓ 扫描完成，找到 " + devicesList.size() + " 个设备");
                textStatus.setText("扫描完成");
                btnScan.setEnabled(true);
                
                if (devicesList.isEmpty()) {
                    appendLog("⚠️  未找到 LOCK_ 开头的设备");
                    Toast.makeText(MainActivity.this, "未找到 LOCK_ 开头的设备", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void connectDevice() {
        if (selectedDevice == null) {
            appendLog("❌ 错误：未选择设备");
            appendLog("💡 请先点击列表中的设备进行选择");
            Toast.makeText(this, "请先点击列表中的设备进行选择", Toast.LENGTH_LONG).show();
            return;
        }

        String name = selectedDevice.getName() != null ? selectedDevice.getName() : "未知设备";
        appendLog("========================================");
        appendLog("🔗 正在连接设备: " + name);
        appendLog("   位置: [" + selectedDevicePosition + "]");
        appendLog("   MAC: " + selectedDevice.getAddress());
        textStatus.setText("正在连接设备...");
        btnConnect.setEnabled(false);

        bluetoothManager.connect(selectedDevice, new BluetoothConnectionManager.ConnectionCallbackWithInfo() {
            @Override
            public void onServicesDiscovered(String info) {
                appendLog("📋 服务发现信息：");
                appendLog(info);
                
                // 检查是否包含Nordic UART服务
                if (info.contains("6e400007")) {
                    appendLog("✓ 找到 Nordic UART 服务");
                } else {
                    appendLog("❌ 未找到 Nordic UART 服务");
                }
            }

            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    isConnected = true;
                    updateUIState();
                    textStatus.setText("已连接: " + name);
                    appendLog("✅ 连接成功！");
                    appendLog("💡 现在可以测试发送数据或修改密码");
                    appendLog("========================================");
                    Toast.makeText(MainActivity.this, "连接成功", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    isConnected = false;
                    updateUIState();
                    textStatus.setText("已断开连接");
                    appendLog("⚠ 连接已断开");
                });
            }

            @Override
            public void onConnectionFailed(String error) {
                runOnUiThread(() -> {
                    textStatus.setText("连接失败: " + error);
                    btnConnect.setEnabled(true);
                    appendLog("❌ 连接失败: " + error);
                    appendLog("========================================");
                    Toast.makeText(MainActivity.this, "连接失败: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /**
     * 测试发送功能 - 发送简单的测试数据
     */
    private void testSend() {
        if (!isConnected) {
            appendLog("❌ 错误：未连接设备");
            Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show();
            return;
        }

        appendLog("========================================");
        appendLog("🧪 开始测试发送");
        
        // 发送简单的测试数据：55 01 40 00 00 00 00 00 00 CS (状态查询)
        byte[] testData = BleLockSdk.getStatusRequest((byte)0x00);
        
        appendLog("发送测试数据: " + HexStringUtils.bytesToHexString(testData));
        appendLog("   这是状态查询命令，应该会收到响应");
        textStatus.setText("正在测试发送...");
        btnTestSend.setEnabled(false);

        new Thread(() -> {
            try {
                boolean sendResult = bluetoothManager.sendData(testData, new BluetoothConnectionManager.DataCallback() {
                    @Override
                    public void onDataReceived(byte[] data) {
                        if (data != null && data.length > 0) {
                            appendLog("✅ 收到测试响应: " + HexStringUtils.bytesToHexString(data));
                            appendLog("   数据长度: " + data.length + " 字节");
                            
                            BleLockSdk.Response response = BleLockSdk.parseResponse(data);
                            
                            runOnUiThread(() -> {
                                if (response.success) {
                                    appendLog("✓ 响应解析成功");
                                    appendLog("   命令: 0x" + String.format("%02X", response.cmd));
                                    appendLog("   状态: 0x" + String.format("%02X", response.status));
                                    textStatus.setText("测试成功");
                                    Toast.makeText(MainActivity.this, "测试成功！", Toast.LENGTH_SHORT).show();
                                } else {
                                    appendLog("❌ 响应解析失败: " + response.error);
                                    textStatus.setText("测试失败");
                                }
                                btnTestSend.setEnabled(true);
                            });
                        }
                    }

                    @Override
                    public void onDataSent(boolean success) {
                        if (!success) {
                            runOnUiThread(() -> {
                                textStatus.setText("发送失败");
                                appendLog("❌ 测试数据发送失败");
                                appendLog("========================================");
                                btnTestSend.setEnabled(true);
                                Toast.makeText(MainActivity.this, "发送失败", Toast.LENGTH_SHORT).show();
                            });
                        } else {
                            appendLog("✓ 测试数据发送成功，等待响应...");
                        }
                    }
                });

                if (!sendResult) {
                    runOnUiThread(() -> {
                        textStatus.setText("发送失败");
                        appendLog("❌ 发送测试数据失败");
                        appendLog("========================================");
                        btnTestSend.setEnabled(true);
                        Toast.makeText(MainActivity.this, "发送失败", Toast.LENGTH_SHORT).show();
                    });
                }

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (!btnTestSend.isEnabled()) {
                        runOnUiThread(() -> {
                            textStatus.setText("测试超时");
                            appendLog("⏰ 测试超时（10秒未收到响应）");
                            appendLog("💡 可能原因：");
                            appendLog("   1. 设备不支持此命令");
                            appendLog("   2. 通知未启用");
                            appendLog("   3. TX/RX UUID配置错误");
                            appendLog("========================================");
                            btnTestSend.setEnabled(true);
                            Toast.makeText(MainActivity.this, "测试超时", Toast.LENGTH_SHORT).show();
                        });
                    }
                }, 10000);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    textStatus.setText("异常: " + e.getMessage());
                    appendLog("❌ 异常: " + e.getMessage());
                    e.printStackTrace();
                    appendLog("========================================");
                    btnTestSend.setEnabled(true);
                });
            }
        }).start();
    }

    private void changePassword() {
        if (!isConnected) {
            appendLog("❌ 错误：未连接设备");
            Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show();
            return;
        }

        String oldPassword = editOldPassword.getText().toString().trim();
        String newPassword = editNewPassword.getText().toString().trim();

        if (oldPassword.isEmpty()) {
            appendLog("❌ 错误：旧密码为空");
            Toast.makeText(this, "请输入旧密码", Toast.LENGTH_SHORT).show();
            return;
        }

        if (oldPassword.length() != 6 || !oldPassword.matches("\\d+")) {
            appendLog("❌ 错误：旧密码必须是6位数字");
            Toast.makeText(this, "旧密码必须是 6 位数字", Toast.LENGTH_SHORT).show();
            return;
        }

        if (newPassword.isEmpty()) {
            appendLog("❌ 错误：新密码为空");
            Toast.makeText(this, "请输入新密码", Toast.LENGTH_SHORT).show();
            return;
        }

        if (newPassword.length() != 6 || !newPassword.matches("\\d+")) {
            appendLog("❌ 错误：新密码必须是6位数字");
            Toast.makeText(this, "密码必须是 6 位数字", Toast.LENGTH_SHORT).show();
            return;
        }

        appendLog("========================================");
        appendLog("🔄 开始修改密码");
        appendLog("   旧密码: " + oldPassword);
        appendLog("   新密码: " + newPassword);
        appendLog("   协议: SYD8811");
        appendLog("   编码: ASCII编码（例如：'1' -> 0x31）");
        appendLog("   校验和: 累加+异或双重校验");
        appendLog("   命令流程: 0x20验证 → 0x21修改");
        appendLog("💡 根据固件分析，必须先验证旧密码");

        textStatus.setText("正在验证密码...");
        btnChangePassword.setEnabled(false);

        // 步骤1：先验证初始密码（0x20命令）
        byte[] authData = BleLockSdk.authPasswdRequest(oldPassword);
        appendLog("📤 步骤1/2: 验证密码 " + oldPassword);
        appendLog("   发送: " + HexStringUtils.bytesToHexString(authData));

        new Thread(() -> {
            try {
                // 发送验证请求
                boolean authResult = bluetoothManager.sendData(authData, new BluetoothConnectionManager.DataCallback() {
                    @Override
                    public void onDataReceived(byte[] data) {
                        if (data != null && data.length > 0) {
                            appendLog("📨 收到验证响应: " + HexStringUtils.bytesToHexString(data));
                            appendLog("   数据长度: " + data.length + " 字节");

                            BleLockSdk.Response authResponse = BleLockSdk.parseResponse(data);

                            if (authResponse.success && authResponse.cmd == BleLockSdk.CMD_AUTH_PASSWD) {
                                if (authResponse.status == BleLockSdk.RESULT_SUCCESS) {
                                    appendLog("✅ 密码验证成功");
                                    appendLog("📤 步骤2/2: 修改密码为 " + newPassword);

                                    // 步骤2：修改密码（0x21命令）
                                    byte[] changeData = BleLockSdk.setAuthPasswdRequest(newPassword);
                                    appendLog("发送修改数据: " + HexStringUtils.bytesToHexString(changeData));
                                    appendLog("   帧头: 0x" + String.format("%02X", changeData[0]));
                                    appendLog("   类型: 0x" + String.format("%02X", changeData[1]));
                                    appendLog("   命令: 0x" + String.format("%02X", changeData[2]));
                                    appendLog("   新密码(ASCII): " + HexStringUtils.bytesToHexString(changeData, 3, 6));
                                    appendLog("   示例: '1' -> 0x31, '2' -> 0x32");
                                    appendLog("   校验和: 0x" + String.format("%02X", changeData[9]));
                                    appendLog("   校验算法: 累加+异或双重校验");

                                    // 发送修改请求
                                    boolean changeResult = bluetoothManager.sendData(changeData, new BluetoothConnectionManager.DataCallback() {
                                        @Override
                                        public void onDataReceived(byte[] data) {
                                            if (data != null && data.length > 0) {
                                                appendLog("📨 收到修改响应: " + HexStringUtils.bytesToHexString(data));
                                                appendLog("   数据长度: " + data.length + " 字节");

                                                BleLockSdk.Response changeResponse = BleLockSdk.parseResponse(data);

                                                runOnUiThread(() -> {
                                                    if (changeResponse.success && changeResponse.cmd == BleLockSdk.CMD_SET_AUTH_PASSWD) {
                                                        Byte result = changeResponse.getSetAuthPasswdResult();
                                                        if (result != null && result == BleLockSdk.RESULT_SUCCESS) {
                                                            textStatus.setText("密码修改成功");
                                                            appendLog("✅ 密码修改成功！");
                                                            appendLog("   新密码已生效: " + newPassword);
                                                            appendLog("   状态码: 0x" + String.format("%02X", changeResponse.status));
                                                            appendLog("💡 下次连接需要使用新密码");
                                                            appendLog("========================================");
                                                            Toast.makeText(MainActivity.this, "密码修改成功！新密码: " + newPassword, Toast.LENGTH_LONG).show();
                                                        } else {
                                                            textStatus.setText("密码修改失败");
                                                            appendLog("❌ 密码修改失败");
                                                            appendLog("   状态码: 0x" + String.format("%02X", changeResponse.status));
                                                            appendLog("💡 可能原因：");
                                                            appendLog("   1. 初始密码不是 000000");
                                                            appendLog("   2. 设备固件不支持此操作");
                                                            appendLog("   3. 校验和计算错误");
                                                            appendLog("   4. 密码编码方式不匹配");
                                                            appendLog("========================================");
                                                            Toast.makeText(MainActivity.this, "密码修改失败", Toast.LENGTH_SHORT).show();
                                                        }
                                                    } else {
                                                        textStatus.setText("响应错误");
                                                        appendLog("❌ 响应解析错误: " + (changeResponse.success ? "命令不匹配" : changeResponse.error));
                                                        appendLog("========================================");
                                                        Toast.makeText(MainActivity.this, "响应错误", Toast.LENGTH_SHORT).show();
                                                    }
                                                    btnChangePassword.setEnabled(true);
                                                });
                                            }
                                        }

                                        @Override
                                        public void onDataSent(boolean success) {
                                            if (!success) {
                                                runOnUiThread(() -> {
                                                    textStatus.setText("发送失败");
                                                    appendLog("❌ 修改数据发送失败");
                                                    appendLog("========================================");
                                                    btnChangePassword.setEnabled(true);
                                                });
                                            } else {
                                                appendLog("✓ 修改数据发送成功，等待响应...");
                                            }
                                        }
                                    });

                                    if (!changeResult) {
                                        runOnUiThread(() -> {
                                            textStatus.setText("发送失败");
                                            appendLog("❌ 发送修改数据失败");
                                            appendLog("========================================");
                                            btnChangePassword.setEnabled(true);
                                        });
                                    }
                                } else {
                                    runOnUiThread(() -> {
                                        textStatus.setText("密码验证失败");
                                        appendLog("❌ 密码验证失败");
                                        appendLog("   状态码: 0x" + String.format("%02X", authResponse.status));
                                        appendLog("💡 可能原因：初始密码不是 000000");
                                        appendLog("========================================");
                                        btnChangePassword.setEnabled(true);
                                        Toast.makeText(MainActivity.this, "密码验证失败", Toast.LENGTH_SHORT).show();
                                    });
                                }
                            } else {
                                runOnUiThread(() -> {
                                    textStatus.setText("验证响应错误");
                                    appendLog("❌ 验证响应解析错误: " + (authResponse.success ? "命令不匹配" : authResponse.error));
                                    appendLog("========================================");
                                    btnChangePassword.setEnabled(true);
                                    Toast.makeText(MainActivity.this, "验证响应错误", Toast.LENGTH_SHORT).show();
                                });
                            }
                        }
                    }

                    @Override
                    public void onDataSent(boolean success) {
                        if (!success) {
                            runOnUiThread(() -> {
                                textStatus.setText("发送失败");
                                appendLog("❌ 验证数据发送失败");
                                appendLog("========================================");
                                btnChangePassword.setEnabled(true);
                                Toast.makeText(MainActivity.this, "发送失败", Toast.LENGTH_SHORT).show();
                            });
                        } else {
                            appendLog("✓ 验证数据发送成功，等待响应...");
                        }
                    }
                });

                if (!authResult) {
                    runOnUiThread(() -> {
                        textStatus.setText("发送失败");
                        appendLog("❌ 发送验证数据失败");
                        appendLog("========================================");
                        btnChangePassword.setEnabled(true);
                        Toast.makeText(MainActivity.this, "发送失败", Toast.LENGTH_SHORT).show();
                    });
                }

                // 超时处理（总超时时间60秒：验证30秒 + 修改30秒）
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (!btnChangePassword.isEnabled()) {
                        runOnUiThread(() -> {
                            textStatus.setText("修改超时");
                            appendLog("⏰ 修改超时（60秒未完成）");
                            appendLog("💡 可能原因：");
                            appendLog("   1. 设备不支持此协议");
                            appendLog("   2. 设备未正确启用通知");
                            appendLog("   3. 固件未正确响应");
                            appendLog("   4. 初始密码不是 000000");
                            appendLog("   5. Flash写入时间较长");
                            appendLog("   6. 密码编码方式不匹配（必须是ASCII）");
                            appendLog("   7. 校验和计算错误（必须是累加+异或）");
                            appendLog("========================================");
                            appendLog("📊 调试信息：");
                            appendLog("   设备名称: " + selectedDevice.getName());
                            appendLog("   设备地址: " + selectedDevice.getAddress());
                            appendLog("   当前固件: SYD8811_FINAL_REAL_FIX.bin");
                            appendLog("   密码编码: ASCII编码（固件确认）");
                            appendLog("   校验和: 累加+异或双重校验（固件确认）");
                            appendLog("========================================");
                            btnChangePassword.setEnabled(true);
                            Toast.makeText(MainActivity.this, "操作超时（60秒）", Toast.LENGTH_LONG).show();
                        });
                    }
                }, 60000);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    textStatus.setText("异常: " + e.getMessage());
                    appendLog("❌ 异常: " + e.getMessage());
                    e.printStackTrace();
                    appendLog("========================================");
                    btnChangePassword.setEnabled(true);
                });
            }
        }).start();
    }

    private void resetState() {
        isConnected = false;
        selectedDevice = null;
        selectedDevicePosition = -1;
        editNewPassword.setText("");
        devicesList.clear();
        devicesMap.clear();
        devicesAdapter.clear();
        
        updateUIState();
        textStatus.setText("状态已重置");
        appendLog("========================================");
        appendLog("🔄 状态已重置");
        appendLog("========================================");
        Toast.makeText(this, "状态已重置", Toast.LENGTH_SHORT).show();
    }

    private void updateUIState() {
        btnScan.setEnabled(!isConnected);
        btnConnect.setEnabled(!isConnected && selectedDevice != null);
        btnChangePassword.setEnabled(isConnected);
        btnTestSend.setEnabled(isConnected);
        btnReset.setEnabled(true);
        btnViewLog.setEnabled(true);
        
        editNewPassword.setEnabled(isConnected);
    }

    private void appendLog(String message) {
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        String logMessage = "[" + timestamp + "] " + message;
        
        logBuffer.append(logMessage).append("\n");
        
        // 限制日志缓存大小（保留最近1000行）
        if (logBuffer.length() > 50000) {
            // 移除前5000个字符
            logBuffer.delete(0, 5000);
        }
        
        // 更新界面显示（只显示最近100行）
        String[] lines = logBuffer.toString().split("\n");
        StringBuilder recentLogs = new StringBuilder();
        int start = Math.max(0, lines.length - 100);
        for (int i = start; i < lines.length; i++) {
            recentLogs.append(lines[i]).append("\n");
        }
        textLog.setText(recentLogs.toString());
        
        // 自动滚动到底部
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                appendLog("✓ 位置权限已授予");
                startScan();
            } else {
                appendLog("❌ 位置权限被拒绝");
                Toast.makeText(this, "需要位置权限才能扫描蓝牙设备", Toast.LENGTH_LONG).show();
            }
        }
    }
}