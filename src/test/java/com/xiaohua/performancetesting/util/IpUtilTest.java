package com.xiaohua.performancetesting.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 日志里的 IP 要求是可读的 IPv4，并且不能是客户端自己编的。
 * 本地跑 Tomcat 双栈时 getRemoteAddr() 给出 0:0:0:0:0:0:0:1，既难看又和文档示例不一致。
 */
class IpUtilTest {

    private MockHttpServletRequest req(String remote, String xff, String realIp) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(remote);
        if (xff != null) r.addHeader("X-Forwarded-For", xff);
        if (realIp != null) r.addHeader("X-Real-IP", realIp);
        return r;
    }

    @Test
    @DisplayName("IPv6 回环归一化成 127.0.0.1")
    void loopbackBecomesIpv4() {
        assertEquals("127.0.0.1", IpUtil.clientIp(req("0:0:0:0:0:0:0:1", null, null)));
        assertEquals("127.0.0.1", IpUtil.clientIp(req("::1", null, null)));
    }

    @Test
    @DisplayName("IPv4 映射地址去掉 ::ffff: 前缀")
    void mappedV4() {
        assertEquals("10.1.2.3", IpUtil.clientIp(req("::ffff:10.1.2.3", null, null)));
    }

    @Test
    @DisplayName("本身是 IPv4 时原样保留")
    void plainIpv4() {
        assertEquals("203.0.113.7", IpUtil.clientIp(req("203.0.113.7", null, null)));
    }

    @Test
    @DisplayName("默认不信任代理头：客户端自造的 XFF / X-Real-IP 一律无效")
    void forwardedHeadersIgnoredByDefault() {
        MockHttpServletRequest r = req("203.0.113.77", "1.1.1.1", "2.2.2.2");
        assertEquals("203.0.113.77", IpUtil.clientIp(r));          // 单参重载 = 不信任
        assertEquals("203.0.113.77", IpUtil.clientIp(r, false));
        assertEquals("1.1.1.1", IpUtil.clientIp(r, true));         // 显式开启才采信
    }

    @Test
    @DisplayName("开启信任后：取 X-Forwarded-For 第一跳，跳过 unknown")
    void forwardedForFirstHop() {
        assertEquals("203.0.113.9", IpUtil.clientIp(req("10.0.0.1", "203.0.113.9, 10.0.0.2", null), true));
        assertEquals("203.0.113.9", IpUtil.clientIp(req("10.0.0.1", "unknown, 203.0.113.9", null), true));
        HttpServletRequest r = req("10.0.0.1", null, "203.0.113.55");
        assertEquals("203.0.113.55", IpUtil.clientIp(r, true));
    }

    @Test
    @DisplayName("代理头里的脏值不会原样进日志（日志注入），且会回落到 socket 地址")
    void headerInjectionIsRejected() {
        MockHttpServletRequest dirty = req("10.0.0.1", "1.1.1.1\n2026-05-22 00:00:00 [ADMIN] 伪造了一行", null);
        String ip = IpUtil.clientIp(dirty, true);
        assertFalse(ip.contains("\n"), "换行不能进日志: " + ip);
        assertEquals("10.0.0.1", ip);
        // 合法跳段仍优先，脏跳段不影响
        assertEquals("203.0.113.9", IpUtil.clientIp(req("10.0.0.1", "203.0.113.9, bad hop", null), true));
        assertEquals("10.0.0.1", IpUtil.clientIp(req("10.0.0.1", "'; DROP TABLE user; --", null), true));
        assertEquals("10.0.0.1", IpUtil.clientIp(req("10.0.0.1", "1.2.3.4:80", null), true));
    }

    @Test
    @DisplayName("带端口写法与 IPv6 区域标识能解析")
    void bracketAndZone() {
        assertEquals("127.0.0.1", IpUtil.clientIp(req("[::1]:8080", null, null)));
        assertEquals("fe80::1", IpUtil.clientIp(req("fe80::1%eth0", null, null)));
    }

    @Test
    @DisplayName("真 IPv6 外网地址保留紧凑写法（不伪造 IPv4）")
    void realIpv6Kept() {
        assertEquals("2408:8000:1234::5678", IpUtil.clientIp(req("2408:8000:1234::5678", null, null)));
    }

    @Test
    @DisplayName("空值与非法值统一为 unknown")
    void unknownFallback() {
        assertEquals(IpUtil.UNKNOWN, IpUtil.clientIp(null));
        assertEquals(IpUtil.UNKNOWN, IpUtil.clientIp(req("not-an-ip", null, null)));
        assertEquals(IpUtil.UNKNOWN, IpUtil.clientIp(req("999.1.1.1", null, null)));
        assertEquals(IpUtil.UNKNOWN, IpUtil.clientIp(req("1.2.3.4:80", null, null)));
    }
}
