package com.xiaohua.performancetesting.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Md5Util {

    /**
     * 按 UTF-8 取字节计算 MD5，必须与前端 api.js 的 utf8Encode + md5 逐字节一致。
     *
     * <p>字符集不能省略：本项目跑 Java 17，中文 Windows 上平台默认字符集是 GBK，
     * 少了这个参数，含中文的口令在服务端和浏览器会算出两个不同摘要，
     * 表现为“密码明明对，却永远登录不上”。
     */
    public static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
