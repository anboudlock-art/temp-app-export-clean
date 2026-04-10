package com.example.blepassword;

/**
 * 十六进制字符串工具类（纯 Java 版本）
 */
public class HexStringUtils {
    
    /**
     * 字节数组转十六进制字符串
     */
    public static String bytesToHexString(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(b & 0xFF);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    /**
     * 字节数组部分转十六进制字符串（带偏移和长度）
     */
    public static String bytesToHexString(byte[] bytes, int offset, int length) {
        if (bytes == null) {
            return "";
        }
        
        if (offset < 0 || length <= 0 || offset + length > bytes.length) {
            return bytesToHexString(bytes);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < offset + length; i++) {
            String hex = Integer.toHexString(bytes[i] & 0xFF);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }
    
    /**
     * 十六进制字符串转字节数组
     */
    public static byte[] hexStringToBytes(String hexString) {
        if (hexString == null || hexString.isEmpty()) {
            return new byte[0];
        }
        
        hexString = hexString.trim();
        if (hexString.length() % 2 != 0) {
            hexString = "0" + hexString;
        }
        
        int len = hexString.length() / 2;
        byte[] data = new byte[len];
        for (int i = 0; i < len; i++) {
            int high = Character.digit(hexString.charAt(i * 2), 16);
            int low = Character.digit(hexString.charAt(i * 2 + 1), 16);
            data[i] = (byte) ((high << 4) | low);
        }
        return data;
    }
    
    /**
     * int 转 byte 数组（大端序）
     */
    public static byte[] intToBytes(int value) {
        return new byte[] {
            (byte) (value >> 24),
            (byte) (value >> 16),
            (byte) (value >> 8),
            (byte) value
        };
    }
    
    /**
     * byte 数组转 int（大端序）
     */
    public static int bytesToInt(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return 0;
        }
        return (bytes[0] & 0xFF) << 24 |
               (bytes[1] & 0xFF) << 16 |
               (bytes[2] & 0xFF) << 8 |
               (bytes[3] & 0xFF);
    }
}
