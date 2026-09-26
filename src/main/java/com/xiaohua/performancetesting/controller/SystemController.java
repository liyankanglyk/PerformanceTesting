package com.xiaohua.performancetesting.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiaohua.performancetesting.common.Result;
import com.xiaohua.performancetesting.common.PageResult;
import com.xiaohua.performancetesting.entity.Goods;
import com.xiaohua.performancetesting.entity.OperationLog;
import com.xiaohua.performancetesting.entity.Orders;
import com.xiaohua.performancetesting.service.DatabaseInitService;
import com.xiaohua.performancetesting.service.GoodsService;
import com.xiaohua.performancetesting.service.OperationLogService;
import com.xiaohua.performancetesting.service.OrderService;
import com.xiaohua.performancetesting.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "System", description = "系统接口 — 运行状态监控与操作日志（需管理员 Token）")
@RestController
@RequestMapping("/api/admin")
public class SystemController {

    private static final int MAX_LOG_LIMIT = 1000;

    /** orders.status：0 未支付 / 1 已支付（与 OrderController.pay 的写入口径一致） */
    private static final int ORDER_PAID = 1;

    /** 速率统计窗口上限（分钟）：防止 ?windowMinutes=999999 把概览变成全表扫 */
    private static final int MAX_WINDOW_MINUTES = 60;

    private final OperationLogService logService;
    private final UserService userService;
    private final GoodsService goodsService;
    private final OrderService orderService;
    private final DatabaseInitService dbInitService;

    public SystemController(OperationLogService logService, UserService userService,
                            GoodsService goodsService, OrderService orderService,
                            DatabaseInitService dbInitService) {
        this.logService = logService;
        this.userService = userService;
        this.goodsService = goodsService;
        this.orderService = orderService;
        this.dbInitService = dbInitService;
    }

    @Operation(
        summary = "系统运行状态",
        description = "获取服务器实时运行状态，包括：运行时间、CPU核心数、堆内存使用/上限、非堆内存使用、线程数（当前/峰值）、JVM内存等。"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功")
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.ok(jvmInfo());
    }

    @Operation(
        summary = "系统概览汇总",
        description = """
            系统概览页专用：一次请求拿回「业务计数 + 下单/支付速率 + 库存 + JVM」，避开前端打 4 个列表接口只为取 total。

            - `windowMinutes`：速率统计窗口，1-60，默认 5；越界值被提回区间（不报错），响应里的 `windowMinutes` 是生效值
            - 速率统计靠 `orders.idx_create_ts` / `idx_status_create_ts` 做 range 扫描
            - `rate.payRate` 在窗口内无新单时为 `null`（不是 0，前端显示 `-`）
            - 只读、不写审计日志；但每次调用执行 7 条 COUNT/SUM，压测时不要把概览自动刷新调到 1s

            响应：`{windowMinutes, generatedAt, counts:{users,goods,orders,paidOrders,logs},
            rate:{placed,paid,placedPerMin,payRate,windowMinutes}, stock:{total,soldOutGoods}, jvm:{...}}`

            注：成交金额（GMV）按需求已从概览中去掉，响应里不再有 `money` 字段。
            """
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功")
    @GetMapping("/summary")
    public Result<Map<String, Object>> summary(@RequestParam(defaultValue = "5") int windowMinutes) {
        int win = Math.min(MAX_WINDOW_MINUTES, Math.max(1, windowMinutes));
        long now = System.currentTimeMillis();
        long from = now - win * 60_000L;

        long orders = orderService.count();
        long paidOrders = orderService.count(new LambdaQueryWrapper<Orders>().eq(Orders::getStatus, ORDER_PAID));
        long placedWin = orderService.count(new LambdaQueryWrapper<Orders>().ge(Orders::getCreateTs, from));
        long paidWin = orderService.count(new LambdaQueryWrapper<Orders>()
                .ge(Orders::getCreateTs, from).eq(Orders::getStatus, ORDER_PAID));

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("users", userService.count());
        counts.put("goods", goodsService.count());
        counts.put("orders", orders);
        counts.put("paidOrders", paidOrders);
        counts.put("logs", logService.count());

        Map<String, Object> rate = new LinkedHashMap<>();
        rate.put("placed", placedWin);
        rate.put("paid", paidWin);
        // placedWin 是「整窗口」条数，窗口单位已经是分钟 → 每分钟速率 = placedWin / win（不是 *60）
        rate.put("placedPerMin", BigDecimal.valueOf((double) placedWin / win).setScale(1, RoundingMode.HALF_UP));
        // 窗口内没新单时「支付率」无定义：给 null 而不是 0，否则前端会误报吞吐掉了
        rate.put("payRate", placedWin == 0 ? null
                : BigDecimal.valueOf(paidWin).divide(BigDecimal.valueOf(placedWin), 3, RoundingMode.HALF_UP));
        rate.put("windowMinutes", win);

        Map<String, Object> stock = new LinkedHashMap<>();
        Number stockSum = goodsService.getObj(new QueryWrapper<Goods>().select("IFNULL(SUM(stock),0)"), o -> (Number) o);
        stock.put("total", stockSum == null ? 0L : stockSum.longValue());
        stock.put("soldOutGoods", goodsService.count(new LambdaQueryWrapper<Goods>().le(Goods::getStock, 0)));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("windowMinutes", win);
        data.put("generatedAt", now);
        data.put("counts", counts);
        data.put("rate", rate);
        data.put("stock", stock);
        data.put("jvm", jvmInfo());
        return Result.ok(data);
    }

    /** JVM 指标：/status 与 /summary 共用一份口径，避免两处算法漂移 */
    private Map<String, Object> jvmInfo() {
        Runtime runtime = Runtime.getRuntime();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        long heapUsed = memoryBean.getHeapMemoryUsage().getUsed();
        long heapMax = memoryBean.getHeapMemoryUsage().getMax();

        long gcCount = 0;
        long gcTimeMs = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = bean.getCollectionCount();
            long t = bean.getCollectionTime();
            if (c >= 0) gcCount += c;
            if (t >= 0) gcTimeMs += t;
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime());
        info.put("availableProcessors", runtime.availableProcessors());
        info.put("heapUsed", heapUsed);
        info.put("heapMax", heapMax);
        info.put("nonHeapUsed", memoryBean.getNonHeapMemoryUsage().getUsed());
        info.put("threadCount", threadBean.getThreadCount());
        info.put("peakThreadCount", threadBean.getPeakThreadCount());
        info.put("totalMemory", runtime.totalMemory());
        info.put("freeMemory", runtime.freeMemory());
        info.put("maxMemory", runtime.maxMemory());
        // 本轮新增，旧字段全部保留（不影响已有 JMeter 断言路径）
        info.put("heapPct", heapMax > 0 ? BigDecimal.valueOf(heapUsed)
                .divide(BigDecimal.valueOf(heapMax), 4, RoundingMode.HALF_UP) : null);
        info.put("gcCount", gcCount);
        info.put("gcTimeMs", gcTimeMs);

        return info;
    }

    @Operation(
        summary = "操作日志列表",
        description = """
            获取操作日志（服务端分页，按时间倒序，同秒用 id 保证翻页稳定）。

            - `page` 从 1 开始；`size` 默认 10，上限 200
            - `keyword`：匹配操作人 / 操作类型 / 操作详情 / **IP**
            - `action`：按操作类型精确筛选（枚举值见 `/api/admin/log-actions`）
            - 越界页码返回空列表（不自动回绕）

            每行记录：操作人 ID / 用户名 / **角色** / 操作类型 / 详情（含变更前后对比）/ **来源 IP（IPv4）** / 时间。
            """
    )
    @GetMapping("/logs")
    public Result<PageResult<OperationLog>> logs(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String action,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "10") long size) {
        return Result.ok(PageResult.of(logService.pageQuery(page, size, keyword, action)));
    }

    @Operation(
        summary = "操作日志操作类型列表",
        description = "返回当前日志中出现过的操作类型，供管理端筛选下拉框使用。"
    )
    @GetMapping("/log-actions")
    public Result<List<String>> logActions() {
        return Result.ok(logService.distinctActions());
    }

    // ==================== 数据准备（压测间隆重灌） ====================

    @Operation(
        summary = "数据库初始化状态",
        description = """
            概览页展示用：启动策略（`db.init-mode`）、手动重置是否放开、四张表是否已建、
            本进程上一次手动重置的时间与耗时（重置会重建表，没地方持久化，所以只记在内存）。
            不返回任何账号口令。
            """
    )
    @GetMapping("/db/state")
    public Result<Map<String, Object>> dbState() {
        Map<String, Object> s = new LinkedHashMap<>(dbInitService.state());
        s.put("counts", currentCounts());
        return Result.ok(s);
    }

    @Operation(
        summary = "重新灌入测试数据（危险）",
        description = """
            压测跑完一轮后手动把数据恢复到初始状态，不用重启服务。

            - `mode=full`（默认）：执行 schema.sql + data.sql，**表结构与索引一起重建**，适合改过 DDL
            - `mode=data`：只 TRUNCATE 四张表再灌 data.sql，**保留表结构与索引**，更快
            - 必须带 `confirm=RESET`，否则 400（防 JMeter 脚本误调）
            - 服务器配置 `db.reset.enabled=false` 时返回 403
            - 已有重置在跑时返回 409
            - **会清掉 operation_log**：审计行在重建之后才写入，所以本次操作本身能在日志里看到
            - **管理员账号按初始数据重建**：TRUNCATE / 重建会让自增 id 从 1 重新开始，所以旧 Token
              往往仍然指向新的 admin 记录（**不是必然失效**）；但数据已经是另一套，调用后应当重新登录再看页面
              （管理端按钮成功后会主动跳登录页）
            - 压测进行中不要调：会 TRUNCATE 正在写入的 orders 表，把本轮数据捣脏
            """
    )
    @PostMapping("/db/reset")
    public Result<Map<String, Object>> dbReset(@RequestParam(required = false) String mode,
                                               @RequestParam(required = false) String confirm,
                                               HttpServletRequest request) {
        if (!"RESET".equals(confirm)) {
            // 不给默认值：必须显式确认，避开脚本或误点
            return Result.fail(400, "confirm required: pass confirm=RESET");
        }
        Map<String, Object> r;
        try {
            r = dbInitService.reset(mode);
        } catch (IllegalArgumentException e) {
            return Result.fail(400, e.getMessage());
        } catch (IllegalStateException e) {
            String msg = e.getMessage() == null ? "db reset failed" : e.getMessage();
            // 开关被关：权限/配置问题；并发冲突：409
            return Result.fail(msg.contains("retry later") ? 409 : 403, msg);
        }

        Long adminId = adminId(request);
        String adminName = (String) request.getAttribute("username");
        Map<String, Object> counts = currentCounts();
        r.put("counts", counts);
        r.put("notice", "四张表已清空并重新灌入初始数据；账号 ID 可能已变化，请重新登录后再看页面");

        // 重置自己的审计：写在新建的空表里，不会被下一次重置“抹掉自己的记录”
        logService.log(adminId, adminName == null ? "-" : adminName, "DB_RESET",
                "重新灌入测试数据（mode=" + r.get("mode") + "，耗时 " + r.get("elapsedMs") + "ms）"
                        + "；重置后 user=" + counts.get("users") + ", goods=" + counts.get("goods")
                        + ", orders=0, logs=0");
        return Result.ok("数据库已重置，请重新登录", r);
    }

    private Map<String, Object> currentCounts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("users", userService.count());
        counts.put("goods", goodsService.count());
        counts.put("orders", orderService.count());
        counts.put("logs", logService.count());
        return counts;
    }

    private Long adminId(HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        return userId instanceof Long ? (Long) userId : null;
    }
}
