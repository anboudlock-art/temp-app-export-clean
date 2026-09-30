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
 * 蓝牙锁 连接密码修改工具
 *
 * 协议：《智能锁4G+蓝牙通讯协议 v2.0》，适用固件 v1.7 及以上（0x21 改密从 v1.7 起支持）。
 *
 * 流程：
 *   连接 → 开通知 → 0x10 对时(key1) → 0x20 旧密码(key2) → 0x21 新密码(key2)
 *
 * 锁的规则：
 *   - 0x10 之后 10 秒内必须发 0x20，否则锁休眠断开
 *   - 同一次连接里 0x10 只处理一次；鉴权通过后 60 秒无操作断开
 *   - 出厂密码 000000
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1;
    private static final int STEP_TIMEOUT_MS = 5000;

    private BluetoothConnectionManager bt;

    private Button btnScan, btnConnect, btnChangePassword, btnReset, btnTestSend, btnViewLog;
    private ListView listViewDevices;
    private EditText editOldPassword, editNewPassword;
    private TextView textStatus, textLog;
    private ScrollView scrollView;

    private ArrayAdapter<String> devicesAdapter;
    private final List<BluetoothDevice> devicesList = new ArrayList<>();
    private final Map<String, BluetoothDevice> devicesMap = new HashMap<>();
    private BluetoothDevice selectedDevice;
    private boolean isConnected = false;

    private final StringBuilder logBuffer = new StringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---------- 会话状态 ----------
    private enum Step { IDLE, WAIT_TIME, WAIT_AUTH, WAIT_SET }
    private Step step = Step.IDLE;
    private byte[] key1, key2;
    private boolean timeSynced = false;   // 本次连接已发过 0x10 且收到回复
    private boolean authed = false;       // 本次连接 0x20 已通过
    private int cmdId = 0;
    private String pendingOld, pendingNew;
    private boolean onlyVerify;           // true = 只验证密码，不改
    private final Runnable stepTimeout = this::onStepTimeout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        bt = new BluetoothConnectionManager(this);
        bt.setDataListener(dataListener);
        setupListeners();
        log("应用启动。协议 v2.0：AES-128 加密，先对时再验密。出厂密码 000000");
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
        textLog.setMovementMethod(new ScrollingMovementMethod());
        btnTestSend.setText("验证密码");
        if (editOldPassword.getText().length() == 0) editOldPassword.setText("000000");
        updateUIState();
        textStatus.setText("准备就绪");
    }

    private void setupListeners() {
        btnScan.setOnClickListener(v -> { if (checkPermissions()) startScan(); });
        btnConnect.setOnClickListener(v -> connectDevice());
        btnChangePassword.setOnClickListener(v -> startFlow(false));
        btnTestSend.setOnClickListener(v -> startFlow(true));
        btnViewLog.setOnClickListener(v -> showLogDialog());
        btnReset.setOnClickListener(v -> resetState());
        listViewDevices.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < devicesList.size()) {
                selectedDevice = devicesList.get(position);
                listViewDevices.setItemChecked(position, true);
                log("已选择 " + selectedDevice.getName() + "  " + selectedDevice.getAddress());
                updateUIState();
            }
        });
    }

    // ---------- 扫描 / 连接 ----------

    private boolean checkPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQUEST_BLUETOOTH_PERMISSIONS);
            return false;
        }
        if (!bt.isBluetoothEnabled()) {
            log("蓝牙未开启");
            bt.enableBluetooth(this);
            return false;
        }
        return true;
    }

    private void startScan() {
        devicesList.clear(); devicesMap.clear(); devicesAdapter.clear();
        selectedDevice = null;
        updateUIState();
        textStatus.setText("扫描中…（只显示 LOCK_ 开头的锁）");
        btnScan.setEnabled(false);
        log("开始扫描");
        bt.startScan(new BluetoothConnectionManager.ScanCallback() {
            @Override public void onDeviceFound(BluetoothDevice device, int rssi) {
                ui.post(() -> {
                    if (devicesMap.containsKey(device.getAddress())) return;
                    devicesMap.put(device.getAddress(), device);
                    devicesList.add(device);
                    devicesAdapter.add(String.format("%s  (%d dBm)", device.getName(), rssi));
                    log("找到 " + device.getName());
                });
            }
            @Override public void onScanComplete() {
                ui.post(() -> {
                    btnScan.setEnabled(true);
                    textStatus.setText("扫描完成，" + devicesList.size() + " 把锁");
                    if (devicesList.isEmpty()) log("没找到锁。锁要先按键唤醒（红灯慢闪 = 在广播）");
                });
            }
        });
    }

    private void connectDevice() {
        if (selectedDevice == null) { toast("请先在列表里点一把锁"); return; }
        bt.stopScan();
        String name = selectedDevice.getName();
        log("连接 " + name + " …");
        textStatus.setText("连接中…");
        btnConnect.setEnabled(false);
        resetSession();
        bt.connect(selectedDevice, new BluetoothConnectionManager.ConnectionCallback() {
            @Override public void onConnected() {
                ui.post(() -> {
                    isConnected = true;
                    updateUIState();
                    textStatus.setText("已连接 " + name);
                    log("连接成功，通知已开启。可以「验证密码」或「修改密码」");
                });
            }
            @Override public void onDisconnected() {
                ui.post(() -> {
                    isConnected = false;
                    resetSession();
                    updateUIState();
                    textStatus.setText("已断开");
                    log("连接已断开（锁 60 秒无操作会自己断开并休眠，属正常）");
                });
            }
            @Override public void onConnectionFailed(String error) {
                ui.post(() -> {
                    isConnected = false;
                    resetSession();
                    updateUIState();
                    textStatus.setText("连接失败");
                    log("连接失败: " + error);
                });
            }
        });
    }

    private void resetSession() {
        ui.removeCallbacks(stepTimeout);
        step = Step.IDLE;
        timeSynced = false;
        authed = false;
        key1 = null; key2 = null;
    }

    // ---------- 主流程 ----------

    private void startFlow(boolean verifyOnly) {
        if (!isConnected) { toast("请先连接锁"); return; }
        if (step != Step.IDLE) { toast("上一步还没完成"); return; }
        String oldP = editOldPassword.getText().toString().trim();
        String newP = editNewPassword.getText().toString().trim();
        if (!oldP.matches("\\d{6}")) { toast("旧密码必须是 6 位数字"); return; }
        if (!verifyOnly) {
            if (!newP.matches("\\d{6}")) { toast("新密码必须是 6 位数字"); return; }
            if (newP.equals(oldP)) { toast("新旧密码相同"); return; }
        }
        onlyVerify = verifyOnly;
        pendingOld = oldP; pendingNew = newP;
        setBusy(true);

        if (key1 == null) {
            key1 = BleLockSdk.deriveKey1(bt.getConnectedAddress());
            log("key1 = " + HexStringUtils.bytesToHexString(key1));
        }
        if (!timeSynced) sendSetTime();
        else if (!authed) sendAuth();
        else sendSetOrFinish();
    }

    private void sendSetTime() {
        Date now = new Date();
        key2 = BleLockSdk.deriveKey2(key1, now);
        byte[] frame = BleLockSdk.buildSetTime(nextId(), now);
        step = Step.WAIT_TIME;
        textStatus.setText("对时…");
        send(frame, key1, "0x10 对时");
    }

    private void sendAuth() {
        byte[] frame = BleLockSdk.buildAuthPasswd(nextId(), pendingOld);
        step = Step.WAIT_AUTH;
        textStatus.setText("验证密码…");
        send(frame, key2, "0x20 验证密码 " + pendingOld);
    }

    private void sendSetOrFinish() {
        if (onlyVerify) {
            finishOk("密码验证通过");
            return;
        }
        byte[] frame = BleLockSdk.buildSetAuthPasswd(nextId(), pendingNew);
        step = Step.WAIT_SET;
        textStatus.setText("修改密码…");
        send(frame, key2, "0x21 修改密码为 " + pendingNew);
    }

    private void send(byte[] plain, byte[] key, String what) {
        try {
            byte[] enc = BleLockSdk.encrypt(key, plain);
            log("→ " + what + "  明文 " + HexStringUtils.bytesToHexString(plain));
            if (!bt.write(enc)) { fail("写入失败（连接已断？）"); return; }
            ui.removeCallbacks(stepTimeout);
            ui.postDelayed(stepTimeout, STEP_TIMEOUT_MS);
        } catch (Exception e) {
            fail("加密异常: " + e.getMessage());
        }
    }

    private final BluetoothConnectionManager.DataListener dataListener = new BluetoothConnectionManager.DataListener() {
        @Override public void onNotify(byte[] enc) { ui.post(() -> handleNotify(enc)); }
        @Override public void onWriteResult(boolean success) { if (!success) ui.post(() -> fail("GATT 写入返回失败")); }
    };

    private void handleNotify(byte[] enc) {
        if (enc.length != 16) { log("← 非 16 字节通知，忽略: " + HexStringUtils.bytesToHexString(enc)); return; }
        byte[] key = (step == Step.WAIT_TIME) ? key1 : key2;
        if (key == null) { log("← 收到通知但无会话密钥，忽略"); return; }
        BleLockSdk.Response r = BleLockSdk.decryptResponse(key, enc);
        if (r == null) {
            // 主动推送等有时用另一把钥匙；两把都试一次
            byte[] alt = (key == key1) ? key2 : key1;
            r = (alt != null) ? BleLockSdk.decryptResponse(alt, enc) : null;
            if (r == null) { log("← 解密失败（密钥不对或帧损坏）: " + HexStringUtils.bytesToHexString(enc)); return; }
        }
        log("← 回复 cmd=0x" + String.format("%02X", r.cmd) + " 明文 " + HexStringUtils.bytesToHexString(r.raw));

        switch (step) {
            case WAIT_TIME:
                if (r.cmd != BleLockSdk.CMD_SET_TIME) return;
                ui.removeCallbacks(stepTimeout);
                timeSynced = true;
                log("对时成功，切换到 key2 = " + HexStringUtils.bytesToHexString(key2));
                sendAuth();
                break;
            case WAIT_AUTH:
                if (r.cmd != BleLockSdk.CMD_AUTH_PASSWD) return;
                ui.removeCallbacks(stepTimeout);
                if (r.status() == 0) {
                    authed = true;
                    log("密码验证通过");
                    sendSetOrFinish();
                } else {
                    fail("密码错误（锁回 01）。旧密码不是 " + pendingOld + "？");
                }
                break;
            case WAIT_SET:
                if (r.cmd != BleLockSdk.CMD_SET_AUTH_PASSWD) return;
                ui.removeCallbacks(stepTimeout);
                if (r.status() == 0) {
                    editOldPassword.setText(pendingNew);
                    editNewPassword.setText("");
                    finishOk("密码已改为 " + pendingNew + "，已写入锁的 Flash。建议断开重连后点「验证密码」确认一次");
                } else {
                    fail("锁拒绝修改（回 " + r.status() + "）");
                }
                break;
            default:
                // 空闲时收到的主动推送（如锁杆插入 0x40）
                log("（空闲状态收到通知，已记录）");
        }
    }

    private void onStepTimeout() {
        switch (step) {
            case WAIT_TIME:
                fail("对时无回复。可能：不是我们的锁固件 / 通知未开启 / 锁已休眠");
                break;
            case WAIT_AUTH:
                fail("验证密码无回复。0x10 之后 10 秒内必须发 0x20，请断开重连再试");
                break;
            case WAIT_SET:
                fail("修改密码无回复。固件需 v1.7 以上才支持 0x21");
                break;
            default:
        }
    }

    private void finishOk(String msg) {
        step = Step.IDLE;
        setBusy(false);
        textStatus.setText(msg);
        log("✅ " + msg);
        toast(msg);
    }

    private void fail(String msg) {
        ui.removeCallbacks(stepTimeout);
        step = Step.IDLE;
        setBusy(false);
        textStatus.setText("失败：" + msg);
        log("❌ " + msg);
        toast(msg);
    }

    private int nextId() {
        cmdId = (cmdId % 255) + 1;
        return cmdId;
    }

    // ---------- 界面杂项 ----------

    private void setBusy(boolean busy) {
        btnChangePassword.setEnabled(!busy && isConnected);
        btnTestSend.setEnabled(!busy && isConnected);
    }

    private void updateUIState() {
        btnScan.setEnabled(!isConnected);
        btnConnect.setEnabled(!isConnected && selectedDevice != null);
        btnChangePassword.setEnabled(isConnected);
        btnTestSend.setEnabled(isConnected);
        editNewPassword.setEnabled(isConnected);
    }

    private void resetState() {
        bt.disconnect();
        isConnected = false;
        resetSession();
        selectedDevice = null;
        devicesList.clear(); devicesMap.clear(); devicesAdapter.clear();
        editNewPassword.setText("");
        updateUIState();
        textStatus.setText("已重置");
        log("状态已重置");
    }

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
            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("日志", logBuffer.toString()));
            toast("已复制");
        });
        btnClear.setOnClickListener(v -> { logBuffer.setLength(0); textLog.setText(""); logTextView.setText(""); });
        btnClose.setOnClickListener(v -> dialog.dismiss());
    }

    private void log(String message) {
        String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new Date());
        logBuffer.append("[").append(ts).append("] ").append(message).append("\n");
        if (logBuffer.length() > 50000) logBuffer.delete(0, 5000);
        String[] lines = logBuffer.toString().split("\n");
        StringBuilder recent = new StringBuilder();
        for (int i = Math.max(0, lines.length - 100); i < lines.length; i++) recent.append(lines[i]).append("\n");
        textLog.setText(recent.toString());
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            boolean all = grantResults.length > 0;
            for (int g : grantResults) all &= (g == PackageManager.PERMISSION_GRANTED);
            if (all) startScan(); else toast("需要蓝牙和位置权限才能扫描");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bt.disconnect();
    }
}
