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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 蓝牙锁密码修改工具 —— 基于 4G-BLE-093 固件源码逆向工程重写
 *
 * 协议要点（来自固件）：
 * - BLE Service UUID: 0x000A (VendorV2)
 * - 通信全程 AES-128-ECB 加密
 * - 密码编码：数字值（0-9），不是 ASCII
 * - 校验和：BCC 简单加法
 * - 密钥：基于 MAC 地址 + 时间
 *
 * 密码修改完整流程：
 * 1. 扫描 LOCK_ 设备
 * 2. 连接 + 订阅通知
 * 3. 发送 SET_TIME (0x10) → 切换到 key2
 * 4. 发送 AUTH_PASSWD (0x20, 旧密码) → 验证身份
 * 5. 发送 SET_AUTH_PASSWD (0x21, 新密码) → 修改密码
 * 6. 显示结果
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSIONS = 1;

    private BluetoothConnectionManager bluetoothManager;

    // UI 组件
    private Button btnScan, btnConnect, btnChangePassword, btnReset, btnTestSend, btnViewLog, btnUnlock, btnLock;
    private ListView listViewDevices;
    private EditText editOldPassword, editNewPassword;
    private TextView textStatus, textLog;
    private ScrollView scrollView;

    // 设备列表
    private ArrayAdapter<String> devicesAdapter;
    private List<BluetoothDevice> devicesList = new ArrayList<>();
    private Map<String, BluetoothDevice> devicesMap = new HashMap<>();

    private BluetoothDevice selectedDevice;
    private int selectedDevicePosition = -1;
    private boolean isConnected = false;

    // 日志
    private StringBuilder logBuffer = new StringBuilder();

    // 密码修改流程状态机
    private enum FlowStep {
        IDLE,
        WAITING_SET_TIME,
        WAITING_AUTH,
        WAITING_SET_PASSWD,
        WAITING_UNLOCK,
        WAITING_LOCK
    }
    private FlowStep currentStep = FlowStep.IDLE;
    private String pendingOldPassword;
    private String pendingNewPassword;
    private Boolean pendingLockAction;  // true=unlock, false=lock, null=none
    private Date setTimeDate;

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
        btnUnlock = findViewById(R.id.btnUnlock);
        btnLock = findViewById(R.id.btnLock);
        listViewDevices = findViewById(R.id.listViewDevices);
        editOldPassword = findViewById(R.id.editOldPassword);
        editNewPassword = findViewById(R.id.editNewPassword);
        textStatus = findViewById(R.id.textStatus);
        textLog = findViewById(R.id.textLog);
        scrollView = findViewById(R.id.scrollView);

        devicesAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        listViewDevices.setAdapter(devicesAdapter);
        textLog.setMovementMethod(new ScrollingMovementMethod());

        updateUIState();
        textStatus.setText("准备就绪");
        appendLog("========================================");
        appendLog("🚀 应用启动完成（协议重写版）");
        appendLog("协议：SYD8811 (VendorV2 Service 0x000A)");
        appendLog("加密：AES-128-ECB");
        appendLog("密码编码：数值编码（0-9）");
        appendLog("校验和：BCC 加法");
        appendLog("流程：setTime → auth → setPasswd");
        appendLog("默认密码：000000");
        appendLog("========================================");
    }

    private void initBluetoothManager() {
        bluetoothManager = new BluetoothConnectionManager(this);
        appendLog("✓ 蓝牙管理器初始化完成");
    }

    private void setupListeners() {
        btnScan.setOnClickListener(v -> {
            if (checkPermissions()) startScan();
        });
        btnConnect.setOnClickListener(v -> connectDevice());
        btnChangePassword.setOnClickListener(v -> startPasswordChangeFlow());
        btnTestSend.setOnClickListener(v -> testSendSetTime());
        btnViewLog.setOnClickListener(v -> showLogDialog());
        btnReset.setOnClickListener(v -> resetState());
        btnUnlock.setOnClickListener(v -> sendLockCommand(true));
        btnLock.setOnClickListener(v -> sendLockCommand(false));

        listViewDevices.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < devicesList.size()) {
                selectedDevice = devicesList.get(position);
                selectedDevicePosition = position;
                String name = selectedDevice.getName() != null ? selectedDevice.getName() : "未知";
                appendLog("✓ 选中设备 [" + position + "]: " + name +
                        " (" + selectedDevice.getAddress() + ")");
                Toast.makeText(this, "已选择: " + name, Toast.LENGTH_SHORT).show();
                listViewDevices.setItemChecked(position, true);
                updateUIState();
            }
        });
    }

    // ===================== 权限 =====================

    private boolean checkPermissions() {
        List<String> missing = new ArrayList<>();

        // 位置权限（所有版本都需要 — Vivo/MIUI 强制要求）
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        // Android 12+ 蓝牙权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        if (!missing.isEmpty()) {
            appendLog("⚠️ 需要运行时权限: " + missing.size() + " 项");
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
            return false;
        }

        if (!bluetoothManager.isBluetoothEnabled()) {
            appendLog("⚠️ 蓝牙未开启");
            bluetoothManager.enableBluetooth(this);
            return false;
        }

        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = grantResults.length > 0;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                appendLog("✓ 权限已授予");
                startScan();
            } else {
                appendLog("❌ 权限被拒绝");
                Toast.makeText(this, "需要蓝牙和位置权限", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ===================== 扫描 =====================

    private void startScan() {
        devicesList.clear();
        devicesMap.clear();
        devicesAdapter.clear();
        selectedDevice = null;
        selectedDevicePosition = -1;

        appendLog("========================================");
        appendLog("📡 开始扫描 LOCK_ 设备...");
        textStatus.setText("正在扫描...");
        btnScan.setEnabled(false);

        bluetoothManager.startScan(new BluetoothConnectionManager.ScanCallback() {
            @Override
            public void onDeviceFound(BluetoothDevice device, int rssi) {
                String name = device.getName();
                if (name != null && name.startsWith("LOCK_") &&
                        !devicesMap.containsKey(device.getAddress())) {
                    devicesMap.put(device.getAddress(), device);
                    devicesList.add(device);
                    String displayText = String.format("%s (%ddBm)", name, rssi);
                    devicesAdapter.add(displayText);
                    appendLog("  📱 " + displayText + " [" + device.getAddress() + "]");
                    runOnUiThread(() -> devicesAdapter.notifyDataSetChanged());
                }
            }

            @Override
            public void onScanComplete() {
                appendLog("✓ 扫描完成，找到 " + devicesList.size() + " 个设备");
                runOnUiThread(() -> {
                    textStatus.setText("扫描完成");
                    btnScan.setEnabled(true);
                    if (devicesList.isEmpty()) {
                        Toast.makeText(MainActivity.this, "未找到 LOCK_ 设备", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onScanError(int errorCode, String message) {
                appendLog("❌ 扫描失败 [errorCode=" + errorCode + "]");
                appendLog("   原因: " + message);
                runOnUiThread(() -> textStatus.setText("扫描失败: " + errorCode));
            }
        });
    }

    // ===================== 连接 =====================

    private void connectDevice() {
        if (selectedDevice == null) {
            appendLog("❌ 未选择设备");
            Toast.makeText(this, "请先选择设备", Toast.LENGTH_LONG).show();
            return;
        }

        String name = selectedDevice.getName() != null ? selectedDevice.getName() : "未知";
        String mac = selectedDevice.getAddress();
        appendLog("========================================");
        appendLog("🔗 连接设备: " + name + " (" + mac + ")");
        appendLog("   生成 AES 密钥中...");
        textStatus.setText("正在连接...");
        btnConnect.setEnabled(false);

        bluetoothManager.connect(selectedDevice, new BluetoothConnectionManager.ConnectionCallbackWithInfo() {
            @Override
            public void onServicesDiscovered(String info) {
                appendLog("📋 " + info);
            }

            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    isConnected = true;
                    updateUIState();
                    textStatus.setText("已连接: " + name);

                    appendLog("✅ 连接成功！");
                    appendLog("   Key1: " + HexStringUtils.bytesToHexString(bluetoothManager.getAesKey1()));
                    appendLog("   Key2 将在 SET_TIME 成功后生成");
                    appendLog("💡 点击[测试发送]发送 SET_TIME");
                    appendLog("💡 或直接点[修改密码]");
                    appendLog("========================================");

                    Toast.makeText(MainActivity.this, "连接成功", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    isConnected = false;
                    currentStep = FlowStep.IDLE;
                    updateUIState();
                    textStatus.setText("已断开");
                    appendLog("⚠ 连接断开");
                });
            }

            @Override
            public void onConnectionFailed(String error) {
                runOnUiThread(() -> {
                    textStatus.setText("连接失败");
                    btnConnect.setEnabled(true);
                    appendLog("❌ 连接失败: " + error);
                    Toast.makeText(MainActivity.this, "连接失败: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ===================== 测试发送（SET_TIME） =====================

    private void testSendSetTime() {
        if (!isConnected) {
            Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show();
            return;
        }

        appendLog("========================================");
        appendLog("🧪 发送 SET_TIME (0x10) 命令");

        Date now = new Date();
        byte[] request = BleLockSdk.setTimeRequest(now);
        appendLog("   明文请求: " + HexStringUtils.bytesToHexString(request));

        // SET_TIME 用 key1 加密（因为此时还没完成时间设置）
        byte[] key = bluetoothManager.getAesKey1();
        textStatus.setText("正在发送 SET_TIME...");
        btnTestSend.setEnabled(false);

        bluetoothManager.sendEncryptedData(request, key, new BluetoothConnectionManager.DataCallback() {
            @Override
            public void onDataReceived(byte[] encryptedData) {
                // 解密响应（SET_TIME 的响应用 key1 解密）
                byte[] decrypted = ProtoUtil.decryptResponse(key, encryptedData);
                if (decrypted != null) {
                    BleLockSdk.Response response = BleLockSdk.parseResponse(decrypted);
                    runOnUiThread(() -> {
                        if (response.success && response.isSetTimeSuccess()) {
                            bluetoothManager.onSetTimeSuccess(now);
                            appendLog("✅ SET_TIME 成功！key2 已生成");
                            appendLog("   Key2: " + HexStringUtils.bytesToHexString(bluetoothManager.getAesKey2()));
                            textStatus.setText("SET_TIME 成功");
                        } else {
                            appendLog("❌ SET_TIME 响应异常: " +
                                    (response.success ? "cmd=0x" + String.format("%02X", response.cmd) : response.error));
                            textStatus.setText("SET_TIME 失败");
                        }
                        btnTestSend.setEnabled(true);
                    });
                } else {
                    runOnUiThread(() -> {
                        appendLog("❌ 解密 SET_TIME 响应失败");
                        textStatus.setText("解密失败");
                        btnTestSend.setEnabled(true);
                    });
                }
            }

            @Override
            public void onDataSent(boolean success) {
                if (success) {
                    appendLog("✓ SET_TIME 数据已发送，等待响应...");
                } else {
                    runOnUiThread(() -> {
                        appendLog("❌ SET_TIME 发送失败");
                        textStatus.setText("发送失败");
                        btnTestSend.setEnabled(true);
                    });
                }
            }
        });

        // 超时
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!btnTestSend.isEnabled()) {
                runOnUiThread(() -> {
                    appendLog("⏰ SET_TIME 超时");
                    textStatus.setText("SET_TIME 超时");
                    btnTestSend.setEnabled(true);
                });
            }
        }, 10000);
    }

    // ===================== 密码修改流程（状态机） =====================

    private void startPasswordChangeFlow() {
        if (!isConnected) {
            Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show();
            return;
        }

        String oldPassword = editOldPassword.getText().toString().trim();
        if (oldPassword.isEmpty() || oldPassword.length() != 6 || !oldPassword.matches("\\d+")) {
            Toast.makeText(this, "旧密码必须是 6 位数字", Toast.LENGTH_SHORT).show();
            return;
        }

        String newPassword = editNewPassword.getText().toString().trim();
        if (newPassword.isEmpty() || newPassword.length() != 6 || !newPassword.matches("\\d+")) {
            Toast.makeText(this, "新密码必须是 6 位数字", Toast.LENGTH_SHORT).show();
            return;
        }

        pendingOldPassword = oldPassword;
        pendingNewPassword = newPassword;
        btnChangePassword.setEnabled(false);

        appendLog("========================================");
        appendLog("🔄 开始密码修改流程");
        appendLog("   旧密码: " + oldPassword);
        appendLog("   新密码: " + newPassword);
        appendLog("========================================");

        // 步骤 1：发送 SET_TIME
        currentStep = FlowStep.WAITING_SET_TIME;
        sendSetTimeForFlow();
    }

    /** 步骤 1：发送 SET_TIME (0x10) */
    private void sendSetTimeForFlow() {
        appendLog("📤 步骤 1/3: 发送 SET_TIME (0x10)");
        textStatus.setText("步骤 1/3: 设置时间...");

        setTimeDate = new Date();  // 保存精确时间，后面生成 key2 用
        byte[] request = BleLockSdk.setTimeRequest(setTimeDate);
        byte[] key = bluetoothManager.getAesKey1();  // SET_TIME 用 key1

        appendLog("   明文: " + HexStringUtils.bytesToHexString(request));

        boolean sent = bluetoothManager.sendEncryptedData(request, key, flowDataCallback);
        if (!sent) {
            appendLog("❌ SET_TIME 发送失败");
            resetFlow();
        }
    }

    /** 步骤 2：发送 AUTH_PASSWD (0x20) */
    private void sendAuthForFlow() {
        int oldPassInt = Integer.parseInt(pendingOldPassword);
        appendLog("📤 步骤 2/3: 验证密码 (0x20) 密码=" + pendingOldPassword);
        textStatus.setText("步骤 2/3: 验证密码...");

        byte[] request = BleLockSdk.authPasswdRequest(oldPassInt);
        byte[] key = bluetoothManager.getCurrentKey();  // 已切换到 key2

        appendLog("   明文: " + HexStringUtils.bytesToHexString(request));

        boolean sent = bluetoothManager.sendEncryptedData(request, key, flowDataCallback);
        if (!sent) {
            appendLog("❌ AUTH 发送失败");
            resetFlow();
        }
    }

    /** 步骤 3：发送 SET_AUTH_PASSWD (0x21) */
    private void sendSetPasswdForFlow() {
        int newPassInt = Integer.parseInt(pendingNewPassword);
        appendLog("📤 步骤 3/3: 修改密码 (0x21) 新密码=" + pendingNewPassword);
        textStatus.setText("步骤 3/3: 修改密码...");

        byte[] request = BleLockSdk.setAuthPasswdRequest(newPassInt);
        byte[] key = bluetoothManager.getCurrentKey();  // key2

        appendLog("   明文: " + HexStringUtils.bytesToHexString(request));

        boolean sent = bluetoothManager.sendEncryptedData(request, key, flowDataCallback);
        if (!sent) {
            appendLog("❌ SET_PASSWD 发送失败");
            resetFlow();
        }
    }

    private void sendLockForFlow(boolean isUnlock) {
        String cmdName = isUnlock ? "开锁 (0x30)" : "关锁 (0x31)";
        appendLog("📤 步骤 3/3: " + cmdName);
        textStatus.setText("步骤 3/3: " + (isUnlock ? "开锁" : "关锁") + "...");

        byte[] request = isUnlock ? BleLockSdk.openLockRequest((byte)0) : BleLockSdk.closeLockRequest((byte)0);
        byte[] key = bluetoothManager.getCurrentKey();

        appendLog("   明文: " + HexStringUtils.bytesToHexString(request));

        boolean sent = bluetoothManager.sendEncryptedData(request, key, flowDataCallback);
        if (!sent) {
            appendLog("❌ " + (isUnlock ? "开锁" : "关锁") + "指令发送失败");
            resetFlow();
        }
    }

    /** 统一的数据回调 —— 根据 currentStep 路由到不同处理 */
    private final BluetoothConnectionManager.DataCallback flowDataCallback =
            new BluetoothConnectionManager.DataCallback() {
        @Override
        public void onDataReceived(byte[] encryptedData) {
            // 根据当前步骤选择解密密钥
            byte[] key;
            if (currentStep == FlowStep.WAITING_SET_TIME) {
                key = bluetoothManager.getAesKey1();  // SET_TIME 响应用 key1 解密
            } else {
                key = bluetoothManager.getCurrentKey();  // 后续步骤用 key2
            }

            byte[] decrypted = ProtoUtil.decryptResponse(key, encryptedData);
            if (decrypted == null) {
                runOnUiThread(() -> {
                    appendLog("❌ 解密失败（密钥可能不匹配）");
                    appendLog("   加密数据: " + HexStringUtils.bytesToHexString(encryptedData));
                    appendLog("   使用密钥: " + HexStringUtils.bytesToHexString(key));
                    resetFlow();
                });
                return;
            }

            BleLockSdk.Response response = BleLockSdk.parseResponse(decrypted);
            appendLog("📨 收到响应: cmd=0x" + String.format("%02X", response.cmd & 0xFF) +
                    " payload=" + HexStringUtils.bytesToHexString(response.payload));

            runOnUiThread(() -> handleFlowResponse(response));
        }

        @Override
        public void onDataSent(boolean success) {
            if (success) {
                appendLog("✓ 数据已发送，等待响应...");
            } else {
                runOnUiThread(() -> {
                    appendLog("❌ 数据发送失败");
                    resetFlow();
                });
            }
        }
    };

    /** 根据状态机处理响应 */
    private void handleFlowResponse(BleLockSdk.Response response) {
        switch (currentStep) {
            case WAITING_SET_TIME:
                if (response.success && response.isSetTimeSuccess()) {
                    appendLog("✅ SET_TIME 成功，用发送时的时间生成 key2");
                    bluetoothManager.onSetTimeSuccess(setTimeDate);
                    appendLog("   Key2: " + HexStringUtils.bytesToHexString(bluetoothManager.getAesKey2()));
                    // 进入步骤 2
                    currentStep = FlowStep.WAITING_AUTH;
                    // 短暂延迟确保固件切换密钥
                    new Handler(Looper.getMainLooper()).postDelayed(this::sendAuthForFlow, 200);
                } else {
                    appendLog("❌ SET_TIME 失败: " + response.error);
                    resetFlow();
                }
                break;

            case WAITING_AUTH:
                if (response.success && response.cmd == BleLockSdk.CMD_AUTH_PASSWD) {
                    Byte authResult = response.getAuthPasswdResult();
                    if (authResult != null && authResult == BleLockSdk.RESULT_SUCCESS) {
                        appendLog("✅ 密码验证成功");
                        if (currentStep == FlowStep.WAITING_AUTH && pendingNewPassword != null) {
                            currentStep = FlowStep.WAITING_SET_PASSWD;
                            new Handler(Looper.getMainLooper()).postDelayed(this::sendSetPasswdForFlow, 200);
                        } else if (pendingLockAction != null) {
                            boolean isUnlock = pendingLockAction;
                            pendingLockAction = null;
                            FlowStep nextStep = isUnlock ? FlowStep.WAITING_UNLOCK : FlowStep.WAITING_LOCK;
                            currentStep = nextStep;
                            new Handler(Looper.getMainLooper()).postDelayed(() -> sendLockForFlow(isUnlock), 200);
                        }
                    } else {
                        appendLog("❌ 密码验证失败（旧密码不正确？）");
                        appendLog("💡 确认默认密码是否为 000000");
                        resetFlow();
                    }
                } else {
                    appendLog("❌ AUTH 响应异常: " + response.error);
                    resetFlow();
                }
                break;

            case WAITING_SET_PASSWD:
                if (response.success && response.cmd == BleLockSdk.CMD_SET_AUTH_PASSWD) {
                    Byte setResult = response.getSetAuthPasswdResult();
                    if (setResult != null && setResult == BleLockSdk.RESULT_SUCCESS) {
                        appendLog("========================================");
                        appendLog("🎉🎉🎉 密码修改成功！");
                        appendLog("   新密码: " + pendingNewPassword);
                        appendLog("💡 下次连接使用新密码");
                        appendLog("========================================");
                        textStatus.setText("密码修改成功！新密码: " + pendingNewPassword);
                        Toast.makeText(MainActivity.this,
                                "密码修改成功！新密码: " + pendingNewPassword,
                                Toast.LENGTH_LONG).show();
                    } else {
                        appendLog("❌ 密码修改失败");
                        appendLog("💡 固件可能未启用密码修改功能");
                        textStatus.setText("密码修改失败");
                    }
                } else {
                    appendLog("❌ SET_PASSWD 响应异常: " + response.error);
                }
                resetFlow();
                break;

            case WAITING_UNLOCK:
            case WAITING_LOCK:
                boolean isUnlockResp = (currentStep == FlowStep.WAITING_UNLOCK);
                String lockCmdName = isUnlockResp ? "开锁" : "关锁";
                if (response.success) {
                    appendLog("📨 收到" + lockCmdName + "响应: cmd=0x" + String.format("%02X", response.cmd));
                    if (response.payload != null && response.payload.length >= 4) {
                        int battery = response.payload[1] & 0xFF;
                        int lockState = response.payload[2] & 0xFF;
                        String stateStr = (lockState == 0) ? "关锁(已锁)" : "开锁(已开)";
                        appendLog("========================================");
                        appendLog("📊 锁状态反馈:");
                        appendLog("   🔋 电量: " + battery + "%");
                        appendLog("   🔒 锁状态: " + stateStr);
                        if (isUnlockResp && lockState == 1) {
                            appendLog("   ✅ 开锁成功");
                        } else if (!isUnlockResp && lockState == 0) {
                            appendLog("   ✅ 关锁成功");
                        } else {
                            appendLog("   ⚠️ " + lockCmdName + "指令已发，等待状态变化");
                        }
                        appendLog("========================================");
                    } else {
                        appendLog("   响应: " + HexStringUtils.bytesToHexString(response.payload));
                    }
                } else {
                    appendLog("❌ " + lockCmdName + "响应异常: " + response.error);
                }
                resetFlow();
                break;
        }
    }

    private void resetFlow() {
        currentStep = FlowStep.IDLE;
        pendingNewPassword = null;
        runOnUiThread(() -> btnChangePassword.setEnabled(isConnected));
    }

    // ===================== 日志对话框 =====================

    private void showLogDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("完整日志");
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_log, null);
        builder.setView(dialogView);

        TextView logTextView = dialogView.findViewById(R.id.logTextView);
        Button btnCopy = dialogView.findViewById(R.id.btnCopy);
        Button btnClear = dialogView.findViewById(R.id.btnClear);
        Button btnClose = dialogView.findViewById(R.id.btnClose);

        logTextView.setText(logBuffer.toString());
        logTextView.setMovementMethod(new ScrollingMovementMethod());

        AlertDialog dialog = builder.create();
        dialog.show();

        btnCopy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("日志", logBuffer.toString()));
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
        });
        btnClear.setOnClickListener(v -> {
            logBuffer.setLength(0);
            textLog.setText("");
            logTextView.setText("");
        });
        btnClose.setOnClickListener(v -> dialog.dismiss());
    }

    // ===================== 开关锁测试 =====================

    private void sendLockCommand(boolean isUnlock) {
        if (!isConnected) {
            appendLog("❌ 未连接设备");
            Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show();
            return;
        }

        String oldPassword = editOldPassword.getText().toString().trim();
        if (oldPassword.isEmpty() || oldPassword.length() != 6 || !oldPassword.matches("\\d+")) {
            Toast.makeText(this, "请输入正确的密码(6位)", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isUnlock) {
            // 关锁需要确认锁杆已插入
            new AlertDialog.Builder(this)
                .setTitle("关锁确认")
                .setMessage("请确认锁杆已插入到位\n（STATE_KEY和lock_hall都检测到）\n\n确认后点击"关锁"")
                .setPositiveButton("关锁", (d, w) -> doSendLockCommand(false, oldPassword))
                .setNegativeButton("取消", null)
                .show();
            return;
        }

        doSendLockCommand(true, oldPassword);
    }

    private void doSendLockCommand(boolean isUnlock, String oldPassword) {
        String cmdName = isUnlock ? "开锁" : "关锁";
        appendLog("========================================");
        appendLog("🔧 开始" + cmdName + "测试");
        appendLog("   密码: " + oldPassword);
        if (!isUnlock) {
            appendLog("   💡 发送关锁指令后请插入锁杆");
        }
        appendLog("========================================");

        pendingOldPassword = oldPassword;
        pendingNewPassword = null;
        pendingLockAction = isUnlock;

        // 如果已认证，直接发送开关锁指令
        byte[] key2 = bluetoothManager.getAesKey2();
        if (key2 != null) {
            appendLog("✓ 已认证，直接发送" + cmdName + "指令");
            currentStep = isUnlock ? FlowStep.WAITING_UNLOCK : FlowStep.WAITING_LOCK;
            sendLockForFlow(isUnlock);
            return;
        }

        // 未认证，走完整流程（复用密码修改的SET_TIME和AUTH）
        currentStep = FlowStep.WAITING_SET_TIME;
        sendSetTimeForFlow();
    }

    // ===================== 重置 =====================

    private void resetState() {
        isConnected = false;
        selectedDevice = null;
        selectedDevicePosition = -1;
        currentStep = FlowStep.IDLE;
        pendingNewPassword = null;
        editNewPassword.setText("");
        devicesList.clear();
        devicesMap.clear();
        devicesAdapter.clear();
        bluetoothManager.disconnect();

        updateUIState();
        textStatus.setText("已重置");
        appendLog("========================================");
        appendLog("🔄 状态已重置");
        appendLog("========================================");
    }

    // ===================== UI 状态 =====================

    private void updateUIState() {
        btnScan.setEnabled(!isConnected);
        btnConnect.setEnabled(!isConnected && selectedDevice != null);
        btnChangePassword.setEnabled(isConnected);
        btnTestSend.setEnabled(isConnected);
        btnReset.setEnabled(true);
        btnViewLog.setEnabled(true);
        editNewPassword.setEnabled(isConnected);
    }

    // ===================== 日志输出 =====================

    private void appendLog(final String message) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> appendLog(message));
            return;
        }

        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        String logMessage = "[" + timestamp + "] " + message;

        logBuffer.append(logMessage).append("\n");
        if (logBuffer.length() > 50000) {
            logBuffer.delete(0, 5000);
        }

        String[] lines = logBuffer.toString().split("\n");
        StringBuilder recentLogs = new StringBuilder();
        int start = Math.max(0, lines.length - 100);
        for (int i = start; i < lines.length; i++) {
            recentLogs.append(lines[i]).append("\n");
        }
        textLog.setText(recentLogs.toString());
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
