package com.xiaohua.performancetesting.controller;

import com.xiaohua.performancetesting.common.Result;
import com.xiaohua.performancetesting.entity.Goods;
import com.xiaohua.performancetesting.service.GoodsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端商品查询接口。
 *
 * <p>注意它**需要 Token**：WebConfig 只把 {@code /api/user/login} 和文档资源排除在
 * JwtInterceptor 之外，{@code /api/goods/**} 不在白名单里。
 * 写压测脚本时线程组里要先跑一次登录并把 Token 传给后续请求，否则会一片 401。
 *
 * <p>本接口返回全量列表（不分页），只用于用户端页面展示；
 * 管理端分页列表走 {@code GET /api/admin/goods}，两者响应结构不同，不要混用。
 */
@Tag(name = "Goods", description = "商品接口 — 商品查询与搜索")
@RestController
@RequestMapping("/api/goods")
public class GoodsController {

    /** 商品列表查询（含关键词过滤） */
    private final GoodsService goodsService;

    /** 构造注入商品查询服务。 */
    public GoodsController(GoodsService goodsService) {
        this.goodsService = goodsService;
    }

    /** 用户端商品列表，返回全量不分页；需要 Token（不在 WebConfig 白名单里）。 */
    @Operation(
        summary = "商品列表查询",
        description = """
            查询所有商品，支持通过 `keyword` 参数进行模糊搜索（匹配商品名称）。
            不传 `keyword` 时返回全部商品列表。

            **请求头**：需要携带 `token`（JWT Token）
            """
    )
    @ApiResponse(responseCode = "200", description = "成功", content = @Content(
        examples = @ExampleObject(value = "{\"code\":200,\"msg\":\"success\",\"data\":[{\"id\":1,\"goodsName\":\"手机\",\"price\":2999.00,\"stock\":100,\"createTime\":\"2026-05-22T10:00:00\"}],\"timestamp\":1716307200000}")
    ))
    @ApiResponse(responseCode = "401", description = "Token 缺失或无效")
    @GetMapping("/list")
    public Result<List<Goods>> list(
            @Parameter(description = "模糊搜索关键词（匹配商品名称），可选")
            @RequestParam(required = false) String keyword) {
        return Result.ok(goodsService.listAll(keyword));
    }
}
