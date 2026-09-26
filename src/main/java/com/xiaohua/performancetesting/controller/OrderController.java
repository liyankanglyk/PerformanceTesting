package com.xiaohua.performancetesting.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xiaohua.performancetesting.common.Result;
import com.xiaohua.performancetesting.entity.Orders;
import com.xiaohua.performancetesting.service.OperationLogService;
import com.xiaohua.performancetesting.service.OrderService;
import com.xiaohua.performancetesting.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户端订单接口：下单、支付、我的订单。
 *
 * <p>{@code POST /api/order/buy} 是 JMeter 主压测接口，写事务（扣库存 + 插订单）；
 * 并发正确性由 GoodsMapper#deductStock 的条件 UPDATE 保证，这里不做“先查再扣”。
 *
 * <p>userId 只从 Token 取（request 属性），所以压测脚本必须先登录拿到对应账号的 Token；
 * 想用同一个 Token 冒充别人下单是行不通的。
 *
 * <p>{@code PUT /api/order/pay/{orderNo}} 从管理端迁到这里：只允许本人支付（订单归属不符 403），
 * 且是条件更新（status=0 才置 1），重复调用不会重复计入，也不会报错。
 */
@Tag(name = "Order", description = "订单接口 — 下单购买与订单查询")
@RestController
@RequestMapping("/api/order")
public class OrderController {

    /** 下单、支付、订单查询 */
    private final OrderService orderService;
    /** 下单/支付前确认账号还在：Token 有效期内可能被管理员删号 */
    private final UserService userService;
    /** 用户端审计入口（PLACE_ORDER、PAY_ORDER），受 operation-log.user-actions-enabled 开关控制 */
    private final OperationLogService logService;

    /** 构造注入订单服务、用户服务（确认账号还在）与审计服务。 */
    public OrderController(OrderService orderService, UserService userService, OperationLogService logService) {
        this.orderService = orderService;
        this.userService = userService;
        this.logService = logService;
    }

    /** JMeter 主压测接口。扣库存 + 插订单在同一事务里，库存判定交给数据库条件 UPDATE。 */
    @Operation(
        summary = "购买商品（下单）",
        description = """
            用户下单购买指定商品。

            **并发控制**：使用数据库原子 `UPDATE goods SET stock = stock - 1 WHERE id = ? AND stock > 0`
            保证高并发场景下不超卖。影响行数为 0 则表示库存不足。

            **请求头**：需要携带 `token`（JWT Token）
            """
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
        required = true,
        content = @Content(
            mediaType = "application/json",
            examples = @ExampleObject(
                name = "下单示例",
                value = "{\"goodsId\":1}"
            )
        )
    )
    @ApiResponse(responseCode = "200", description = "下单成功", content = @Content(
        examples = @ExampleObject(value = "{\"code\":200,\"msg\":\"order placed successfully\",\"data\":{\"id\":1,\"orderNo\":\"a1b2c3d4e5f6\",\"userId\":2,\"goodsId\":1,\"payPrice\":2999.00,\"createTs\":1716307200000,\"status\":0},\"timestamp\":1716307200000}")
    ))
    @ApiResponse(responseCode = "400", description = "参数缺失", content = @Content(
        examples = @ExampleObject(value = "{\"code\":400,\"msg\":\"goodsId is required\",\"data\":null,\"timestamp\":1716307200000}")
    ))
    @ApiResponse(responseCode = "401", description = "Token 缺失或无效")
    @ApiResponse(responseCode = "500", description = "库存不足", content = @Content(
        examples = @ExampleObject(value = "{\"code\":500,\"msg\":\"insufficient stock\",\"data\":null,\"timestamp\":1716307200000}")
    ))
    @PostMapping(value = "/buy", consumes = "application/json")
    public Result<?> buy(@RequestBody Map<String, Long> body, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        Long goodsId = body.get("goodsId");
        if (goodsId == null) {
            return Result.fail(400, "goodsId is required");
        }
        // Token 有效期内账号可能已被管理员删除，不能再产生无效 user_id 的订单
        if (userId == null || userService.getById(userId) == null) {
            return Result.fail(401, "account not found, please login again");
        }
        try {
            Orders order = orderService.buy(userId, goodsId);
            // 用户端也要能追溯：谁在什么时候下了哪一单（只记成功，失败原因已返回给调用方）
            logService.logUser(userId, username(request), "PLACE_ORDER", orderBrief(order));
            return Result.ok("order placed successfully", order);
        } catch (RuntimeException e) {
            return Result.fail(e.getMessage());
        }
    }

    /** 订单快照，与管理端日志格式保持一致：能回答“谁下的单、买了什么、多少钱” */
    private String orderBrief(Orders o) {
        if (o == null) return "订单不存在";
        return "#" + o.getId() + " 订单号 " + o.getOrderNo()
                + "（商品 #" + o.getGoodsId() + "，金额 " + (o.getPayPrice() == null ? "-" : o.getPayPrice().toPlainString()) + "）";
    }

    /** 当前登录人名，审计日志用；取自 request 属性，不查库 */
    private String username(HttpServletRequest request) {
        Object name = request.getAttribute("username");
        return name == null ? null : String.valueOf(name);
    }

    /** 只能支付自己的订单；条件更新保证重复调用只生效一次，返回码不区分“已支付”和“刚支付”。 */
    @Operation(
        summary = "确认支付订单（用户端）",
        description = """
            用户支付自己的订单，状态 0（未支付）→ 1（已支付）。

            **只能付自己的单**：订单 `user_id` 与 Token 不一致返回 403；不存在返回 404。
            **并发安全**：条件更新 `WHERE id = ? AND status = 0`，重复提交（双击/重试）只有一次能改成功，
            第二次返回 500 `order already paid`，不会重复写日志。
            管理端不再提供“标记已支付”（支付应由用户自己完成）。
            """
    )
    @Parameter(name = "orderNo", description = "下单接口返回的订单号", required = true)
    @ApiResponse(responseCode = "200", description = "支付成功")
    @ApiResponse(responseCode = "400", description = "订单号格式非法")
    @ApiResponse(responseCode = "403", description = "不是当前用户的订单")
    @ApiResponse(responseCode = "404", description = "订单不存在")
    @ApiResponse(responseCode = "500", description = "订单已支付")
    @PutMapping("/pay/{orderNo}")
    public Result<?> pay(@PathVariable String orderNo, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return Result.fail(401, "token is missing");
        }
        String no = orderNo == null ? "" : orderNo.trim();
        if (!no.matches("[0-9a-zA-Z\\-]{8,64}")) {
            return Result.fail(400, "invalid orderNo");
        }
        Orders order = orderService.getOne(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getOrderNo, no), false);
        if (order == null) {
            return Result.fail(404, "order not found");
        }
        // 越权拦截：订单号是 32 位随机串，但不能指望“猜不到”当权限
        if (!userId.equals(order.getUserId())) {
            return Result.fail(403, "forbidden: not your order");
        }
        boolean changed = orderService.update(new LambdaUpdateWrapper<Orders>()
                .eq(Orders::getId, order.getId())
                .eq(Orders::getUserId, userId)          // 归属锁在 SQL 谓词里，不依赖上面那次读（避开 TOCTOU）
                .eq(Orders::getStatus, 0)
                .set(Orders::getStatus, 1));
        if (!changed) {
            Orders now = orderService.getById(order.getId());
            if (now == null) {
                // 读到行后又被管理员删掉，不能报成“已支付”
                return Result.fail(404, "order not found");
            }
            return Result.fail(500, "order already paid");
        }
        Orders paid = orderService.getById(order.getId());
        if (paid == null) {
            // 支付已成吽，只是快照拿不到：回一个合并后的对象，不返回 code=200 + data=null
            order.setStatus(1);
            paid = order;
        }
        // 支付是资金状态变更，审计不受 operation-log.user-actions-enabled 开关影响（旧版管理端接口是必记的）
        logService.log(userId, username(request), "PAY_ORDER", "确认支付：" + orderBrief(paid));
        return Result.ok("paid", paid);
    }

    /** 只返回当前 Token 用户的订单，按创建时间倒序；关键词匹配订单号。 */
    @Operation(
        summary = "查询我的订单",
        description = """
            查询当前登录用户的所有订单，支持通过 `keyword` 按订单号模糊搜索。

            **请求头**：需要携带 `token`（JWT Token）
            """
    )
    @ApiResponse(responseCode = "200", description = "成功", content = @Content(
        examples = @ExampleObject(value = "{\"code\":200,\"msg\":\"success\",\"data\":[{\"id\":1,\"orderNo\":\"a1b2c3d4e5f6\",\"userId\":2,\"goodsId\":1,\"payPrice\":2999.00,\"createTs\":1716307200000,\"status\":0}],\"timestamp\":1716307200000}")
    ))
    @GetMapping("/list")
    public Result<List<Orders>> list(
            @Parameter(description = "订单号关键词（模糊搜索），可选")
            @RequestParam(required = false) String keyword,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.ok(orderService.listByUserId(userId, keyword));
    }
}
