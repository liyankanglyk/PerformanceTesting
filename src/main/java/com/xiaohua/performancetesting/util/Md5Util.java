package com.xiaohua.performancetesting.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Md5Util {

    /**
     * 使用 UTF-8 计算 MD5，必须与前端 js 的 utf8Encode + MD5 保持一致。
     * 早期实现使用平台默认字符集（Windows 上为 GBK），
     * 导致中文密码在服务端算出的摘要与客户端不一致，登录永远失败。
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
