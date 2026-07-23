package com.xiaohua.performancetesting.controller;

import com.xiaohua.performancetesting.common.Result;
import com.xiaohua.performancetesting.entity.User;
import com.xiaohua.performancetesting.service.OperationLogService;
import com.xiaohua.performancetesting.service.UserService;
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
                           @RequestHeader("ts") String timestamp) {
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
        Integer role = userService.getRoleByUsername(username);
        if (role != null && role == 1) {
            Long userId = userService.getOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                    .eq(User::getUsername, username)).getId();
            logService.log(userId, username, "ADMIN_LOGIN", "管理员登录");
        }
        return Result.ok(Map.of("token", token, "role", role));
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
}
