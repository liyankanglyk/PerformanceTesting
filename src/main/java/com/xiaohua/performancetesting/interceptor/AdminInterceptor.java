package com.xiaohua.performancetesting.interceptor;

import com.xiaohua.performancetesting.entity.User;
import com.xiaohua.performancetesting.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理员权限校验，两级判定：
 * <ol>
 *   <li>先看 Token 里的 role —— 普通用户直接 403，不必查库；</li>
 *   <li>再回查数据库里的真实角色。</li>
 * </ol>
 *
 * <p>第 2 步不能省：Token 是自校验的、有效期 1 小时，只信 Token 的话，
 * 被降级或被删号的账号在 Token 过期前依旧能进管理端。回查之后改角色、删账号立即生效。
 *
 * <p>代价是每个 /api/admin/** 请求多一次主键查询。管理端不是压测热点路径，可以接受；
 * 若哪天把管理端接口放进高并发线程组，这个开销要重新评估。
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    /** 回查数据库里的真实角色用（类注释的第 2 步），因此它在每个管理端请求上都会被调用一次 */
    private final UserService userService;

    /** 构造注入用户服务。 */
    public AdminInterceptor(UserService userService) {
        this.userService = userService;
    }

    /**
     * 两级判定，任一环不过就写响应体并返回 false：账号没了给 401（该重新登录），
     * 角色不是管理员给 403（权限问题）。放行前把库里的 role / username 写回 request 属性，
     * 控制器读到的一定是最新值。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        Integer role = (Integer) request.getAttribute("role");
        if (role == null || role != 1) {
            write(response, 403, 403, "admin permission required");
            return false;
        }

        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            write(response, 401, 401, "invalid token");
            return false;
        }

        User current = userService.getById(userId);
        if (current == null) {
            write(response, 401, 401, "account has been deleted, please login again");
            return false;
        }
        if (!Integer.valueOf(1).equals(current.getRole())) {
            write(response, 403, 403, "admin permission required, your role has been changed, please login again");
            return false;
        }

        // 以数据库为准，避免后续业务用到过期角色信息
        request.setAttribute("role", current.getRole());
        request.setAttribute("username", current.getUsername());
        return true;
    }

    /**
     * 直接手写 Result 形状的响应体：拦截器阶段抛出的异常不会被 @RestControllerAdvice 捕获，
     * 不手写前端就拿不到 code/msg。httpStatus 与 code 分开传是为了两者允许不同（当前调用都是一致的）。
     */
    private void write(HttpServletResponse response, int httpStatus, int code, String msg) throws Exception {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(httpStatus);
        response.getWriter().write("{\"code\":" + code + ",\"msg\":\"" + msg + "\",\"data\":null,\"timestamp\":"
                + System.currentTimeMillis() + "}");
    }
}
