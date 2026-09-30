package com.example.blepassword;

import android.util.Log;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 蓝牙锁协议 —— 与《智能锁4G+蓝牙通讯协议 v2.0》一致（量产固件 v1.14 逐行核对）。
 *
 * 明文帧:   55 | 指令ID | CMD | 参数… | 校验和        校验和 = 指令ID..参数 累加低 8 位
 * 回复帧:   AA | 指令ID | CMD | 应答… | 校验和
 * 加密:     AES-128-ECB, 16 字节容器  FB | len | 明文帧 | FC FC …
 * 密钥:     key1 = MAC(6, 广播名顺序) + 11 22 33 44 55 66 77 88 99 AA
 *           key2 = key1, [10..15] 换成 0x10 命令里的 年-2000 月 日 时 分 秒
 *           0x10 用 key1, 之后全部用 key2
 */
public final class BleLockSdk {
    private static final String TAG = "BleLockSdk";

    public static final UUID SERVICE_UUID = UUID.fromString("6E40000A-B5A3-F393-E0A9-E50E24DCCA9E");
    public static final UUID NOTIFY_UUID  = UUID.fromString("6E40000B-B5A3-F393-E0A9-E50E24DCCA9E");
    public static final UUID WRITE_UUID   = UUID.fromString("6E40000C-B5A3-F393-E0A9-E50E24DCCA9E");

    public static final byte REQ_HEAD    = 0x55;
    public static final byte RESP_HEAD   = (byte) 0xAA;
    public static final byte CIPHER_HEAD = (byte) 0xFB;
    public static final byte CIPHER_FILL = (byte) 0xFC;

    public static final byte CMD_SET_TIME        = 0x10;
    public static final byte CMD_AUTH_PASSWD     = 0x20;
    public static final byte CMD_SET_AUTH_PASSWD = 0x21;
    public static final byte CMD_OPEN_LOCK       = 0x30;
    public static final byte CMD_CLOSE_LOCK      = 0x31;
    public static final byte CMD_GET_STATUS      = 0x40;
    public static final byte CMD_FORCE_SLEEP     = 0x50;

    public static final byte RESULT_SUCCESS = 0x00;
    public static final byte RESULT_FAILURE = 0x01;

    private BleLockSdk() {}

    // ---------------- 密钥 ----------------

    /** mac 形如 "20:25:10:23:A8:00"（Android 的 device.getAddress()），顺序即广播名里的顺序 */
    public static byte[] deriveKey1(String mac) {
        byte[] m = HexStringUtils.hexStringToBytes(mac.replace(":", "").replace("-", ""));
        if (m == null || m.length != 6) throw new IllegalArgumentException("MAC 必须 6 字节: " + mac);
        byte[] key = new byte[16];
        System.arraycopy(m, 0, key, 0, 6);
        for (int i = 0; i < 10; i++) key[6 + i] = (byte) (0x11 * (i + 1));
        return key;
    }

    public static byte[] deriveKey2(byte[] key1, Date t) {
        byte[] key2 = key1.clone();
        Calendar c = Calendar.getInstance();
        c.setTime(t);
        key2[10] = (byte) (c.get(Calendar.YEAR) - 2000);
        key2[11] = (byte) (c.get(Calendar.MONTH) + 1);
        key2[12] = (byte) c.get(Calendar.DAY_OF_MONTH);
        key2[13] = (byte) c.get(Calendar.HOUR_OF_DAY);
        key2[14] = (byte) c.get(Calendar.MINUTE);
        key2[15] = (byte) c.get(Calendar.SECOND);
        return key2;
    }

    // ---------------- 帧 ----------------

    public static byte checksum(byte[] buf, int start, int len) {
        int sum = 0;
        for (int i = start; i < start + len; i++) sum += buf[i] & 0xFF;
        return (byte) (sum & 0xFF);
    }

    private static byte[] buildFrame(byte cmd, byte[] params, int cmdId) {
        byte[] raw = new byte[3 + params.length + 1];
        raw[0] = REQ_HEAD;
        raw[1] = (byte) cmdId;
        raw[2] = cmd;
        System.arraycopy(params, 0, raw, 3, params.length);
        raw[raw.length - 1] = checksum(raw, 1, raw.length - 2);
        return raw;
    }

    public static byte[] buildSetTime(int cmdId, Date t) {
        Calendar c = Calendar.getInstance();
        c.setTime(t);
        byte[] p = {
            (byte) (c.get(Calendar.YEAR) - 2000),
            (byte) (c.get(Calendar.MONTH) + 1),
            (byte) c.get(Calendar.DAY_OF_MONTH),
            (byte) c.get(Calendar.HOUR_OF_DAY),
            (byte) c.get(Calendar.MINUTE),
            (byte) c.get(Calendar.SECOND),
        };
        return buildFrame(CMD_SET_TIME, p, cmdId);
    }

    /** 6 位数字密码 → 每位一字节 0..9（不是 ASCII） */
    private static byte[] digits6(String passwd) {
        if (passwd == null || !passwd.matches("\\d{6}")) throw new IllegalArgumentException("密码必须是 6 位数字");
        byte[] d = new byte[6];
        for (int i = 0; i < 6; i++) d[i] = (byte) (passwd.charAt(i) - '0');
        return d;
    }

    public static byte[] buildAuthPasswd(int cmdId, String passwd) {
        return buildFrame(CMD_AUTH_PASSWD, digits6(passwd), cmdId);
    }

    public static byte[] buildSetAuthPasswd(int cmdId, String passwd) {
        return buildFrame(CMD_SET_AUTH_PASSWD, digits6(passwd), cmdId);
    }

    public static byte[] buildGetStatus(int cmdId) {
        return buildFrame(CMD_GET_STATUS, new byte[]{0x01}, cmdId);
    }

    // ---------------- 加密容器 ----------------

    public static byte[] pack16(byte[] raw) {
        if (raw.length > 14) throw new IllegalArgumentException("明文帧不能超过 14 字节");
        byte[] block = new byte[16];
        Arrays.fill(block, CIPHER_FILL);
        block[0] = CIPHER_HEAD;
        block[1] = (byte) raw.length;
        System.arraycopy(raw, 0, block, 2, raw.length);
        return block;
    }

    public static byte[] unpack16(byte[] block) {
        if (block.length != 16) throw new IllegalArgumentException("密文块必须 16 字节");
        if (block[0] != CIPHER_HEAD) throw new IllegalArgumentException("容器头不是 FB (密钥不对?)");
        int len = block[1] & 0xFF;
        if (len < 1 || len > 14) throw new IllegalArgumentException("容器长度非法: " + len);
        return Arrays.copyOfRange(block, 2, 2 + len);
    }

    private static byte[] aes(byte[] key, byte[] block, int mode) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(block);
    }

    /** 明文帧 → 16 字节密文（直接写特征） */
    public static byte[] encrypt(byte[] key, byte[] rawFrame) throws Exception {
        return aes(key, pack16(rawFrame), Cipher.ENCRYPT_MODE);
    }

    /** 16 字节密文（通知收到的）→ 明文回复帧 */
    public static byte[] decrypt(byte[] key, byte[] encrypted) throws Exception {
        return unpack16(aes(key, encrypted, Cipher.DECRYPT_MODE));
    }

    // ---------------- 回复解析 ----------------

    public static final class Response {
        public final int cmdId;
        public final byte cmd;
        public final byte[] payload;
        public final byte[] raw;

        Response(int cmdId, byte cmd, byte[] payload, byte[] raw) {
            this.cmdId = cmdId; this.cmd = cmd; this.payload = payload; this.raw = raw;
        }

        /** 0x20 / 0x21 的应答字节；其它命令返回 -1 */
        public int status() {
            return (cmd == CMD_AUTH_PASSWD || cmd == CMD_SET_AUTH_PASSWD) && payload.length >= 1
                    ? (payload[0] & 0xFF) : -1;
        }
    }

    public static Response parseResponse(byte[] raw) {
        if (raw.length < 4) throw new IllegalArgumentException("回复太短: " + raw.length);
        if (raw[0] != RESP_HEAD) throw new IllegalArgumentException("回复帧头不是 AA");
        byte expect = checksum(raw, 1, raw.length - 2);
        if (expect != raw[raw.length - 1]) throw new IllegalArgumentException("回复校验和错误");
        return new Response(raw[1] & 0xFF, raw[2], Arrays.copyOfRange(raw, 3, raw.length - 1), raw);
    }

    /** 解密 + 解析，失败返回 null（多半是密钥不对） */
    public static Response decryptResponse(byte[] key, byte[] encrypted) {
        try {
            return parseResponse(decrypt(key, encrypted));
        } catch (Exception e) {
            Log.d(TAG, "解密/解析失败: " + e.getMessage());
            return null;
        }
    }
}
