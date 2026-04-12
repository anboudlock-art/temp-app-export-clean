package com.example.blepassword;

import android.util.Log;

import java.util.Calendar;
import java.util.Date;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 蓝牙锁协议工具类 —— 基于 4G-BLE-093 固件源码逆向工程
 *
 * 协议要点：
 * - 加密算法：AES-128-ECB（无 IV）
 * - 加密帧格式：[0xFB] [长度] [原始数据...] [0xFC 填充到 16 字节]
 * - 校验和：BCC（简单加法累加），只算 bytes[1..n-2]（跳过帧头和校验位）
 * - 密钥：基于 MAC 地址（key1）或 MAC+时间（key2），16 字节
 */
public class ProtoUtil {
    private static final String TAG = "ProtoUtil";

    /** 密文帧头 */
    public static final byte CIPHERTEXT_HEAD = (byte) 0xFB;

    /** 密文填充字节 */
    public static final byte CIPHERTEXT_FILL = (byte) 0xFC;

    /** 请求帧头 */
    public static final byte REQUEST_HEAD = 0x55;

    /** 响应帧头 */
    public static final byte RESPONSE_HEAD = (byte) 0xAA;

    // ===================== 校验和 =====================

    /**
     * 计算 BCC 校验和（简单加法累加）
     * 注意：只计算 data[1..data.length-2]，跳过帧头 [0] 和校验位 [last]
     *
     * @param data 完整的请求/响应数据（含帧头和校验位占位）
     * @return 校验和字节
     */
    public static byte checkCode(byte[] data) {
        int bcc = 0;
        // 跳过 [0]（帧头 0x55 或 0xAA），跳过 [last]（校验位本身）
        for (int i = 1; i < data.length - 1; i++) {
            bcc += data[i] & 0xFF;
        }
        return (byte) (bcc & 0xFF);
    }

    /**
     * 验证响应数据的校验和是否正确
     */
    public static boolean verifyCheckCode(byte[] data) {
        if (data == null || data.length < 3) return false;
        return checkCode(data) == data[data.length - 1];
    }

    // ===================== AES 加密/解密 =====================

    /**
     * AES-128-ECB 加密（单块，16 字节）
     *
     * @param key       16 字节密钥
     * @param plaintext 16 字节明文
     * @return 16 字节密文，失败返回 null
     */
    public static byte[] aesEncryptEcb(byte[] key, byte[] plaintext) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            Log.e(TAG, "AES 加密失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * AES-128-ECB 解密（单块，16 字节）
     *
     * @param key        16 字节密钥
     * @param ciphertext 16 字节密文
     * @return 16 字节明文，失败返回 null
     */
    public static byte[] aesDecryptEcb(byte[] key, byte[] ciphertext) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            Log.e(TAG, "AES 解密失败: " + e.getMessage());
            return null;
        }
    }

    // ===================== 帧封装/解封 =====================

    /**
     * 加密请求数据：封帧 + AES 加密
     *
     * 步骤：
     * 1. 先计算校验码填入最后一字节
     * 2. 组装加密帧：[0xFB] [长度] [原始数据] [0xFC 填充]（共 16 字节）
     * 3. AES-128-ECB 加密
     *
     * @param key     16 字节 AES 密钥
     * @param request 原始请求数据（含帧头 0x55、cmdId、cmd、payload、校验位占位）
     * @return 16 字节加密后的数据，失败返回 null
     */
    public static byte[] encryptRequest(byte[] key, byte[] request) {
        if (request == null || request.length > 14) {
            Log.e(TAG, "请求数据过长，最大 14 字节（16 - 2 帧头/长度字节）");
            return null;
        }

        // 1. 计算校验码
        request[request.length - 1] = checkCode(request);

        // 2. 组装加密帧
        byte[] frame = new byte[16];
        frame[0] = CIPHERTEXT_HEAD;               // 0xFB
        frame[1] = (byte) request.length;          // 原始数据长度
        System.arraycopy(request, 0, frame, 2, request.length);  // 原始数据
        // 剩余字节用 0xFC 填充
        for (int i = 2 + request.length; i < 16; i++) {
            frame[i] = CIPHERTEXT_FILL;            // 0xFC
        }

        Log.d(TAG, "加密前帧: " + HexStringUtils.bytesToHexString(frame));
        Log.d(TAG, "密钥: " + HexStringUtils.bytesToHexString(key));

        // 3. AES 加密
        byte[] encrypted = aesEncryptEcb(key, frame);
        if (encrypted != null) {
            Log.d(TAG, "加密后: " + HexStringUtils.bytesToHexString(encrypted));
        }
        return encrypted;
    }

    /**
     * 解密响应数据：AES 解密 + 解帧
     *
     * 步骤：
     * 1. AES-128-ECB 解密 16 字节
     * 2. 检查帧头 [0] == 0xFB
     * 3. 读取长度 [1]
     * 4. 提取有效数据 [2..2+length-1]
     *
     * @param key            16 字节 AES 密钥
     * @param encryptedData  16 字节加密数据
     * @return 解密后的有效数据（去掉帧头和填充），失败返回 null
     */
    public static byte[] decryptResponse(byte[] key, byte[] encryptedData) {
        if (encryptedData == null || encryptedData.length != 16) {
            Log.e(TAG, "加密数据长度错误，期望 16 字节，实际 " +
                    (encryptedData != null ? encryptedData.length : "null"));
            return null;
        }

        // 1. AES 解密
        byte[] decrypted = aesDecryptEcb(key, encryptedData);
        if (decrypted == null) {
            return null;
        }

        Log.d(TAG, "解密后帧: " + HexStringUtils.bytesToHexString(decrypted));

        // 2. 检查帧头
        if (decrypted[0] != CIPHERTEXT_HEAD) {
            Log.e(TAG, "帧头错误: 期望 0xFB，实际 0x" +
                    String.format("%02X", decrypted[0] & 0xFF));
            return null;
        }

        // 3. 读取长度
        int len = decrypted[1] & 0xFF;
        if (len > 14 || len < 1) {
            Log.e(TAG, "数据长度异常: " + len);
            return null;
        }

        // 4. 提取有效数据
        byte[] payload = new byte[len];
        System.arraycopy(decrypted, 2, payload, 0, len);

        Log.d(TAG, "解密后有效数据: " + HexStringUtils.bytesToHexString(payload));
        return payload;
    }

    // ===================== 密钥生成 =====================

    /**
     * 生成 key1（静态密钥，基于 MAC 地址）
     *
     * 格式：[MAC倒序 6字节] [0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA]
     *
     * @param macAddress MAC 地址字符串，格式 "FC:2C:B1:71:38:4B"
     * @return 16 字节 key1
     */
    public static byte[] generateKey1(String macAddress) {
        byte[] macBytes = parseMacAddress(macAddress);
        byte[] key1 = new byte[16];

        // MAC 地址倒序放入 key1[0..5]
        for (int i = 0; i < 6; i++) {
            key1[i] = macBytes[5 - i];
        }

        // key1[6] = 0x11, key1[7] = 0x22, ..., key1[15] = 0xAA
        key1[6] = 0x11;
        for (int i = 0; i < 9; i++) {
            key1[i + 7] = (byte) (key1[i + 6] + 0x11);
        }

        Log.d(TAG, "Key1: " + HexStringUtils.bytesToHexString(key1));
        return key1;
    }

    /**
     * 生成 key2（动态密钥，基于 key1 + 当前时间）
     *
     * 格式：key1 的复制，最后 6 字节替换为 [年-2000, 月, 日, 时, 分, 秒]
     *
     * @param key1 key1（16 字节）
     * @param time 当前时间
     * @return 16 字节 key2
     */
    public static byte[] generateKey2(byte[] key1, Date time) {
        byte[] key2 = key1.clone();
        Calendar c = Calendar.getInstance();
        c.setTime(time);

        key2[10] = (byte) (c.get(Calendar.YEAR) - 2000);
        key2[11] = (byte) (c.get(Calendar.MONTH) + 1);
        key2[12] = (byte) c.get(Calendar.DAY_OF_MONTH);
        key2[13] = (byte) c.get(Calendar.HOUR_OF_DAY);
        key2[14] = (byte) c.get(Calendar.MINUTE);
        key2[15] = (byte) c.get(Calendar.SECOND);

        Log.d(TAG, "Key2: " + HexStringUtils.bytesToHexString(key2));
        return key2;
    }

    /**
     * 解析 MAC 地址字符串为 6 字节数组
     *
     * @param mac 格式 "FC:2C:B1:71:38:4B" 或 "FC2CB171384B"
     * @return 6 字节 MAC
     */
    public static byte[] parseMacAddress(String mac) {
        String cleanMac = mac.replace(":", "").replace("-", "");
        byte[] result = new byte[6];
        for (int i = 0; i < 6; i++) {
            result[i] = (byte) Integer.parseInt(cleanMac.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}
