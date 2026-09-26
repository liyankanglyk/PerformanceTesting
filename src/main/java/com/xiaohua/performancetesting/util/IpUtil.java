package com.xiaohua.performancetesting.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 解析。
 *
 * <p>日志里的 IP 要求是 IPv4 可读形式：本项目跑在本地/内网，Tomcat 默认双栈监听，
 * 浏览器用 localhost 访问时 {@code getRemoteAddr()} 返回的是 IPv6 回环
 * {@code 0:0:0:0:0:0:0:1}（或 {@code ::1}），既不可读也和文档示例不一致。
 * 这里统一做归一化：
 * <ul>
 *   <li>IPv6 回环 -&gt; {@code 127.0.0.1}</li>
 *   <li>IPv4 映射地址 {@code ::ffff:10.1.2.3} -&gt; {@code 10.1.2.3}</li>
 *   <li>反向代理场景优先取 {@code X-Forwarded-For} 的第一跳、其次 {@code X-Real-IP}（**默认关闭**，见下）</li>
 *   <li>真正的 IPv6 外网地址无法凭空变成 IPv4，保留紧凑写法</li>
 *   <li>取值一律做字符白名单过滤，避免把请求头内容原样写进日志（日志注入 / 响应头注入）</li>
 * </ul>
 */
public final class IpUtil {

    /** 取不到来源时的占位值。宁可写 unknown 也不写空串：日志表格与筛选都按非空处理。 */
    public static final String UNKNOWN = "unknown";

    /** 代理链可能写过的头，按顺序试；仅当 ip.trust-forwarded-headers=true 时才读 */
    private static final String[] HEADERS = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};

    /** 私有构造：纯静态工具类，不允许实例化。 */
    private IpUtil() {
    }

    /** 直连部署用的默认口径：完全不看代理头，只取 remoteAddr（配置默认就是这个）。 */
    public static String clientIp(HttpServletRequest request) {
        return clientIp(request, false);
    }

    /**
     * @param trustForwardedHeaders 是否信任代理转发的 X-Forwarded-For / X-Real-IP。
     *        这些请求头客户端可以自己写，直连部署（本项目默认形态：Tomcat 6060 直接接浏览器/JMeter）
     *        一旦无条件信任，审计里的 IP 就成了可注入的假证据。只有当前面真的有 nginx/SLB
     *        覆写这些头时才开启（配置项 {@code ip.trust-forwarded-headers}）。
     */
    public static String clientIp(HttpServletRequest request, boolean trustForwardedHeaders) {
        if (request == null) {
            return UNKNOWN;
        }
        if (trustForwardedHeaders) {
            for (String header : HEADERS) {
                String value = request.getHeader(header);
                if (value == null || value.isEmpty()) {
                    continue;
                }
                // X-Forwarded-For: client, proxy1, proxy2 —— 第一个才是客户端
                for (String hop : value.split(",")) {
                    String candidate = hop.trim();
                    if (candidate.isEmpty() || "unknown".equalsIgnoreCase(candidate)) {
                        continue;
                    }
                    String normalized = normalize(candidate);
                    if (normalized != null) {
                        return normalized;
                    }
                }
            }
        }
        String normalized = normalize(request.getRemoteAddr());
        return normalized == null ? UNKNOWN : normalized;
    }

    /**
     * 归一化并校验；非法输入返回 null，由调用方决定回退值。
     */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String ip = raw.trim();
        if (ip.isEmpty() || ip.length() > 64) {
            return null;
        }
        if (ip.charAt(0) == '[') {                 // [::1]:8080 这种带方括号的写法
            int close = ip.indexOf(']');
            if (close > 0) {
                ip = ip.substring(1, close);
            }
        }
        int zone = ip.indexOf('%');                // 去掉 IPv6 区域标识 fe80::1%eth0
        if (zone > 0) {
            ip = ip.substring(0, zone);
        }
        String lower = ip.toLowerCase();
        if (lower.startsWith("::ffff:") && isIpv4(ip.substring(7))) {
            return ip.substring(7);
        }
        if (isIpv4(lower)) {
            return lower;
        }
        if (isIpv6Loopback(lower)) {
            return "127.0.0.1";                    // 本机访问统一记成 127.0.0.1
        }
        if (isIpv6(lower)) {
            return lower;                          // 真 IPv6 客户端保留原值，不伪造
        }
        return null;
    }

    /** 本机回环的两种 IPv6 写法（::1 与全展开形式），归一成 127.0.0.1 才能和 IPv4 记录统一筛选。 */
    private static boolean isIpv6Loopback(String v) {
        return v.equals("::1") || v.equals("0:0:0:0:0:0:0:1");
    }

    /** 严格点分四段判断：每段 1-3 位数字且 0-255；不接受 1.2.3、1.2.3.4.5、前导空格这类写法。 */
    static boolean isIpv4(String v) {
        String[] parts = v.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) < '0' || part.charAt(i) > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * 宽松校验 IPv6：含冒号，分段只能是 1-4 位十六进制（最后一段允许内嵌 IPv4）。
     * 注意不能只靠字符白名单，否则 "1.2.3.4:80" 这种“带端口的地址”会被误当成 IPv6 记进日志。
     */
    static boolean isIpv6(String v) {
        if (v.indexOf(':') < 0 || v.length() > 45) {
            return false;
        }
        String[] seg = v.split(":", -1);
        if (seg.length > 9) {
            return false;
        }
        for (int i = 0; i < seg.length; i++) {
            String part = seg[i];
            if (part.isEmpty()) {
                continue;                            // :: 压缩写法产生的空段
            }
            if (isHexGroup(part)) {
                continue;
            }
            // 只有最后一段允许是内嵌 IPv4（::ffff:1.2.3.4）
            if (i == seg.length - 1 && isIpv4(part)) {
                continue;
            }
            return false;
        }
        return true;
    }

    /** IPv6 分段是否为 1-4 位十六进制（只认小写，调用前已 toLowerCase）。 */
    private static boolean isHexGroup(String part) {
        if (part.length() > 4) {
            return false;
        }
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
