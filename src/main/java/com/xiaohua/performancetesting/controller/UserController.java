package com.xiaohua.performancetesting.controller;

import com.xiaohua.performancetesting.common.Result;
import com.xiaohua.performancetesting.entity.User;
import com.xiaohua.performancetesting.service.OperationLogService;
import com.xiaohua.performancetesting.service.UserService;
import com.xiaohua.performancetesting.util.IpUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "User", description = "用户接口 — 登录与个人信息")
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;
    private final OperationLogService logService;

    /**
     * 审计 IP 是否采信 X-Forwarded-For / X-Real-IP。
     * 默认 false：直连部署下这些头客户端想写什么就写什么，记进日志就是可注入的假证据。
     * 前面真的挂了 nginx/SLB 时设为 true。
     */
    @org.springframework.beans.factory.annotation.Value("${ip.trust-forwarded-headers:false}")
    private boolean trustForwardedHeaders;

    public UserController(UserService userService, OperationLogService logService) {
        this.userService = userService;
        this.logService = logService;
    }

    @Operation(
        summary = "用户登录",
        description = """
            登录验证，成功返回 JWT Token 和角色信息。

            **密码加密规则**：客户端将明文密码 + 时间戳做一次 MD5 后传输
            - `password = MD5(明文密码 + 时间戳)`
            - 时间戳通过请求头 `ts` 传入（毫秒级）

            **测试账号**：admin / 123456（管理员），test001~003 / 123456（普通用户）
            """
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
        required = true,
        content = @Content(
            mediaType = "application/json",
            examples = @ExampleObject(
                name = "登录示例（password = MD5(明文 + 时间戳)）",
                value = "{\"username\":\"admin\",\"password\":\"3d5f2c1a8b9e4f6d7c8a9b0e1f2a3b4c\"}"
            )
        )
    )
    @ApiResponse(responseCode = "200", description = "登录成功", content = @Content(
        examples = @ExampleObject(value = "{\"code\":200,\"msg\":\"success\",\"data\":{\"token\":\"eyJhbG...\",\"role\":1},\"timestamp\":1716307200000}")
    ))
    @ApiResponse(responseCode = "400", description = "参数缺失", content = @Content(
        examples = @ExampleObject(value = "{\"code\":400,\"msg\":\"username, password and ts header are required\",\"data\":null,\"timestamp\":1716307200000}")
    ))
    @ApiResponse(responseCode = "401", description = "用户名或密码错误", content = @Content(
        examples = @ExampleObject(value = "{\"code\":401,\"msg\":\"invalid username or password\",\"data\":null,\"timestamp\":1716307200000}")
    ))
    @PostMapping(value = "/login", consumes = "application/json")
    public Result<?> login(@RequestBody Map<String, String> body,
                           @Parameter(description = "当前时间戳（毫秒），用于密码加盐校验", required = true)
                           @RequestHeader("ts") String timestamp,
                           HttpServletRequest request) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null || timestamp == null) {
            return Result.fail(400, "username, password and ts header are required");
        }
        // 防重放：时间戳与服务器时间偏差不得超过 5 分钟
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return Result.fail(400, "invalid timestamp format");
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - ts) > 5 * 60 * 1000) {
            return Result.fail(400, "timestamp expired, possible replay attack");
        }
        String token = userService.login(username, password, timestamp);
        if (token == null) {
            return Result.fail(401, "invalid username or password");
        }
        // 登录已经验证过密码，这里只再查一次用户拿到 id 与角色（旧实现查了两次：getRoleByUsername + getOne）
        User login = userService.getOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                .eq(User::getUsername, username), false);
        if (login == null) {
            return Result.fail(401, "invalid username or password");
        }
        Integer role = login.getRole() != null ? login.getRole() : 0;
        // 登录请求本身没有 Token，拦截器自然不会塞 role；这里补上，日志的“角色”列才有值
        request.setAttribute("role", role);
        // 只记登录成功的日志；用户端登录受 operation-log.user-actions-enabled 开关控制（压测时可关）
        if (role == 1) {
            logService.log(login.getId(), login.getUsername(), "ADMIN_LOGIN", "管理员登录");
        } else {
            logService.logUser(login.getId(), login.getUsername(), "USER_LOGIN", "用户登录");
        }
        return Result.ok(Map.of("token", token, "role", role));
    }

    @Operation(
        summary = "退出登录",
        description = """
            记录一条退出日志（管理员 ADMIN_LOGOUT / 普通用户 USER_LOGOUT）后返回。

            **注意**：本项目使用无状态 JWT，服务端不会使 Token 失效 —— 退出以客户端清除本地 Token 为准。
            需要真正的“服务端踢人”得引入 Token 黑名单（Redis），当前未实现。
            """
    )
    @ApiResponse(responseCode = "200", description = "成功", content = @Content(
        examples = @ExampleObject(value = "{\"code\":200,\"msg\":\"success\",\"data\":\"已退出\",\"timestamp\":1716307200000}")
    ))
    @ApiResponse(responseCode = "401", description = "Token 缺失或无效（由 JwtInterceptor 拦截）")
    @PostMapping("/logout")
    public Result<String> logout(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        String username = (String) request.getAttribute("username");
        if (userId == null) {
            // 正常路径由 JwtInterceptor 拦下；这里兼容“绕过拦截器”的调用，避免写入无主日志
            return Result.fail(401, "token is missing");
        }
        User db = userService.getById(userId);
        // 账号已删除时按普通用户记一条，不丢审计
        boolean admin = db != null && db.getRole() != null && db.getRole() == 1;
        request.setAttribute("role", admin ? 1 : 0);   // 同上：让退出日志的角色列不为空
        if (admin) {
            // 管理端审计不受用户行为开关影响，始终记录
            logService.log(userId, username, "ADMIN_LOGOUT", "管理员退出");
        } else {
            logService.logUser(userId, username, "USER_LOGOUT", "用户退出");
        }
        return Result.ok("已退出");
    }

    @Operation(
        summary = "获取当前登录用户信息",
        description = "根据请求头中的 Token 解析当前用户，返回用户基本信息（不含密码）。需要在请求头携带 `token`。"
    )
    @ApiResponse(responseCode = "200", description = "成功", content = @Content(
        examples = @ExampleObject(value = "{\"code\":200,\"msg\":\"success\",\"data\":{\"id\":1,\"username\":\"admin\",\"role\":1,\"createTime\":\"2026-05-22T10:00:00\"},\"timestamp\":1716307200000}")
    ))
    @ApiResponse(responseCode = "401", description = "Token 缺失或无效")
    @ApiResponse(responseCode = "404", description = "用户不存在")
    @GetMapping("/info")
    public Result<?> info(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        User user = userService.getById(userId);
        if (user == null) {
            return Result.fail(404, "user not found");
        }
        user.setPassword(null);
        return Result.ok(user);
    }

    /** 取客户端地址（统一 IPv4 写法），仅用于日志展示；是否采信代理头由配置决定 */
    private String clientIp(HttpServletRequest request) {
        return IpUtil.clientIp(request, trustForwardedHeaders);
    }
}
