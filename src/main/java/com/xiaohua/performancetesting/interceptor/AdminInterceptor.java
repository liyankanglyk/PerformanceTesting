package com.xiaohua.performancetesting.interceptor;

import com.xiaohua.performancetesting.entity.User;
import com.xiaohua.performancetesting.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理员权限校验。
 * 先按 Token 里的 role 快速拒绝（普通用户不会走到数据库），
 * 再回查数据库真实角色：管理员在后台改角色/删账号后，
 * 对方手里 1 小时有效期的旧 Token 会立刻失效（旧实现只看 Token，
 * 被降级或被删除的账号在过期前仍是管理员）。
 * 注意：管理端接口不是压测热点路径，这里多一次主键查询可以接受。
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    private final UserService userService;

    public AdminInterceptor(UserService userService) {
        this.userService = userService;
    }

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

    private void write(HttpServletResponse response, int httpStatus, int code, String msg) throws Exception {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(httpStatus);
        response.getWriter().write("{\"code\":" + code + ",\"msg\":\"" + msg + "\",\"data\":null,\"timestamp\":"
                + System.currentTimeMillis() + "}");
    }
}
