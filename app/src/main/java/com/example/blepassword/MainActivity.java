package com.example.blepassword;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 锁测试工具 —— 连接老固件（服务 6E400007，广播名 smart lock），
 * 自动发送连接密码认证，然后一键开/关锁。用于产线逐把测试。
 */
public class MainActivity extends AppCompatActivity {

    private BluetoothConnectionManager mgr;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText etPwd;
    private TextView tvStatus;
    private TextView tvLog;
    private Button btnScan, btnOpen, btnClose, btnStatus, btnDisconnect;

    private boolean authed = false;
    private final Map<String, BluetoothDevice> found = new LinkedHashMap<>();
    private final Map<String, Integer> foundRssi = new LinkedHashMap<>();

    private static final int REQ_PERM = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        mgr = new BluetoothConnectionManager(this);
        setButtonsEnabled(false);
        log("老锁协议：服务 6E400007 / 写 6E400009 / 通知 6E400008");
        log("默认连接密码 000000。点“扫描并连接”开始。");
        ensurePermissions();
    }

    // ---------------------------------------------------------------- UI
    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("锁测试工具");
        title.setTextSize(22);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        LinearLayout pwdRow = new LinearLayout(this);
        pwdRow.setOrientation(LinearLayout.HORIZONTAL);
        pwdRow.setPadding(0, dp(12), 0, dp(4));
        TextView pwdLabel = new TextView(this);
        pwdLabel.setText("连接密码：");
        pwdLabel.setTextSize(16);
        pwdLabel.setGravity(Gravity.CENTER_VERTICAL);
        etPwd = new EditText(this);
        etPwd.setText("000000");
        etPwd.setSingleLine(true);
        etPwd.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pwdRow.addView(pwdLabel);
        pwdRow.addView(etPwd);
        root.addView(pwdRow);

        btnScan = big("① 扫描并连接锁");
        btnScan.setOnClickListener(v -> onScan());
        root.addView(btnScan);

        tvStatus = new TextView(this);
        tvStatus.setText("状态：未连接");
        tvStatus.setTextSize(18);
        tvStatus.setTextColor(Color.parseColor("#B00020"));
        tvStatus.setPadding(0, dp(10), 0, dp(10));
        root.addView(tvStatus);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        btnOpen = big("② 开锁");
        btnOpen.setOnClickListener(v -> send(BleLockSdk.open(), "开锁"));
        btnClose = big("② 关锁");
        btnClose.setOnClickListener(v -> send(BleLockSdk.close(), "关锁"));
        row1.addView(wrap(btnOpen));
        row1.addView(wrap(btnClose));
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        btnStatus = big("查状态");
        btnStatus.setOnClickListener(v -> send(BleLockSdk.status(), "查状态"));
        btnDisconnect = big("断开");
        btnDisconnect.setOnClickListener(v -> onDisconnect());
        row2.addView(wrap(btnStatus));
        row2.addView(wrap(btnDisconnect));
        root.addView(row2);

        TextView logLabel = new TextView(this);
        logLabel.setText("日志：");
        logLabel.setPadding(0, dp(10), 0, 0);
        root.addView(logLabel);

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        tvLog = new TextView(this);
        tvLog.setTextSize(13);
        tvLog.setTextColor(Color.DKGRAY);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        sv.addView(tvLog);
        root.addView(sv);

        return root;
    }

    private Button big(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(18);
        b.setAllCaps(false);
        return b;
    }

    private View wrap(Button b) {
        b.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void setButtonsEnabled(boolean en) {
        authed = en;
        if (btnOpen != null) btnOpen.setEnabled(en);
        if (btnClose != null) btnClose.setEnabled(en);
        if (btnStatus != null) btnStatus.setEnabled(en);
    }

    // ---------------------------------------------------------------- scan
    private void onScan() {
        if (!hasPermissions()) { ensurePermissions(); return; }
        if (!mgr.isBluetoothEnabled()) {
            mgr.enableBluetooth(this);
            toast("请先打开蓝牙");
            return;
        }
        found.clear();
        foundRssi.clear();
        setButtonsEnabled(false);
        setStatus("正在扫描…", false);
        log("开始扫描（约 6 秒）…");
        mgr.startScan(new BluetoothConnectionManager.ScanCallback() {
            @Override public void onDeviceFound(BluetoothDevice device, int rssi) {
                String mac = device.getAddress();
                String name = safeName(device);
                if (!found.containsKey(mac)) log("发现：" + name + " [" + mac + "] " + rssi + "dBm");
                found.put(mac, device);
                foundRssi.put(mac, rssi);
            }
            @Override public void onScanComplete() {
                ui.post(MainActivity.this::afterScan);
            }
        });
        ui.postDelayed(() -> mgr.stopScan(), 6000);
    }

    private void afterScan() {
        if (found.isEmpty()) {
            setStatus("没扫到设备，重试或检查锁是否上电", false);
            return;
        }
        final List<BluetoothDevice> devs = new ArrayList<>(found.values());
        final String[] items = new String[devs.size()];
        for (int i = 0; i < devs.size(); i++) {
            BluetoothDevice d = devs.get(i);
            items[i] = safeName(d) + "  [" + d.getAddress() + "]  "
                    + foundRssi.get(d.getAddress()) + "dBm";
        }
        if (devs.size() == 1) { connect(devs.get(0)); return; }
        new AlertDialog.Builder(this)
                .setTitle("选择要测试的锁")
                .setItems(items, (dialog, which) -> connect(devs.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- connect + auth
    private void connect(BluetoothDevice device) {
        setStatus("连接中：" + safeName(device) + " …", false);
        log("连接 " + safeName(device) + " [" + device.getAddress() + "]");
        mgr.connect(device, new BluetoothConnectionManager.ConnectionCallbackWithInfo() {
            @Override public void onServicesDiscovered(String info) { ui.post(() -> log(info)); }
            @Override public void onConnected() {
                ui.post(() -> {
                    setStatus("已连接，正在认证…", false);
                    log("已连接，发送连接密码认证…");
                });
                // 等通知通道就绪后再发认证
                ui.postDelayed(() -> send(BleLockSdk.auth(pwd()), "认证(连接密码 " + pwd() + ")"), 600);
            }
            @Override public void onDisconnected() {
                ui.post(() -> { setStatus("已断开", false); setButtonsEnabled(false); log("连接断开"); });
            }
            @Override public void onConnectionFailed(String error) {
                ui.post(() -> { setStatus("连接失败：" + error, false); log("连接失败：" + error); });
            }
        });
    }

    private final BluetoothConnectionManager.DataCallback dataCb =
            new BluetoothConnectionManager.DataCallback() {
        @Override public void onDataReceived(byte[] data) { ui.post(() -> onResp(data)); }
        @Override public void onDataSent(boolean success) {
            ui.post(() -> log(success ? "  → 已发送" : "  → 发送失败"));
        }
    };

    private void onResp(byte[] d) {
        if (d == null || d.length == 0) return;
        log("收到：" + HexStringUtils.bytesToHexString(d));
        if (d[0] != BleLockSdk.RESP || d.length < 2) return;
        byte cmd = d[1];
        if (cmd == BleLockSdk.CMD_AUTH) {
            if (BleLockSdk.isAuthOk(d)) {
                setButtonsEnabled(true);
                setStatus("认证成功 ✓ 可以开关锁了", true);
                log("认证成功，可以开/关锁");
            } else {
                setButtonsEnabled(false);
                setStatus("认证失败：连接密码不对", false);
                log("认证失败（密码错），试试别的密码");
            }
        } else if (cmd == BleLockSdk.CMD_STATUS && d.length >= 3) {
            setStatus(d[2] == 0x01 ? "锁状态：已锁 🔒" : "锁状态：未锁 🔓", true);
        } else if (cmd == BleLockSdk.CMD_ENERGY && d.length >= 3) {
            log("电量：" + (d[2] & 0xFF) + "%");
        }
    }

    private void send(byte[] data, String what) {
        if (mgr == null) return;
        if (!authed && !what.startsWith("认证")) {
            toast("请先连接并认证");
            return;
        }
        log("发送 " + what + "：" + HexStringUtils.bytesToHexString(data));
        boolean ok = mgr.sendData(data, dataCb);
        if (!ok) log("  → 写入失败（未连接？）");
    }

    private void onDisconnect() {
        if (mgr != null) mgr.disconnect();
        setButtonsEnabled(false);
        setStatus("已断开", false);
    }

    // ---------------------------------------------------------------- helpers
    private String pwd() {
        String p = etPwd.getText().toString().trim();
        return p.isEmpty() ? "000000" : p;
    }

    private String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? "(无名)" : n;
        } catch (SecurityException e) {
            return "(无名)";
        }
    }

    private void setStatus(String s, boolean ok) {
        tvStatus.setText("状态：" + s);
        tvStatus.setTextColor(ok ? Color.parseColor("#1B7F3B") : Color.parseColor("#B00020"));
    }

    private void log(String s) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        tvLog.append("[" + t + "] " + s + "\n");
        final int amount = tvLog.getLayout() == null ? 0
                : tvLog.getLayout().getLineTop(tvLog.getLineCount()) - tvLog.getHeight();
        if (amount > 0) tvLog.scrollTo(0, amount);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---------------------------------------------------------------- permissions
    private String[] neededPerms() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{ Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT };
        }
        return new String[]{ Manifest.permission.ACCESS_FINE_LOCATION };
    }

    private boolean hasPermissions() {
        for (String p : neededPerms()) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    private void ensurePermissions() {
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, neededPerms(), REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            if (hasPermissions()) log("权限已授予");
            else toast("需要蓝牙/定位权限才能扫描");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mgr != null) mgr.disconnect();
    }
}
