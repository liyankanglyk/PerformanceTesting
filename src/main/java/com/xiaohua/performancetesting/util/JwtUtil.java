package com.xiaohua.performancetesting.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与解析（HS256）。
 *
 * <p>载荷：subject = userId，附加 claim username、role。过期时间与密钥来自
 * {@code jwt.expiration} / {@code jwt.secret}。
 *
 * <p>令牌是自校验的，服务端不存会话，所以两件事必须知道：
 * 改了库里角色，已签出的 Token 仍带旧 role，要重新登录才生效；
 * 系统重置后旧 Token 也**不会**立刻失效（无状态，服务端无从吊销）。
 * 种子脚本是带固定 id 插入的（1=admin、2~4=test001~003），所以这几个账号的 Token
 * 重置后依旧能用；但重置前新建的账号会被清掉，它的 Token 就指向了一个不存在的用户，
 * 表现为 401 或页面数据错乱。因此重置接口只提醒“请重新登录”，不声称已让旧 Token 失效。
 */
@Component
public class JwtUtil {

    /** 签名口令，来自配置 jwt.secret。低于 32 字节会被补零（见 getKey），换口令会让所有已签发 Token 立刻失效 */
    @Value("${jwt.secret}")
    private String secret;

    /** 有效期（毫秒），来自配置 jwt.expiration。期内改角色/删账号的即时生效靠 AdminInterceptor 回查数据库，不靠缩短它 */
    @Value("${jwt.expiration}")
    private long expiration;

    /**
     * 把配置里的字符串口令变成 HS256 需要的密钥。
     *
     * <p>不足 32 字节时补零：jjwt 对短密钥直接抛异常，为了让默认口令能跑起来才这么做。
     * 真要上生产应把 jwt.secret 配到 32 字节以上，而不是依赖补零。
     */
    private SecretKey getKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        // HS256 要求密钥至少 256 bit（32 字节）
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            return Keys.hmacShaKeyFor(padded);
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /** 签发 Token。role 为空按普通用户处理，避免后续拆箱 NPE。 */
    public String generateToken(Long userId, String username, Integer role) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + expiration);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(exp)
                .signWith(getKey())
                .compact();
    }

    /**
     * 验签并解析。
     *
     * @return 解析出的声明
     * @throws io.jsonwebtoken.ExpiredJwtException 已过期（拦截器据此回 token has expired）
     * @throws RuntimeException 签名不符、格式非法等（拦截器统一回 invalid token）
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
