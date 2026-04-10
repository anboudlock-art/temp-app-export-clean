package com.example.blepassword;

import android.util.Log;

import java.util.Calendar;
import java.util.Date;

/**
 * 蓝牙锁 SDK - 最终修复版本（基于SYD8811_FINAL_REAL_FIX.bin固件分析）
 * 
 * 固件功能确认：
 * - 支持0x21密码修改命令（REAL_0x21_FUNCTION）
 * - 密码编码：ASCII字符编码
 * - 校验和算法：累加+异或双重校验 checksum = XOR(all bytes) + SUM(all bytes)
 * - 两步验证：先0x20验证旧密码 → 再0x21修改新密码
 * - 默认密码：000000
 * 
 * 协议格式：
 * - 命令：55 01 CMD [DATA...] CS（固定10字节）
 * - 响应：AA 01 CMD ST CS（固定5字节）
 */
public class BleLockSdk {
    private static final String TAG = "BleLockSdk";

    // 协议常量
    private static final byte REQUEST_HEAD = 0x55;  // 命令起始字节
    private static final byte RESPONSE_HEAD = (byte) 0xAA;  // 响应起始字节
    private static final byte DATA_TYPE = 0x01;  // 数据类型（固定）

    // 指令定义
    public static final byte CMD_SET_TIME = 0x10;           // 时间设置
    public static final byte CMD_AUTH_PASSWD = 0x20;        // 密码验证
    public static final byte CMD_SET_AUTH_PASSWD = 0x21;    // 密码设置
    public static final byte CMD_OPEN_LOCK = 0x30;          // 开锁
    public static final byte CMD_CLOSE_LOCK = 0x31;         // 关锁
    public static final byte CMD_GET_STATUS = 0x40;         // 状态查询
    public static final byte CMD_FORCE_SLEEP = 0x50;        // 强制休眠

    // 结果定义
    public static final byte RESULT_SUCCESS = 0x00;         // 成功
    public static final byte RESULT_FAILURE = 0x01;         // 失败
    public static final byte STATUS_CLOSED = 0x00;          // 上锁/关闭
    public static final byte STATUS_OPENED = 0x01;          // 未上锁/打开

    // ===================== 校验和计算（基于固件分析）=====================

    /**
     * 计算校验和 - 累加+异或双重校验（SYD8811固件确认算法）
     * 公式：checksum = XOR(all bytes) + SUM(all bytes)
     */
    public static byte calculateChecksum(byte[] data, int length) {
        if (data == null || data.length == 0) return 0;

        int sum = 0;
        byte xor_val = 0;
        
        for (int i = 0; i < length && i < data.length; i++) {
            sum += data[i] & 0xFF;  // 使用 & 0xFF 确保无符号累加
            xor_val ^= data[i];
        }
        
        // 双重校验：累加和异或相加
        int checksum = (xor_val & 0xFF) + (sum & 0xFF);
        return (byte) (checksum & 0xFF);  // 取低8位
    }

    // ===================== 密码编码（基于固件分析）=====================

    /**
     * 编码密码字符串为字节数组 - ASCII编码（固件确认）
     * @param password 6位数字字符串
     * @return 6字节数组（ASCII编码）
     */
    private static byte[] encodePassword(String password) {
        if (password == null || password.length() != 6) {
            throw new IllegalArgumentException("密码必须是6位");
        }

        byte[] result = new byte[6];
        
        // ASCII编码：每个字节是字符的 ASCII 值
        // 例如：'1' -> 0x31, '2' -> 0x32, '0' -> 0x30
        for (int i = 0; i < 6; i++) {
            result[i] = (byte) password.charAt(i);
        }
        
        log("密码编码（ASCII）: " + password + " -> " + HexStringUtils.bytesToHexString(result));
        return result;
    }

    // ===================== Request =====================

    /**
     * 生成时间设置请求
     */
    public static byte[] setTimeRequest(Date time) {
        Calendar c = Calendar.getInstance();
        c.setTime(time);
        byte[] request = new byte[10];
        request[0] = REQUEST_HEAD;
        request[1] = DATA_TYPE;
        request[2] = CMD_SET_TIME;
        request[3] = (byte) (c.get(Calendar.YEAR) - 2000);
        request[4] = (byte) (c.get(Calendar.MONTH) + 1);
        request[5] = (byte) c.get(Calendar.DAY_OF_MONTH);
        request[6] = (byte) c.get(Calendar.HOUR_OF_DAY);
        request[7] = (byte) c.get(Calendar.MINUTE);
        request[8] = 0x00;  // 填充
        request[9] = calculateChecksum(request, 9);
        return request;
    }

    /**
     * 生成密码验证请求（0x20命令）
     * 命令格式：55 01 20 [密码6字节 ASCII] CS（固定10字节）
     *
     * 字节布局：
     *   [0] 0x55 帧头
     *   [1] 0x01 数据类型
     *   [2] 0x20 命令码
     *   [3..8] 6 字节 ASCII 密码
     *   [9] 校验和
     *
     * 注意：6 字节密码完整占用 [3..8]，中间没有填充字节。
     */
    public static byte[] authPasswdRequest(String password) {
        if (password == null || password.length() != 6) {
            throw new IllegalArgumentException("密码必须是6位");
        }

        byte[] request = new byte[10];

        // 帧头
        request[0] = REQUEST_HEAD;          // 0x55

        // 数据类型
        request[1] = DATA_TYPE;             // 0x01

        // 命令码
        request[2] = CMD_AUTH_PASSWD;       // 0x20

        // 密码编码（ASCII 编码，6 字节放在 [3..8]）
        byte[] encodedPassword = encodePassword(password);
        System.arraycopy(encodedPassword, 0, request, 3, 6);

        // 校验和（累加+异或双重校验，基于前 9 字节）
        request[9] = calculateChecksum(request, 9);

        log("生成验证密码请求: " + HexStringUtils.bytesToHexString(request));
        log("  帧头: 0x" + String.format("%02X", request[0]));
        log("  类型: 0x" + String.format("%02X", request[1]));
        log("  命令: 0x" + String.format("%02X", request[2]));
        log("  密码(ASCII): " + HexStringUtils.bytesToHexString(request, 3, 6));
        log("  校验和: 0x" + String.format("%02X", request[9]));

        return request;
    }

    /**
     * 生成密码验证请求（整数版本，兼容旧接口）
     */
    public static byte[] authPasswdRequest(int passwd) {
        if (passwd < 0 || passwd > 999999) {
            throw new IllegalArgumentException("密码范围错误 (0-999999)");
        }
        // 转换为6位字符串，带前导零
        String passwordStr = String.format("%06d", passwd);
        return authPasswdRequest(passwordStr);
    }

    /**
     * 生成密码设置请求（0x21命令）
     * 命令格式：55 01 21 [密码6字节 ASCII] CS（固定10字节）
     *
     * 字节布局：
     *   [0] 0x55 帧头
     *   [1] 0x01 数据类型
     *   [2] 0x21 命令码
     *   [3..8] 6 字节 ASCII 新密码
     *   [9] 校验和
     *
     * 与 0x20 验证命令结构完全一致，只是命令码不同。
     */
    public static byte[] setAuthPasswdRequest(String password) {
        if (password == null || password.length() != 6) {
            throw new IllegalArgumentException("密码必须是6位");
        }

        byte[] request = new byte[10];

        // 帧头
        request[0] = REQUEST_HEAD;          // 0x55

        // 数据类型
        request[1] = DATA_TYPE;             // 0x01

        // 命令码
        request[2] = CMD_SET_AUTH_PASSWD;   // 0x21

        // 密码编码（ASCII 编码，6 字节完整放在 [3..8]）
        byte[] encodedPassword = encodePassword(password);
        System.arraycopy(encodedPassword, 0, request, 3, 6);

        // 校验和（累加+异或双重校验，基于前 9 字节）
        request[9] = calculateChecksum(request, 9);

        log("生成修改密码请求: " + HexStringUtils.bytesToHexString(request));
        log("  帧头: 0x" + String.format("%02X", request[0]));
        log("  类型: 0x" + String.format("%02X", request[1]));
        log("  命令: 0x" + String.format("%02X", request[2]));
        log("  新密码(ASCII): " + HexStringUtils.bytesToHexString(request, 3, 6));
        log("  校验和: 0x" + String.format("%02X", request[9]));

        return request;
    }

    /**
     * 生成密码设置请求（整数版本，兼容旧接口）
     */
    public static byte[] setAuthPasswdRequest(int passwd) {
        if (passwd < 0 || passwd > 999999) {
            throw new IllegalArgumentException("密码范围错误 (0-999999)");
        }
        // 转换为6位字符串，带前导零
        String passwordStr = String.format("%06d", passwd);
        return setAuthPasswdRequest(passwordStr);
    }

    /**
     * 生成开锁请求
     */
    public static byte[] openLockRequest(byte sleepMode) {
        byte[] request = new byte[10];
        request[0] = REQUEST_HEAD;
        request[1] = DATA_TYPE;
        request[2] = CMD_OPEN_LOCK;
        request[3] = sleepMode;
        request[9] = calculateChecksum(request, 9);
        return request;
    }

    /**
     * 生成关锁请求
     */
    public static byte[] closeLockRequest(byte sleepMode) {
        byte[] request = new byte[10];
        request[0] = REQUEST_HEAD;
        request[1] = DATA_TYPE;
        request[2] = CMD_CLOSE_LOCK;
        request[3] = sleepMode;
        request[9] = calculateChecksum(request, 9);
        return request;
    }

    /**
     * 生成状态查询请求
     */
    public static byte[] getStatusRequest(byte sleepMode) {
        byte[] request = new byte[10];
        request[0] = REQUEST_HEAD;
        request[1] = DATA_TYPE;
        request[2] = CMD_GET_STATUS;
        request[3] = sleepMode;
        request[9] = calculateChecksum(request, 9);
        return request;
    }

    /**
     * 生成强制休眠请求
     */
    public static byte[] forceSleepRequest() {
        byte[] request = new byte[10];
        request[0] = REQUEST_HEAD;
        request[1] = DATA_TYPE;
        request[2] = CMD_FORCE_SLEEP;
        request[9] = calculateChecksum(request, 9);
        return request;
    }

    // ===================== Response =====================

    /**
     * 解析响应（SYD8811协议）
     * 响应格式：AA 01 CMD ST CS（固定5字节）
     */
    public static Response parseResponse(byte[] responseData) {
        log("================== 解析响应 ==================");

        // 先做 null 检查，避免后续访问崩溃
        if (responseData == null) {
            log("错误: 响应数据为空 (null)");
            return new Response(false, "响应数据为空", null, (byte) 0, (byte) 0);
        }

        log("响应数据: " + HexStringUtils.bytesToHexString(responseData));
        log("数据长度: " + responseData.length + " 字节");

        // 检查数据长度（至少需要5字节：帧头+类型+命令+状态+校验和）
        if (responseData.length < 5) {
            log("错误: 响应数据长度不足，至少需要 5 字节，实际: " + responseData.length);
            return new Response(false, "响应数据长度不足", null, (byte) 0, (byte) 0);
        }

        // 检查帧头
        log("响应帧头: 0x" + String.format("%02X", responseData[0]));
        if (responseData[0] != RESPONSE_HEAD) {
            log("错误: 响应帧头错误，期望: 0xAA，实际: 0x" + String.format("%02X", responseData[0]));
            return new Response(false, "响应帧头错误", null, (byte) 0, (byte) 0);
        }

        // 检查类型字段
        log("类型字段: 0x" + String.format("%02X", responseData[1]));
        if (responseData[1] != DATA_TYPE) {
            log("警告: 类型字段不是 0x01，实际: 0x" + String.format("%02X", responseData[1]));
        }

        byte cmd = responseData[2];
        byte status = responseData[3];
        byte checksum = responseData[4];

        // 计算校验和（累加+异或双重校验）
        int sum = (responseData[0] & 0xFF) + (responseData[1] & 0xFF) + 
                  (responseData[2] & 0xFF) + (responseData[3] & 0xFF);
        byte xor_val = (byte) (responseData[0] ^ responseData[1] ^ 
                             responseData[2] ^ responseData[3]);
        int calculatedChecksum = (xor_val & 0xFF) + (sum & 0xFF);
        
        log("计算校验和（累加）: 0x" + String.format("%02X", sum & 0xFF));
        log("计算校验和（异或）: 0x" + String.format("%02X", xor_val & 0xFF));
        log("计算校验和（双重）: 0x" + String.format("%02X", calculatedChecksum & 0xFF));
        log("实际校验和: 0x" + String.format("%02X", checksum));

        boolean checksumValid = (calculatedChecksum & 0xFF) == (checksum & 0xFF);
        
        if (!checksumValid) {
            log("警告: 响应校验和不匹配");
        } else {
            log("校验和验证通过");
        }

        log("命令: 0x" + String.format("%02X", cmd));
        log("状态: 0x" + String.format("%02X", status));
        log("==============================================");

        return new Response(checksumValid, checksumValid ? "" : "校验和错误", responseData, cmd, status);
    }

    /**
     * 响应数据类
     */
    public static class Response {
        public boolean success;
        public String error;
        public byte[] data;
        public byte cmd;
        public byte status;

        public Response(boolean success, String error, byte[] data, byte cmd, byte status) {
            this.success = success;
            this.error = error;
            this.data = data;
            this.cmd = cmd;
            this.status = status;
        }

        public Byte getAuthPasswdResult() {
            if (cmd == CMD_AUTH_PASSWD) {
                return status == RESULT_SUCCESS ? RESULT_SUCCESS : RESULT_FAILURE;
            }
            return null;
        }

        public Byte getSetAuthPasswdResult() {
            if (cmd == CMD_SET_AUTH_PASSWD) {
                return status == RESULT_SUCCESS ? RESULT_SUCCESS : RESULT_FAILURE;
            }
            return null;
        }

        public Byte getStatusResult() {
            if (cmd == CMD_GET_STATUS) {
                return status;
            }
            return null;
        }

        public Byte getOpenLockResult() {
            if (cmd == CMD_OPEN_LOCK) {
                return status;
            }
            return null;
        }

        public Byte getCloseLockResult() {
            if (cmd == CMD_CLOSE_LOCK) {
                return status;
            }
            return null;
        }
    }

    private static void log(String message) {
        Log.d(TAG, message);
    }
}
