package com.xiaohua.performancetesting.interceptor;

import com.xiaohua.performancetesting.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Token 校验拦截器：管“你是谁”，不管“你能不能”（后者见 AdminInterceptor）。
 *
 * <p>校验通过后把 userId / username / role 放进 request 属性，
 * 控制器一律从这里取当前登录人，不接受请求参数传 userId —— 否则可以冒充他人下单、查他人订单。
 *
 * <p>失败直接写响应体返回 false，不再进全局异常处理，保证 401 的响应形状与 Result 一致。
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    /** 验签与解析令牌，密钥和有效期来自配置项 jwt.* */
    private final JwtUtil jwtUtil;

    /** 构造注入 JwtUtil。本拦截器不查库，角色是否仍然有效由 AdminInterceptor 复核。 */
    public JwtInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    /** 验签通过则把 userId / username / role 写进 request 属性供控制器与审计使用；失败写 401 并返回 false。 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 浏览器预检请求不带自定义头，必须放行，否则跨域时所有接口都 401
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 令牌放在自定义头 token（不是 Authorization: Bearer），与前端 Api.tokenHeader() 对应
        String token = request.getHeader("token");
        if (token == null || token.isEmpty()) {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(401);
            response.getWriter().write("{\"code\":401,\"msg\":\"token is missing\",\"data\":null,\"timestamp\":" + System.currentTimeMillis() + "}");
            return false;
        }

        try {
            Claims claims = jwtUtil.parseToken(token);
            request.setAttribute("userId", Long.valueOf(claims.getSubject()));
            request.setAttribute("username", claims.get("username"));
            // role 进属性后由 AdminInterceptor 判定 403；这里同时供控制器记录审计日志的操作人角色
            request.setAttribute("role", claims.get("role", Integer.class));
        } catch (ExpiredJwtException e) {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(401);
            response.getWriter().write("{\"code\":401,\"msg\":\"token has expired\",\"data\":null,\"timestamp\":" + System.currentTimeMillis() + "}");
            return false;
        } catch (Exception e) {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(401);
            response.getWriter().write("{\"code\":401,\"msg\":\"invalid token\",\"data\":null,\"timestamp\":" + System.currentTimeMillis() + "}");
            return false;
        }

        return true;
    }
}
