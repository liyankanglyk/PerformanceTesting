package com.xiaohua.performancetesting.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xiaohua.performancetesting.entity.OperationLog;
import com.xiaohua.performancetesting.service.DatabaseInitService;
import com.xiaohua.performancetesting.service.GoodsService;
import com.xiaohua.performancetesting.service.OperationLogService;
import com.xiaohua.performancetesting.service.OrderService;
import com.xiaohua.performancetesting.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 操作日志查询接口契约：管理端日志面板需要翻页，
 * 所以 /logs 返回分页体（records/total/page/size/pages），不再是一次性 limit 条。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemControllerTest {

    @Mock OperationLogService logService;
    @Mock UserService userService;
    @Mock GoodsService goodsService;
    @Mock OrderService orderService;
    @Mock DatabaseInitService dbInitService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SystemController(logService, userService, goodsService, orderService, dbInitService))
                .setControllerAdvice(new com.xiaohua.performancetesting.common.GlobalExceptionHandler())
                .build();
        when(logService.pageQuery(anyLong(), anyLong(), any(), any())).thenAnswer(inv -> {
            Page<OperationLog> pg = new Page<>(inv.getArgument(0), inv.getArgument(1));
            pg.setRecords(List.of(row(pg.getCurrent() * 100 + 1, "ADMIN_LOGIN", "管理员登录")));
            pg.setTotal(13);
            return pg;
        });
    }

    private OperationLog row(long id, String action, String detail) {
        OperationLog l = new OperationLog();
        l.setId(id);
        l.setOperatorId(1L);
        l.setUsername("admin");
        l.setRole(1);
        l.setAction(action);
        l.setDetail(detail);
        l.setIp("127.0.0.1");
        return l;
    }

    @Test
    @DisplayName("默认第 1 页、每页 10 条，响应为分页体")
    void defaultPaging() throws Exception {
        mvc.perform(get("/api/admin/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.total").value(13))
                .andExpect(jsonPath("$.data.pages").value(2))
                // 角色与 IP 是独立列（detail 里不再重复拼 IP）
                .andExpect(jsonPath("$.data.records[0].role").value(1))
                .andExpect(jsonPath("$.data.records[0].ip").value("127.0.0.1"))
                .andExpect(jsonPath("$.data.records[0].detail").value("管理员登录"));
        verify(logService).pageQuery(1, 10, null, null);
    }

    @Test
    @DisplayName("page / size 透传给服务层（夹档与上限在服务层做）")
    void pagingPassedThrough() throws Exception {
        mvc.perform(get("/api/admin/logs").param("page", "3").param("size", "50"))
                .andExpect(jsonPath("$.data.page").value(3))
                .andExpect(jsonPath("$.data.size").value(50));
        verify(logService).pageQuery(3, 50, null, null);

        mvc.perform(get("/api/admin/logs").param("page", "0").param("size", "0"));
        verify(logService).pageQuery(0, 0, null, null);
    }

    @Test
    @DisplayName("keyword 与 action 一起透传（前端筛选依赖它）")
    void filtersPassedThrough() throws Exception {
        mvc.perform(get("/api/admin/logs").param("keyword", "66.60").param("action", "UPDATE_GOODS"));
        verify(logService).pageQuery(1, 10, "66.60", "UPDATE_GOODS");
    }

    @Test
    @DisplayName("操作类型列表接口可用于填充筛选下拉框")
    void logActions() throws Exception {
        when(logService.distinctActions()).thenReturn(List.of("ADMIN_LOGIN", "PAY_ORDER"));
        mvc.perform(get("/api/admin/log-actions"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    // ---------- /summary（系统概览专用聚合接口） ----------

    private void stubOrders(long total, long paid, long winPlaced, long winPaid) {
        when(orderService.count()).thenReturn(total);
        when(orderService.count(any())).thenReturn(paid, winPlaced, winPaid);
        when(userService.count()).thenReturn(300L);
        when(goodsService.count()).thenReturn(6L);
        when(goodsService.count(any())).thenReturn(1L);
        when(goodsService.getObj(any(), any())).thenReturn(980L);
        when(logService.count()).thenReturn(25000L);
    }

    @Test
    @DisplayName("默认窗口 5 分钟，响应按 counts/rate/stock/jvm 分组（业务数据不含 GMV）")
    void summaryShape() throws Exception {
        stubOrders(12480L, 9800L, 620L, 610L);
        mvc.perform(get("/api/admin/summary"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.windowMinutes").value(5))
                .andExpect(jsonPath("$.data.counts.users").value(300))
                .andExpect(jsonPath("$.data.counts.goods").value(6))
                .andExpect(jsonPath("$.data.counts.orders").value(12480))
                .andExpect(jsonPath("$.data.counts.paidOrders").value(9800))
                .andExpect(jsonPath("$.data.counts.logs").value(25000))
                .andExpect(jsonPath("$.data.rate.placed").value(620))
                .andExpect(jsonPath("$.data.rate.placedPerMin").value(124.0))
                .andExpect(jsonPath("$.data.rate.payRate").value(0.984))
                .andExpect(jsonPath("$.data.rate.windowMinutes").value(5))
                .andExpect(jsonPath("$.data.stock.total").value(980))
                .andExpect(jsonPath("$.data.stock.soldOutGoods").value(1))
                // 用户明确不要成交金额：不能再把 money 塞回契约里
                .andExpect(jsonPath("$.data.money").doesNotExist())
                .andExpect(jsonPath("$.data.jvm.heapUsed").isNumber())
                .andExpect(jsonPath("$.data.jvm.gcCount").isNumber());
        // 没发过 SUM(pay_price)：少两条聚和查询就是少两份压测开销
        verify(orderService, never()).getObj(any(), any());
    }

    @Test
    @DisplayName("windowMinutes 越界被夹回 1-60，而不是报 400（下拉框之外的手写值）")
    void summaryClampsWindow() throws Exception {
        stubOrders(10L, 5L, 2L, 2L);
        mvc.perform(get("/api/admin/summary").param("windowMinutes", "0"))
                .andExpect(jsonPath("$.data.windowMinutes").value(1))
                .andExpect(jsonPath("$.data.rate.windowMinutes").value(1))
                // 2 条 / 1 分钟 = 2.0（把窗口当秒乘 60 的写法会得到 120）
                .andExpect(jsonPath("$.data.rate.placedPerMin").value(2.0));
        mvc.perform(get("/api/admin/summary").param("windowMinutes", "-5"))
                .andExpect(jsonPath("$.data.windowMinutes").value(1));
        mvc.perform(get("/api/admin/summary").param("windowMinutes", "999999"))
                .andExpect(jsonPath("$.data.windowMinutes").value(60))
                .andExpect(jsonPath("$.data.rate.windowMinutes").value(60));
        mvc.perform(get("/api/admin/summary").param("windowMinutes", "15"))
                .andExpect(jsonPath("$.data.windowMinutes").value(15))
                // 2 条 / 15 分钟 = 0.1
                .andExpect(jsonPath("$.data.rate.placedPerMin").value(0.1));
    }

    @Test
    @DisplayName("窗口内没新单时 payRate 给 null（前端显示 -），不能当成 0% 吞吐下降")
    void summaryNullRate() throws Exception {
        stubOrders(10L, 0L, 0L, 0L);
        mvc.perform(get("/api/admin/summary"))
                .andExpect(jsonPath("$.data.rate.placed").value(0))
                .andExpect(jsonPath("$.data.rate.payRate").doesNotExist())
                .andExpect(jsonPath("$.data.rate.placedPerMin").value(0.0));
    }

    @Test
    @DisplayName("/status 旧字段全部保留（已有 JMeter 路径），只往上加 heapPct/gc*")
    void statusBackwardCompatible() throws Exception {
        mvc.perform(get("/api/admin/status"))
                .andExpect(jsonPath("$.data.uptime").isNumber())
                .andExpect(jsonPath("$.data.availableProcessors").isNumber())
                .andExpect(jsonPath("$.data.heapUsed").isNumber())
                .andExpect(jsonPath("$.data.heapMax").isNumber())
                .andExpect(jsonPath("$.data.nonHeapUsed").isNumber())
                .andExpect(jsonPath("$.data.threadCount").isNumber())
                .andExpect(jsonPath("$.data.peakThreadCount").isNumber())
                .andExpect(jsonPath("$.data.totalMemory").isNumber())
                .andExpect(jsonPath("$.data.freeMemory").isNumber())
                .andExpect(jsonPath("$.data.maxMemory").isNumber())
                .andExpect(jsonPath("$.data.gcCount").isNumber())
                .andExpect(jsonPath("$.data.gcTimeMs").isNumber());
    }

    @Test
    @DisplayName("windowMinutes 传非数字：400 + invalid parameter（不是 500，不透出转换异常原文）")
    void summaryRejectsNonNumericWindow() throws Exception {
        stubOrders(1L, 1L, 1L, 1L);
        mvc.perform(get("/api/admin/summary").param("windowMinutes", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("invalid parameter: windowMinutes"));
    }

    // ---------- 数据准备：/db/state 与 /db/reset ----------

    private java.util.Map<String, Object> initState() {
        java.util.Map<String, Object> s = new java.util.LinkedHashMap<>();
        s.put("initMode", "if-absent");
        s.put("startupMode", "IF_ABSENT");
        s.put("resetEnabled", true);
        s.put("busy", false);
        s.put("tablesPresent", true);
        s.put("lastResetAt", "2026-05-22 10:00:00");
        s.put("lastResetMode", "full");
        s.put("lastResetMs", 812L);
        return s;
    }

    @Test
    @DisplayName("/db/state 返回启动策略与上次重置信息，并补上当前的四张表计数")
    void dbStateExposesStartupMode() throws Exception {
        when(dbInitService.state()).thenReturn(initState());
        stubOrders(10L, 5L, 2L, 2L);
        mvc.perform(get("/api/admin/db/state"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.startupMode").value("IF_ABSENT"))
                .andExpect(jsonPath("$.data.resetEnabled").value(true))
                .andExpect(jsonPath("$.data.lastResetAt").value("2026-05-22 10:00:00"))
                .andExpect(jsonPath("$.data.counts.users").value(300))
                .andExpect(jsonPath("$.data.counts.goods").value(6))
                // 不能把数据源口令透出去
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    @DisplayName("重置必须显式 confirm=RESET，否则 400 且完全不碰数据库")
    void dbResetRequiresConfirm() throws Exception {
        mvc.perform(post("/api/admin/db/reset").param("mode", "full"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("confirm required: pass confirm=RESET"));
        mvc.perform(post("/api/admin/db/reset").param("confirm", "reset"))
                .andExpect(jsonPath("$.code").value(400));
        verify(dbInitService, never()).reset(any());
        verify(logService, never()).log(any(), any(), any(), any());
    }

    @Test
    @DisplayName("重置成功：返回生效模式与耗时，并在重建之后写 DB_RESET 审计（不会被自己抹掉）")
    void dbResetSuccessAuditsItself() throws Exception {
        java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("mode", "data");
        r.put("elapsedMs", 95L);
        r.put("downgradedToFull", false);
        when(dbInitService.reset("data")).thenReturn(r);
        stubOrders(10L, 5L, 2L, 2L);

        mvc.perform(post("/api/admin/db/reset").param("mode", "data").param("confirm", "RESET"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.mode").value("data"))
                .andExpect(jsonPath("$.data.elapsedMs").value(95))
                .andExpect(jsonPath("$.data.notice").isString())
                .andExpect(jsonPath("$.data.counts.orders").value(10));

        org.mockito.InOrder order = inOrder(dbInitService, logService);
        order.verify(dbInitService).reset("data");
        order.verify(logService).log(any(), any(), eq("DB_RESET"), contains("mode=data"));
    }

    @Test
    @DisplayName("重置错误映射：模式不合法 400、开关关闭 403、并发冲突 409")
    void dbResetErrorMapping() throws Exception {
        // 重桩定必须用 doThrow 形式：when(mock.reset(any())) 会立刻触发上一条 thenThrow
        org.mockito.Mockito.doThrow(new IllegalArgumentException("mode must be full or data"))
                .when(dbInitService).reset(any());
        mvc.perform(post("/api/admin/db/reset").param("mode", "bogus").param("confirm", "RESET"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("mode must be full or data"));

        org.mockito.Mockito.doThrow(new IllegalStateException("db reset is disabled (db.reset.enabled=false)"))
                .when(dbInitService).reset(any());
        mvc.perform(post("/api/admin/db/reset").param("confirm", "RESET"))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("db reset is disabled (db.reset.enabled=false)"));

        org.mockito.Mockito.doThrow(new IllegalStateException("database is initializing, retry later"))
                .when(dbInitService).reset(any());
        mvc.perform(post("/api/admin/db/reset").param("confirm", "RESET"))
                .andExpect(jsonPath("$.code").value(409));

        // 失败路径不能写审计（日志里不应出现一次没发生过的重置）
        verify(logService, never()).log(any(), any(), eq("DB_RESET"), any());
    }
}
