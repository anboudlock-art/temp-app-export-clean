package com.example.blepassword;

/**
 * 老蓝牙锁协议（取自原厂 APP base-blelock: com.chltec.base_blelock.module.protocol.BleLockProtocol）
 *
 * 明文，无加密。校验 = 所有字节异或(XOR)。
 *   手机 -> 锁 (写特征 6E400009)：  0x55 命令 [参数...] 校验
 *   锁 -> 手机 (通知特征 6E400008)：0xAA 命令 [数据...] 校验
 *
 * 关键流程：连接成功后必须先发“连接密码(0x41)”认证，否则所有指令无效（30 秒内）。
 * 出厂默认连接密码 = "000000"（原厂 BleLockEntity.DEFAULT_PASSWORD）。
 *
 *   认证   55 41 00 00 00 14       成功回 AA 41 00 00 00 EB，密码错回 AA 41 EB
 *   开锁   55 10 01 44
 *   关锁   55 10 02 47
 *   查状态 55 30 65                 回 AA 30 01=已锁 / AA 30 00=未锁
 *   查电量 55 20 75                 回 AA 20 电量 校验
 */
public class BleLockSdk {

    public static final byte REQ          = 0x55;
    public static final byte RESP         = (byte) 0xAA;

    public static final byte CMD_OPERATE  = 0x10; // 开/关锁
    public static final byte CMD_ENERGY   = 0x20; // 电量
    public static final byte CMD_STATUS   = 0x30; // 状态
    public static final byte CMD_AUTH     = 0x41; // 连接密码认证

    public static final byte OPEN         = 0x01;
    public static final byte CLOSE        = 0x02;

    /** 异或校验 */
    private static byte xor(byte[] d, int len) {
        byte c = 0;
        for (int i = 0; i < len; i++) c ^= d[i];
        return c;
    }

    /** 认证报文：55 41 pp pp pp cs （pwd 为 6 位数字字符串，默认 "000000"） */
    public static byte[] auth(String pwd) {
        int v = 0;
        try { v = Integer.parseInt(pwd.trim()); } catch (Exception e) { v = 0; }
        byte[] f = new byte[6];
        f[0] = REQ;
        f[1] = CMD_AUTH;
        f[2] = (byte) ((v >> 16) & 0xFF);
        f[3] = (byte) ((v >> 8) & 0xFF);
        f[4] = (byte) (v & 0xFF);
        f[5] = xor(f, 5);
        return f;
    }

    /** 开/关锁报文：55 10 cmd cs */
    public static byte[] operate(byte openOrClose) {
        byte[] f = new byte[4];
        f[0] = REQ;
        f[1] = CMD_OPERATE;
        f[2] = openOrClose;
        f[3] = xor(f, 3);
        return f;
    }

    public static byte[] open()  { return operate(OPEN); }
    public static byte[] close() { return operate(CLOSE); }

    /** 查询报文：55 cmd cs */
    private static byte[] query(byte cmd) {
        byte[] f = new byte[3];
        f[0] = REQ;
        f[1] = cmd;
        f[2] = xor(f, 2);
        return f;
    }

    public static byte[] status()  { return query(CMD_STATUS); }
    public static byte[] energy()  { return query(CMD_ENERGY); }

    /** 校验收到的报文（异或） */
    public static boolean checkResp(byte[] d) {
        if (d == null || d.length < 2) return false;
        byte c = 0;
        for (int i = 0; i < d.length - 1; i++) c ^= d[i];
        return (c == d[d.length - 1]);
    }

    /** 认证是否成功：成功报文长度为 6（AA 41 pp pp pp cs），失败为 3（AA 41 EB） */
    public static boolean isAuthOk(byte[] d) {
        return d != null && d.length >= 6 && d[0] == RESP && d[1] == CMD_AUTH;
    }
}
