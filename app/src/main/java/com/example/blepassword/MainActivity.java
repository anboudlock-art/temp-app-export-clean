package com.example.blepassword;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
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
 * 智能锁测试工具（CC2541 老固件）。
 * 只显示广播名含 "smart lock" 的设备；连接后自动认证并保活，可连续开/关锁；
 * 统计当前这把锁的开关测试次数。
 */
public class MainActivity extends AppCompatActivity {

    private BluetoothConnectionManager mgr;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText etPwd;
    private TextView tvStatus, tvDevice, tvCount, tvLog;
    private Button btnScan, btnOpen, btnClose, btnStatus, btnDisconnect;

    private boolean authed = false;
    private boolean kaQuiet = false;                 // 保活静默标志（不刷日志）
    private int testCount = 0;                        // 当前这把锁的开关测试次数
    private String currentMac = "";
    private String countedMac = "";
    private final Map<String, BluetoothDevice> found = new LinkedHashMap<>();
    private final Map<String, Integer> foundRssi = new LinkedHashMap<>();
    private final Map<String, String> foundName = new LinkedHashMap<>();
    private final java.util.Set<String> matchMacs = new java.util.HashSet<>();

    private static final int REQ_PERM = 1001;

    // 配色
    private static final int C_BG      = 0xFFEEF2F6;
    private static final int C_HEADER  = 0xFF1565C0;
    private static final int C_CARD    = 0xFFFFFFFF;
    private static final int C_COUNT   = 0xFFE3F2FD;
    private static final int C_BLUE    = 0xFF1565C0;
    private static final int C_GREEN   = 0xFF2E7D32;
    private static final int C_ORANGE  = 0xFFE65100;
    private static final int C_GREY    = 0xFFCFD8DC;
    private static final int C_OKTXT   = 0xFF1B7F3B;
    private static final int C_ERRTXT  = 0xFFC62828;

    // 保活：每 3 秒发一次状态查询，防止锁因空闲自动断开
    private final Runnable keepAlive = new Runnable() {
        @Override public void run() {
            if (authed && mgr != null) {
                kaQuiet = true;
                mgr.sendData(BleLockSdk.status(), dataCb);
                ui.postDelayed(this, 3000);
            }
        }
    };
    private void startKeepAlive() { ui.removeCallbacks(keepAlive); ui.postDelayed(keepAlive, 3000); }
    private void stopKeepAlive() { ui.removeCallbacks(keepAlive); kaQuiet = false; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        mgr = new BluetoothConnectionManager(this);
        setButtonsEnabled(false);
        updateCount();
        log("老锁协议：服务 6E400007 / 写 6E400009 / 通知 6E400008");
        log("优先显示 smart / SMNT 设备。点“扫描并连接”开始。");
        ensurePermissions();
    }

    // ---------------------------------------------------------------- UI
    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(C_HEADER);
        int hp = dp(16);
        header.setPadding(hp, dp(18), hp, dp(16));
        TextView h1 = new TextView(this);
        h1.setText("智能锁测试工具  v5");
        h1.setTextSize(22);
        h1.setTypeface(Typeface.DEFAULT_BOLD);
        h1.setTextColor(Color.WHITE);
        TextView h2 = new TextView(this);
        h2.setText("逐把测试 · 扫描 → 连接 → 自动认证 → 开 / 关");
        h2.setTextSize(12);
        h2.setTextColor(0xCCFFFFFF);
        header.addView(h1);
        header.addView(h2);
        root.addView(header);

        // 可滚动内容
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int cp = dp(14);
        content.setPadding(cp, cp, cp, cp);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll);

        // 连接密码 + 扫描按钮
        LinearLayout connCard = card(C_CARD);
        LinearLayout pwdRow = new LinearLayout(this);
        pwdRow.setOrientation(LinearLayout.HORIZONTAL);
        pwdRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView pwdLabel = new TextView(this);
        pwdLabel.setText("连接密码 ");
        pwdLabel.setTextColor(Color.DKGRAY);
        etPwd = new EditText(this);
        etPwd.setText("000000");
        etPwd.setSingleLine(true);
        etPwd.setInputType(InputType.TYPE_CLASS_NUMBER);
        etPwd.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pwdRow.addView(pwdLabel);
        pwdRow.addView(etPwd);
        connCard.addView(pwdRow);
        btnScan = mkBtn("① 扫描并连接  (smart lock)", C_BLUE, Color.WHITE, 17);
        topMargin(btnScan, 10);
        btnScan.setOnClickListener(v -> onScan());
        connCard.addView(btnScan);
        content.addView(connCard);

        // 状态卡
        LinearLayout statusCard = card(C_CARD);
        TextView sLabel = tag("状态");
        tvStatus = new TextView(this);
        tvStatus.setText("未连接");
        tvStatus.setTextSize(19);
        tvStatus.setTypeface(Typeface.DEFAULT_BOLD);
        tvStatus.setTextColor(C_ERRTXT);
        tvDevice = new TextView(this);
        tvDevice.setText("");
        tvDevice.setTextSize(12);
        tvDevice.setTextColor(Color.GRAY);
        statusCard.addView(sLabel);
        statusCard.addView(tvStatus);
        statusCard.addView(tvDevice);
        content.addView(statusCard);

        // 次数卡
        LinearLayout countCard = card(C_COUNT);
        TextView cLabel = tag("本把锁已测试");
        cLabel.setTextColor(0xFF0D47A1);
        LinearLayout countRow = new LinearLayout(this);
        countRow.setOrientation(LinearLayout.HORIZONTAL);
        countRow.setGravity(Gravity.CENTER_VERTICAL);
        tvCount = new TextView(this);
        tvCount.setText("0");
        tvCount.setTextSize(46);
        tvCount.setTypeface(Typeface.DEFAULT_BOLD);
        tvCount.setTextColor(0xFF0D47A1);
        TextView cUnit = new TextView(this);
        cUnit.setText("  次");
        cUnit.setTextSize(18);
        cUnit.setTextColor(0xFF0D47A1);
        countRow.addView(tvCount);
        countRow.addView(cUnit);
        countCard.addView(cLabel);
        countCard.addView(countRow);
        content.addView(countCard);

        // 开 / 关 大按钮
        LinearLayout opRow = new LinearLayout(this);
        opRow.setOrientation(LinearLayout.HORIZONTAL);
        topMargin(opRow, 4);
        btnOpen = mkBtn("开 锁", C_GREEN, Color.WHITE, 22);
        btnClose = mkBtn("关 锁", C_ORANGE, Color.WHITE, 22);
        btnOpen.setMinimumHeight(dp(64));
        btnClose.setMinimumHeight(dp(64));
        btnOpen.setOnClickListener(v -> doOp(BleLockSdk.open(), "开锁"));
        btnClose.setOnClickListener(v -> doOp(BleLockSdk.close(), "关锁"));
        opRow.addView(weight(btnOpen, 6));
        opRow.addView(weight(btnClose, 6));
        content.addView(opRow);

        // 查状态 / 断开
        LinearLayout subRow = new LinearLayout(this);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        topMargin(subRow, 2);
        btnStatus = mkBtn("查状态", C_GREY, Color.BLACK, 15);
        btnDisconnect = mkBtn("断开", C_GREY, Color.BLACK, 15);
        btnStatus.setOnClickListener(v -> send(BleLockSdk.status(), "查状态"));
        btnDisconnect.setOnClickListener(v -> onDisconnect());
        subRow.addView(weight(btnStatus, 6));
        subRow.addView(weight(btnDisconnect, 6));
        content.addView(subRow);

        // 日志（固定在底部，独立滚动）
        TextView logLabel = tag("日志");
        logLabel.setPadding(dp(14), dp(6), dp(14), dp(2));
        root.addView(logLabel);
        ScrollView logScroll = new ScrollView(this);
        LinearLayout.LayoutParams lsp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        lsp.setMargins(dp(14), 0, dp(14), dp(12));
        logScroll.setLayoutParams(lsp);
        logScroll.setBackground(rounded(0xFFFAFAFA, 10));
        tvLog = new TextView(this);
        tvLog.setPadding(dp(10), dp(8), dp(10), dp(8));
        tvLog.setTextSize(12);
        tvLog.setTextColor(0xFF444444);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        logScroll.addView(tvLog);
        root.addView(logScroll);

        return root;
    }

    // ---- UI helpers ----
    private GradientDrawable rounded(int color, int radDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radDp));
        return g;
    }

    private LinearLayout card(int bg) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(rounded(bg, 12));
        int p = dp(14);
        l.setPadding(p, dp(12), p, dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        l.setLayoutParams(lp);
        return l;
    }

    private TextView tag(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(Color.GRAY);
        return t;
    }

    private Button mkBtn(String text, int bg, int fg, float size) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(size);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setBackground(rounded(bg, 10));
        try { b.setStateListAnimator(null); } catch (Throwable ignore) {}
        return b;
    }

    private View weight(Button b, int marginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(marginDp) / 2, dp(6), dp(marginDp) / 2, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void topMargin(View v, int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(dp), 0, 0);
        v.setLayoutParams(lp);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void setButtonsEnabled(boolean en) {
        authed = en;
        setBtn(btnOpen, en);
        setBtn(btnClose, en);
        setBtn(btnStatus, en);
    }

    private void setBtn(Button b, boolean en) {
        if (b == null) return;
        b.setEnabled(en);
        b.setAlpha(en ? 1f : 0.4f);
    }

    private void updateCount() {
        if (tvCount != null) tvCount.setText(String.valueOf(testCount));
    }

    // ---------------------------------------------------------------- scan
    private void onScan() {
        if (!hasPermissions()) { ensurePermissions(); return; }
        if (!mgr.isBluetoothEnabled()) {
            mgr.enableBluetooth(this);
            toast("请先打开蓝牙");
            return;
        }
        stopKeepAlive();
        found.clear();
        foundRssi.clear();
        foundName.clear();
        matchMacs.clear();
        setButtonsEnabled(false);
        setStatus("正在扫描…", 0);
        log("开始扫描（约 6 秒）…");
        mgr.startScan(new BluetoothConnectionManager.ScanCallback() {
            @Override public void onDeviceFound(BluetoothDevice device, int rssi, String name, boolean isLock) {
                String nm = (name == null || name.trim().isEmpty()) ? "(无名)" : name.trim();
                String mac = device.getAddress();
                if (!found.containsKey(mac)) log("发现：" + nm + " [" + mac + "] " + rssi + "dBm" + (isLock ? "  ★锁" : ""));
                found.put(mac, device);
                foundRssi.put(mac, rssi);
                foundName.put(mac, nm);
                if (isLock) matchMacs.add(mac);
            }
            @Override public void onScanComplete() {
                ui.post(MainActivity.this::afterScan);
            }
        });
        ui.postDelayed(() -> mgr.stopScan(), 6000);
    }

    private void afterScan() {
        if (found.isEmpty()) {
            setStatus("没扫到设备，确认锁上电/靠近后重试", 0);
            return;
        }
        final List<BluetoothDevice> devs = new ArrayList<>();
        if (!matchMacs.isEmpty()) {
            for (Map.Entry<String, BluetoothDevice> e : found.entrySet())
                if (matchMacs.contains(e.getKey())) devs.add(e.getValue());
        } else {
            devs.addAll(found.values());
            log("未匹配到 smart/SMNT，列出全部 " + found.size() + " 个设备供选择");
        }
        final String[] items = new String[devs.size()];
        for (int i = 0; i < devs.size(); i++) {
            BluetoothDevice d = devs.get(i);
            items[i] = foundName.get(d.getAddress()) + "   [" + d.getAddress() + "]   "
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
        currentMac = device.getAddress();
        String nm = foundName.containsKey(currentMac) ? foundName.get(currentMac) : safeName(device);
        setStatus("连接中…", 0);
        tvDevice.setText(nm + "  [" + currentMac + "]");
        log("连接 " + nm + " [" + currentMac + "]");
        mgr.connect(device, new BluetoothConnectionManager.ConnectionCallbackWithInfo() {
            @Override public void onServicesDiscovered(String info) { ui.post(() -> log(info)); }
            @Override public void onConnected() {
                mgr.requestHighPriority();
                ui.post(() -> { setStatus("已连接，正在认证…", 0); log("已连接，发送连接密码认证…"); });
                ui.postDelayed(() -> send(BleLockSdk.auth(pwd()), "认证(连接密码 " + pwd() + ")"), 600);
            }
            @Override public void onDisconnected() {
                ui.post(() -> {
                    stopKeepAlive();
                    setStatus("已断开（可重新扫描连接）", 0);
                    setButtonsEnabled(false);
                    log("连接断开");
                });
            }
            @Override public void onConnectionFailed(String error) {
                ui.post(() -> { setStatus("连接失败：" + error, 0); log("连接失败：" + error); });
            }
        });
    }

    private final BluetoothConnectionManager.DataCallback dataCb =
            new BluetoothConnectionManager.DataCallback() {
        @Override public void onDataReceived(byte[] data) { ui.post(() -> onResp(data)); }
        @Override public void onDataSent(boolean success) {
            ui.post(() -> { if (!kaQuiet) log(success ? "  → 已发送" : "  → 发送失败"); });
        }
    };

    private void onResp(byte[] d) {
        if (d == null || d.length == 0) return;
        // 保活回包：静默更新状态，不刷日志
        if (kaQuiet && d.length >= 2 && d[0] == BleLockSdk.RESP && d[1] == BleLockSdk.CMD_STATUS) {
            kaQuiet = false;
            if (d.length >= 3) setStatus(d[2] == 0x01 ? "已连接 · 已锁 🔒" : "已连接 · 未锁 🔓", 1);
            return;
        }
        log("收到：" + HexStringUtils.bytesToHexString(d));
        if (d[0] != BleLockSdk.RESP || d.length < 2) return;
        byte cmd = d[1];
        if (cmd == BleLockSdk.CMD_AUTH) {
            if (BleLockSdk.isAuthOk(d)) {
                setButtonsEnabled(true);
                if (!currentMac.equals(countedMac)) { testCount = 0; countedMac = currentMac; updateCount(); }
                setStatus("认证成功 ✓ 可连续开关锁", 1);
                log("认证成功，已开启保活（每 3 秒），可连续开/关");
                startKeepAlive();
            } else {
                setButtonsEnabled(false);
                setStatus("认证失败：连接密码不对", 2);
                log("认证失败（密码错），换个密码再试");
            }
        } else if (cmd == BleLockSdk.CMD_STATUS && d.length >= 3) {
            setStatus(d[2] == 0x01 ? "已连接 · 已锁 🔒" : "已连接 · 未锁 🔓", 1);
        } else if (cmd == BleLockSdk.CMD_ENERGY && d.length >= 3) {
            log("电量：" + (d[2] & 0xFF) + "%");
        }
    }

    /** 开/关锁：发送并计数 */
    private void doOp(byte[] cmd, String what) {
        if (!authed) { toast("请先连接并认证"); return; }
        send(cmd, what);
        testCount++;
        updateCount();
    }

    private void send(byte[] data, String what) {
        if (mgr == null) return;
        if (!authed && !what.startsWith("认证")) { toast("请先连接并认证"); return; }
        log("发送 " + what + "：" + HexStringUtils.bytesToHexString(data));
        boolean ok = mgr.sendData(data, dataCb);
        if (!ok) log("  → 写入失败（未连接？）");
    }

    private void onDisconnect() {
        stopKeepAlive();
        if (mgr != null) mgr.disconnect();
        setButtonsEnabled(false);
        setStatus("已断开", 0);
    }

    // ---------------------------------------------------------------- helpers
    private String pwd() {
        String p = etPwd.getText().toString().trim();
        return p.isEmpty() ? "000000" : p;
    }

    /** 锁设备：名称含 smart（不区分大小写，如 "smart lock"） */
    private boolean isLockName(String name) {
        return name != null && name.toLowerCase(Locale.US).contains("smart");
    }

    private String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? "(无名)" : n;
        } catch (SecurityException e) {
            return "(无名)";
        }
    }

    /** kind: 0=普通/红 1=成功/绿 2=错误/红 */
    private void setStatus(String s, int kind) {
        tvStatus.setText(s);
        tvStatus.setTextColor(kind == 1 ? C_OKTXT : C_ERRTXT);
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
        stopKeepAlive();
        if (mgr != null) mgr.disconnect();
    }
}
