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
    private int testCount = 0;                        // 当前这把锁的开锁次数（磁簧断开计）
    private int lockState = -1;                        // -1 未知 / 0 已锁(锁杆插入) / 1 未锁(锁杆拔出)
    private static final byte LOCKED_BYTE = 0x01;      // AA 30 xx 中“已锁”对应值；若实测相反改为 0x00
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

    // 保活+轮询：每 1.5 秒查一次状态，既防断开，又能及时发现锁杆拔出
    private final Runnable keepAlive = new Runnable() {
        @Override public void run() {
            if (authed && mgr != null) {
                kaQuiet = true;
                mgr.sendData(BleLockSdk.status(), dataCb);
                ui.postDelayed(this, 1500);
            }
        }
    };
    private void startKeepAlive() { ui.removeCallbacks(keepAlive); ui.postDelayed(keepAlive, 1500); }
    private void stopKeepAlive() { ui.removeCallbacks(keepAlive); kaQuiet = false; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        mgr = new BluetoothConnectionManager(this);
        setButtonsEnabled(false);
        updateCount();
        log("老锁协议：服务 6E400007 / 写 6E400009 / 通知 6E400008");
        log("只显示 smart lock 设备。点“扫描并连接”开始。");
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
        h1.setText("智能锁测试工具  v7");
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
        TextView cLabel = tag("本把锁 · 开锁次数（拔出计）");
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
        setBtn(btnStatus, en);
        updateOpCloseButtons();
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
        lockState = -1;
        setButtonsEnabled(false);
        setStatus("正在扫描…", 0);
        log("环境：Android " + Build.VERSION.SDK_INT + " · 定位服务 " + (isLocationOn() ? "已开" : "未开")
                + " · 权限 " + (hasPermissions() ? "OK" : "缺"));
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
            setStatus("没扫到任何设备——请打开手机『定位/位置服务』后重试", 0);
            log("扫到 0 个设备。安卓扫蓝牙通常要求打开手机『定位服务』(即使系统蓝牙里能看到)。请打开定位后再扫。");
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
                    lockState = -1;
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
        boolean isStatus = d.length >= 3 && d[0] == BleLockSdk.RESP && d[1] == BleLockSdk.CMD_STATUS;
        if (kaQuiet && isStatus) { kaQuiet = false; handleStatus(d[2]); return; }   // 保活回包: 静默驱动状态
        log("收到：" + HexStringUtils.bytesToHexString(d));
        if (d[0] != BleLockSdk.RESP || d.length < 2) return;
        byte cmd = d[1];
        if (cmd == BleLockSdk.CMD_AUTH) {
            if (BleLockSdk.isAuthOk(d)) {
                if (!currentMac.equals(countedMac)) { testCount = 0; countedMac = currentMac; updateCount(); }
                setButtonsEnabled(true);
                setStatus("认证成功 ✓ 可开关锁", 1);
                log("认证成功；按“开锁”后在约2秒内拔出锁杆才算一次（否则会自动回锁）");
                startKeepAlive();
                send(BleLockSdk.status(), "查状态");       // 立即取一次状态初始化
            } else {
                setButtonsEnabled(false);
                setStatus("认证失败：连接密码不对", 2);
                log("认证失败（密码错），换个密码再试");
            }
        } else if (cmd == BleLockSdk.CMD_STATUS && d.length >= 3) {
            handleStatus(d[2]);
        } else if (cmd == BleLockSdk.CMD_ENERGY && d.length >= 3) {
            log("电量：" + (d[2] & 0xFF) + "%");
        }
    }

    /**
     * 锁状态（磁簧开关）：锁杆插入=已锁；拔出=开锁。
     * 每检测到一次 已锁→未锁（锁杆被拔出）记一次开锁；未锁时禁止再开锁。
     * 本固件：开锁后约 1~2 秒不拔锁杆会自动回锁，不拔则不计数。
     * 若实测上锁/开锁显示相反，把顶部 LOCKED_BYTE 改成另一个值即可。
     */
    private void handleStatus(byte st) {
        int ns = (st == LOCKED_BYTE) ? 0 : 1;
        if (lockState == 0 && ns == 1) {               // 锁杆拔出 → 记一次开锁
            testCount++;
            updateCount();
            log("检测到开锁（锁杆拔出）→ 开锁次数 " + testCount);
        }
        if (ns != lockState) {
            log("锁状态：" + (ns == 0 ? "已锁(锁杆插入)" : "未锁(锁杆拔出)")
                    + "  [AA 30 " + String.format("%02X", st) + "]");
        }
        lockState = ns;
        setStatus(ns == 0 ? "已连接 · 已锁 🔒" : "已连接 · 未锁 🔓（拔出）", 1);
        updateOpCloseButtons();
    }

    /** 开锁仅在已锁/未知时可用；未锁时禁止再开锁。关锁认证后可用。 */
    private void updateOpCloseButtons() {
        setBtn(btnOpen, authed && lockState != 1);
        setBtn(btnClose, authed);
    }

    /** 开/关锁：只发命令。开锁计数由“锁杆拔出(磁簧断开)”驱动，不在此 +1 */
    private void doOp(byte[] cmd, String what) {
        if (!authed) { toast("请先连接并认证"); return; }
        send(cmd, what);
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
        lockState = -1;
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
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION };
        }
        return new String[]{ Manifest.permission.ACCESS_FINE_LOCATION };
    }

    private boolean isLocationOn() {
        try {
            android.location.LocationManager lm =
                    (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) { return true; }
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
