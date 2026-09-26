package com.xiaohua.performancetesting.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 服务端 MD5 必须与前端 js/api.js 的 md5()（UTF-8 编码）一致，
 * 否则中文密码在“管理端新增用户 -> 该用户登录”链路上永远失败。
 */
class Md5UtilTest {

    private static final String TS = "1700000000000";

    @Test
    @DisplayName("ASCII 密码摘要与前端一致")
    void asciiMatchesFrontend() {
        // 前端 api.js md5('123456' + ts) 实测值
        assertEquals("28927441b95dfcb83dc3e9ef96555415", Md5Util.md5("123456" + TS));
    }

    @Test
    @DisplayName("中文密码摘要使用 UTF-8，与前端一致")
    void chineseMatchesFrontend() {
        // 前端 api.js md5('密码123' + ts) 实测值
        assertEquals("7b716e06de34bbf20ca06d3286be7133", Md5Util.md5("密码123" + TS));
    }

    @Test
    @DisplayName("回归保护：平台默认字符集（Windows=GBK）会算出不同摘要 -> 旧实现 bug")
    void platformDefaultCharsetWouldBreakLogin() throws Exception {
        String input = "密码123" + TS;
        byte[] utf8 = input.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Charset gbk = Charset.isSupported("GBK") ? Charset.forName("GBK") : Charset.defaultCharset();
        String withDefault = hex(MessageDigest.getInstance("MD5").digest(input.getBytes(gbk)));
        String withUtf8 = hex(MessageDigest.getInstance("MD5").digest(utf8));
        assertNotEquals(withUtf8, withDefault, "GBK 与 UTF-8 摘要必须不同，否则本用例无法证明修复有效");
        assertEquals(withUtf8, Md5Util.md5(input), "Md5Util 必须固定使用 UTF-8");
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
