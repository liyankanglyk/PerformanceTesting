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

/**
 * 系统监控与运维接口，和 AdminController 共用 {@code /api/admin} 前缀，
 * 因此同样要管理员 Token；按前缀去 AdminController 找这些接口是找不到的。
 *
 * <p>分两类：
 * <ul>
 *   <li>只读观测：{@code /status}、{@code /summary}、{@code /logs}、{@code /log-actions}，
 *       给概览页轮询用（默认 5 秒一次）。</li>
 *   <li>破坏性运维：{@code /db/state}、{@code /db/reset}，重置会清库并重灌种子数据。</li>
 * </ul>
 *
 * <p>压测时**不要把这些接口放进高并发线程组**：{@code /summary} 一次要跑计数 + 速率 +
 * 库存 + JVM 十几条查询，几十并发就能把连接池占满，测出来的业务 RT 全是假的。
 * 要看实时指标，单开一个 1 线程 / 5 秒的定时器足够。
 */
@Tag(name = "System", description = "系统接口 — 运行状态监控与操作日志（需管理员 Token）")
@RestController
@RequestMapping("/api/admin")
public class SystemController {

    /** “最近 N 条日志”的条数上限，夹住 ?limit=999999 这类请求，不让它拉全表 */
    private static final int MAX_LOG_LIMIT = 1000;

    /** orders.status：0 未支付 / 1 已支付（与 OrderController.pay 的写入口径一致） */
    private static final int ORDER_PAID = 1;

    /** 速率统计窗口上限（分钟）：防止 ?windowMinutes=999999 把概览变成全表扫 */
    private static final int MAX_WINDOW_MINUTES = 60;

    /** 日志翻页与操作类型枚举 */
    private final OperationLogService logService;
    /** 概览页的业务计数之一（用户数） */
    private final UserService userService;
    /** 概览页的业务计数与售罄统计 */
    private final GoodsService goodsService;
    /** 概览页的订单计数与近 N 分钟速率 */
    private final OrderService orderService;
    /** 库初始化状态与「系统重置」的执行者 */
    private final DatabaseInitService dbInitService;

    /** 构造注入观测与运维数据的来源：日志、用户、商品、订单服务与库初始化服务。 */
    public SystemController(OperationLogService logService, UserService userService,
                            GoodsService goodsService, OrderService orderService,
                            DatabaseInitService dbInitService) {
        this.logService = logService;
        this.userService = userService;
        this.goodsService = goodsService;
        this.orderService = orderService;
        this.dbInitService = dbInitService;
    }

    /** JVM 内存、GC、线程与启动信息。字段只增不改，新增指标往这里加而不是新开接口。 */
    @Operation(
        summary = "系统运行状态",
        description = "获取服务器实时运行状态，包括：运行时间、CPU核心数、堆内存使用/上限、非堆内存使用、线程数（当前/峰值）、JVM内存等。"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功")
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.ok(jvmInfo());
    }

    /** 概览页唯一的聚合接口：业务计数 + 速率 + 库存 + JVM 一次返回，替代前端打四个分页接口取 total。 */
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
        // 字段只增不改：已有 JMeter 断言按旧字段路径取值，改名或删字段会让断言静默全红
        info.put("heapPct", heapMax > 0 ? BigDecimal.valueOf(heapUsed)
                .divide(BigDecimal.valueOf(heapMax), 4, RoundingMode.HALF_UP) : null);
        info.put("gcCount", gcCount);
        info.put("gcTimeMs", gcTimeMs);

        return info;
    }

    /** 服务端分页 + 关键词/操作类型筛选，倒序按 create_time 再以 id 兜底。 */
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

    /** 日志里真实出现过的枚举并上已知枚举，给筛选下拉框打底，避免新增动作时前端硬编码漏项。 */
    @Operation(
        summary = "操作日志操作类型列表",
        description = "返回当前日志中出现过的操作类型，供管理端筛选下拉框使用。"
    )
    @GetMapping("/log-actions")
    public Result<List<String>> logActions() {
        return Result.ok(logService.distinctActions());
    }

    // ==================== 数据准备（压测间隆重灌） ====================

    /** 只读，不含连接口令。库连不上时降级返回而不是 500 —— 表没建好正是它最该被调用的时候。 */
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

    /** 破坏性接口：清库重建，必须带 confirm=RESET；并发第二个请求会拿到 busy。审计在重建之后写，否则这条记录会被自己清掉。 */
    @Operation(
        summary = "重新灌入测试数据（危险）",
        description = """
            压测跑完一轮后手动把数据恢复到初始状态，不用重启服务。

            - `mode=full`（默认）：执行初始化脚本 performance_testing.sql，每张表先 DROP 再建并灌种子数据
            - `mode=data`：**已无“只清数据”这种能力**（结构与数据在同一份脚本里），会降级成 full，
              响应里回 `downgradedToFull=true`；保留这个取值只是为了不打破已有调用
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

    /** 四条 count(*)。只给概览页用，别放进高并发线程组（见类注释）。 */
    private Map<String, Object> currentCounts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("users", userService.count());
        counts.put("goods", goodsService.count());
        counts.put("orders", orderService.count());
        counts.put("logs", logService.count());
        return counts;
    }

    /** 当前管理员 ID，取自 JwtInterceptor 写入的 request 属性；写审计日志时作为操作人 */
    private Long adminId(HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        return userId instanceof Long ? (Long) userId : null;
    }
}
