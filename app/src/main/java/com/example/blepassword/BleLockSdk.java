package com.example.blepassword;

import android.util.Log;

import java.util.Calendar;
import java.util.Date;

/**
 * 蓝牙锁 SDK —— 基于 4G-BLE-093 固件源码 + Kotlin SDK 逆向工程
 *
 * 协议格式：
 * - 请求：55 [cmdId] [CMD] [payload...] [CS]（变长，最后一字节是 BCC 校验和）
 * - 响应：AA [cmdId] [CMD] [payload...] [CS]（变长，最后一字节是 BCC 校验和）
 * - 所有通信都经过 AES-128-ECB 加密（见 ProtoUtil）
 *
 * 密码编码：每位数字独立一字节（0-9），不是 ASCII
 * 例如：密码 123456 → {0x01, 0x02, 0x03, 0x04, 0x05, 0x06}
 */
public class BleLockSdk {
    private static final String TAG = "BleLockSdk";

    // 指令定义
    public static final byte CMD_SET_TIME = 0x10;
    public static final byte CMD_AUTH_PASSWD = 0x20;
    public static final byte CMD_SET_AUTH_PASSWD = 0x21;
    public static final byte CMD_OPEN_LOCK = 0x30;
    public static final byte CMD_CLOSE_LOCK = 0x31;
    public static final byte CMD_GET_STATUS = 0x40;
    public static final byte CMD_FORCE_SLEEP = 0x50;

    // 结果定义
    public static final byte RESULT_SUCCESS = 0x00;
    public static final byte RESULT_FAILURE = 0x01;

    // 递增的命令 ID
    private static byte cmdId = 0x01;

    private static byte nextCmdId() {
        return ++cmdId;
    }

    // ===================== 请求生成 =====================

    /**
     * 生成时间设置请求（0x10 命令）
     * 格式：55 [cmdId] 10 [年] [月] [日] [时] [分] [秒] [CS]（10 字节）
     *
     * 连接后必须先发这个命令，固件收到后会设置 GetConnectTimeSuccess=true，
     * 并且后续通信从 key1 切换到 key2。
     */
    public static byte[] setTimeRequest(Date time) {
        Calendar c = Calendar.getInstance();
        c.setTime(time);
        return new byte[]{
                ProtoUtil.REQUEST_HEAD,
                nextCmdId(),
                CMD_SET_TIME,
                (byte) (c.get(Calendar.YEAR) - 2000),
                (byte) (c.get(Calendar.MONTH) + 1),
                (byte) c.get(Calendar.DAY_OF_MONTH),
                (byte) c.get(Calendar.HOUR_OF_DAY),
                (byte) c.get(Calendar.MINUTE),
                (byte) c.get(Calendar.SECOND),
                0x00  // 校验和占位，发送前由 ProtoUtil.encryptRequest 填入
        };
    }

    /**
     * 生成密码验证请求（0x20 命令）
     * 格式：55 [cmdId] 20 [P0] [P1] [P2] [P3] [P4] [P5] [CS]（10 字节）
     *
     * 密码编码：每位数字 0-9 独立一字节（不是 ASCII！）
     */
    public static byte[] authPasswdRequest(int passwd) {
        if (passwd < 0 || passwd > 999999) {
            throw new IllegalArgumentException("密码范围错误 (0-999999)");
        }
        return new byte[]{
                ProtoUtil.REQUEST_HEAD,
                nextCmdId(),
                CMD_AUTH_PASSWD,
                (byte) (passwd / 100000 % 10),
                (byte) (passwd / 10000 % 10),
                (byte) (passwd / 1000 % 10),
                (byte) (passwd / 100 % 10),
                (byte) (passwd / 10 % 10),
                (byte) (passwd % 10),
                0x00  // 校验和占位
        };
    }

    /**
     * 生成密码设置请求（0x21 命令）
     * 格式：55 [cmdId] 21 [P0] [P1] [P2] [P3] [P4] [P5] [CS]（10 字节）
     */
    public static byte[] setAuthPasswdRequest(int passwd) {
        if (passwd < 0 || passwd > 999999) {
            throw new IllegalArgumentException("密码范围错误 (0-999999)");
        }
        return new byte[]{
                ProtoUtil.REQUEST_HEAD,
                nextCmdId(),
                CMD_SET_AUTH_PASSWD,
                (byte) (passwd / 100000 % 10),
                (byte) (passwd / 10000 % 10),
                (byte) (passwd / 1000 % 10),
                (byte) (passwd / 100 % 10),
                (byte) (passwd / 10 % 10),
                (byte) (passwd % 10),
                0x00  // 校验和占位
        };
    }

    /**
     * 生成开锁请求（0x30 命令）
     * 格式：55 [cmdId] 30 [sleepMode] [CS]（5 字节）
     */
    public static byte[] openLockRequest(byte sleepMode) {
        return new byte[]{
                ProtoUtil.REQUEST_HEAD,
                nextCmdId(),
                CMD_OPEN_LOCK,
                sleepMode,
                0x00
        };
    }

    /**
     * 生成关锁请求（0x31 命令）
     */
    public static byte[] closeLockRequest(byte sleepMode) {
        return new byte[]{
                ProtoUtil.REQUEST_HEAD,
                nextCmdId(),
                CMD_CLOSE_LOCK,
                sleepMode,
                0x00
        };
    }

    /**
     * 生成状态查询请求（0x40 命令）
     */
    public static byte[] getStatusRequest(byte sleepMode) {
        return new byte[]{
                ProtoUtil.REQUEST_HEAD,
                nextCmdId(),
                CMD_GET_STATUS,
                sleepMode,
                0x00
        };
    }

    /**
     * 生成强制休眠请求（0x50 命令）
     */
    public static byte[] forceSleepRequest() {
        return new byte[]{
                ProtoUtil.REQUEST_HEAD,
                nextCmdId(),
                CMD_FORCE_SLEEP,
                0x00
        };
    }

    // ===================== 响应解析 =====================

    /**
     * 解析解密后的响应数据
     *
     * @param data 解密后的有效数据（已去掉 0xFB 帧头和 0xFC 填充）
     * @return Response 对象
     */
    public static Response parseResponse(byte[] data) {
        if (data == null || data.length < 4) {
            Log.e(TAG, "响应数据不足: " + (data != null ? data.length : "null"));
            return new Response(false, "响应数据长度不足");
        }

        if (data[0] != ProtoUtil.RESPONSE_HEAD) {
            Log.e(TAG, "响应帧头错误: 0x" + String.format("%02X", data[0] & 0xFF));
            return new Response(false, "响应帧头错误");
        }

        // 校验和验证
        if (!ProtoUtil.verifyCheckCode(data)) {
            Log.e(TAG, "校验和错误");
            return new Response(false, "校验和错误");
        }

        byte responseCmdId = data[1];
        byte cmd = data[2];

        // payload 是 data[3..n-2]（去掉 header, cmdId, cmd, 和最后的 checksum）
        int payloadLen = data.length - 4;
        byte[] payload = new byte[Math.max(payloadLen, 0)];
        if (payloadLen > 0) {
            System.arraycopy(data, 3, payload, 0, payloadLen);
        }

        Log.d(TAG, "响应解析: cmdId=" + (responseCmdId & 0xFF) +
                ", cmd=0x" + String.format("%02X", cmd & 0xFF) +
                ", payload=" + HexStringUtils.bytesToHexString(payload));

        return new Response(true, "", data, responseCmdId, cmd, payload);
    }

    /**
     * 响应数据类
     */
    public static class Response {
        public final boolean success;
        public final String error;
        public final byte[] data;
        public final byte cmdId;
        public final byte cmd;
        public final byte[] payload;

        public Response(boolean success, String error) {
            this(success, error, new byte[0], (byte) 0, (byte) 0, new byte[0]);
        }

        public Response(boolean success, String error, byte[] data,
                        byte cmdId, byte cmd, byte[] payload) {
            this.success = success;
            this.error = error;
            this.data = data;
            this.cmdId = cmdId;
            this.cmd = cmd;
            this.payload = payload;
        }

        /** 获取密码验证结果 */
        public Byte getAuthPasswdResult() {
            if (cmd == CMD_AUTH_PASSWD && payload.length >= 1) {
                return payload[0] == 0x00 ? RESULT_SUCCESS : RESULT_FAILURE;
            }
            return null;
        }

        /** 获取密码设置结果 */
        public Byte getSetAuthPasswdResult() {
            if (cmd == CMD_SET_AUTH_PASSWD && payload.length >= 1) {
                return payload[0] == 0x00 ? RESULT_SUCCESS : RESULT_FAILURE;
            }
            return null;
        }

        /** 获取时间设置结果 */
        public boolean isSetTimeSuccess() {
            return cmd == CMD_SET_TIME && success;
        }
    }
}
