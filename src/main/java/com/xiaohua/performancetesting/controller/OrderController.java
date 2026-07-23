package com.xiaohua.performancetesting.controller;

import com.xiaohua.performancetesting.common.Result;
import com.xiaohua.performancetesting.entity.Orders;
import com.xiaohua.performancetesting.service.OrderService;
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

@Tag(name = "Order", description = "订单接口 — 下单购买与订单查询")
@RestController
@RequestMapping("/api/order")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

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
        try {
            Orders order = orderService.buy(userId, goodsId);
            return Result.ok("order placed successfully", order);
        } catch (RuntimeException e) {
            return Result.fail(e.getMessage());
        }
    }

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
