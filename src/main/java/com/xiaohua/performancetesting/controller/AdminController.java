package com.xiaohua.performancetesting.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xiaohua.performancetesting.common.Result;
import com.xiaohua.performancetesting.entity.Goods;
import com.xiaohua.performancetesting.entity.Orders;
import com.xiaohua.performancetesting.entity.User;
import com.xiaohua.performancetesting.service.GoodsService;
import com.xiaohua.performancetesting.service.OperationLogService;
import com.xiaohua.performancetesting.service.OrderService;
import com.xiaohua.performancetesting.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Admin", description = "管理端接口 — 用户/商品/订单管理（需管理员 Token）")
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserService userService;
    private final GoodsService goodsService;
    private final OrderService orderService;
    private final OperationLogService logService;

    public AdminController(UserService userService, GoodsService goodsService,
                           OrderService orderService, OperationLogService logService) {
        this.userService = userService;
        this.goodsService = goodsService;
        this.orderService = orderService;
        this.logService = logService;
    }

    // ==================== User Management ====================

    @Operation(
        summary = "用户列表",
        description = "查询所有用户，支持按用户名模糊搜索。不传 keyword 时返回全部用户（密码字段已脱敏）。"
    )
    @ApiResponse(responseCode = "200", description = "成功")
    @ApiResponse(responseCode = "401", description = "Token 缺失或无效")
    @ApiResponse(responseCode = "403", description = "非管理员无权限")
    @GetMapping("/users")
    public Result<List<User>> listUsers(@RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<User> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) w.like(User::getUsername, keyword);
        w.orderByAsc(User::getId);
        List<User> users = userService.list(w);
        users.forEach(u -> u.setPassword(null));
        return Result.ok(users);
    }

    @Operation(
        summary = "新增用户",
        description = """
            创建新用户。密码明文存储（演示项目），role 默认为 0（普通用户）。

            **请求体示例**：`{"username":"newUser","password":"123456","role":0}`
            """
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
        required = true,
        content = @Content(
            examples = @ExampleObject(value = "{\"username\":\"newUser\",\"password\":\"123456\",\"role\":0}")
        )
    )
    @ApiResponse(responseCode = "200", description = "创建成功")
    @ApiResponse(responseCode = "500", description = "用户名已存在")
    @PostMapping(value = "/users", consumes = "application/json")
    public Result<?> createUser(@RequestBody User user, HttpServletRequest request) {
        if (userService.getOne(new LambdaQueryWrapper<User>().eq(User::getUsername, user.getUsername())) != null) {
            return Result.fail("username already exists");
        }
        user.setId(null);
        if (user.getRole() == null) user.setRole(0);
        userService.save(user);
        logService.log(getAdminId(request), getAdminName(request), "CREATE_USER", "username=" + user.getUsername());
        user.setPassword(null);
        return Result.ok("created", user);
    }

    @Operation(
        summary = "编辑用户",
        description = """
            更新用户信息。可修改密码和角色，用户名不允许修改。
            密码为空时不更新密码字段。

            **请求体示例**：`{"password":"newPassword","role":1}`
            """
    )
    @ApiResponse(responseCode = "200", description = "更新成功")
    @ApiResponse(responseCode = "500", description = "用户不存在")
    @PutMapping(value = "/users/{id}", consumes = "application/json")
    public Result<?> updateUser(@PathVariable Long id, @RequestBody User user, HttpServletRequest request) {
        User db = userService.getById(id);
        if (db == null) return Result.fail("user not found");
        if (user.getPassword() != null && !user.getPassword().isEmpty()) {
            // plaintext stored directly (demo project)
        } else {
            user.setPassword(null);
        }
        user.setId(id);
        user.setUsername(null); // username not updatable
        userService.updateById(user);
        logService.log(getAdminId(request), getAdminName(request), "UPDATE_USER", "id=" + id);
        return Result.ok("updated", null);
    }

    @Operation(
        summary = "删除用户",
        description = "根据用户 ID 删除用户。"
    )
    @ApiResponse(responseCode = "200", description = "删除成功")
    @ApiResponse(responseCode = "500", description = "用户不存在")
    @DeleteMapping("/users/{id}")
    public Result<?> deleteUser(@PathVariable Long id, HttpServletRequest request) {
        if (userService.getById(id) == null) return Result.fail("user not found");
        userService.removeById(id);
        logService.log(getAdminId(request), getAdminName(request), "DELETE_USER", "id=" + id);
        return Result.ok("deleted", null);
    }

    // ==================== Goods Management ====================

    @Operation(
        summary = "新增商品",
        description = "创建新商品。请求体示例：`{\"goodsName\":\"笔记本电脑\",\"price\":5999.00,\"stock\":50}`"
    )
    @ApiResponse(responseCode = "200", description = "创建成功")
    @PostMapping(value = "/goods", consumes = "application/json")
    public Result<?> createGoods(@RequestBody Goods goods, HttpServletRequest request) {
        goods.setId(null);
        goodsService.save(goods);
        logService.log(getAdminId(request), getAdminName(request), "CREATE_GOODS", "name=" + goods.getGoodsName());
        return Result.ok("created", goods);
    }

    @Operation(
        summary = "编辑商品",
        description = "更新商品信息（名称、价格、库存）。请求体示例：`{\"goodsName\":\"笔记本电脑\",\"price\":4999.00,\"stock\":30}`"
    )
    @ApiResponse(responseCode = "200", description = "更新成功")
    @ApiResponse(responseCode = "500", description = "商品不存在")
    @PutMapping(value = "/goods/{id}", consumes = "application/json")
    public Result<?> updateGoods(@PathVariable Long id, @RequestBody Goods goods, HttpServletRequest request) {
        if (goodsService.getById(id) == null) return Result.fail("goods not found");
        goods.setId(id);
        goodsService.updateById(goods);
        logService.log(getAdminId(request), getAdminName(request), "UPDATE_GOODS", "id=" + id);
        return Result.ok("updated", null);
    }

    @Operation(
        summary = "删除商品",
        description = "根据商品 ID 删除商品。"
    )
    @ApiResponse(responseCode = "200", description = "删除成功")
    @ApiResponse(responseCode = "500", description = "商品不存在")
    @DeleteMapping("/goods/{id}")
    public Result<?> deleteGoods(@PathVariable Long id, HttpServletRequest request) {
        if (goodsService.getById(id) == null) return Result.fail("goods not found");
        goodsService.removeById(id);
        logService.log(getAdminId(request), getAdminName(request), "DELETE_GOODS", "id=" + id);
        return Result.ok("deleted", null);
    }

    // ==================== Order Management ====================

    @Operation(
        summary = "全部订单列表",
        description = "查询所有订单，支持按订单号模糊搜索。不传 keyword 时返回全部订单（按创建时间倒序）。"
    )
    @GetMapping("/orders")
    public Result<List<Orders>> listOrders(@RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<Orders> w = new LambdaQueryWrapper<Orders>().orderByDesc(Orders::getCreateTs);
        if (StringUtils.hasText(keyword)) w.like(Orders::getOrderNo, keyword);
        return Result.ok(orderService.list(w));
    }

    @Operation(
        summary = "标记订单已支付",
        description = "将指定订单的状态从 0（未支付）更新为 1（已支付）。"
    )
    @ApiResponse(responseCode = "200", description = "支付成功")
    @ApiResponse(responseCode = "500", description = "订单不存在")
    @PutMapping(value = "/orders/{id}/pay", consumes = "application/json")
    public Result<?> payOrder(@PathVariable Long id, HttpServletRequest request) {
        if (orderService.getById(id) == null) return Result.fail("order not found");
        orderService.update(new LambdaUpdateWrapper<Orders>().eq(Orders::getId, id).set(Orders::getStatus, 1));
        logService.log(getAdminId(request), getAdminName(request), "PAY_ORDER", "orderId=" + id);
        return Result.ok("paid", null);
    }

    // ==================== Helpers ====================

    private Long getAdminId(HttpServletRequest request) {
        return (Long) request.getAttribute("userId");
    }

    private String getAdminName(HttpServletRequest request) {
        return (String) request.getAttribute("username");
    }
}
