package com.xiaohua.performancetesting.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 建库 + 灌初始数据的唯一入口。初始化内容只有<b>一份脚本</b>
 * {@code classpath:performance_testing.sql}（Navicat 整库转储：每张表先 DROP 再 CREATE，
 * 紧跟 INSERT 种子数据），两条触发路径共用它：
 * <ol>
 *   <li>启动时按 {@code db.init-mode} 决定：always（每次重启清库重建，默认值，会冲掉已有数据）/
 *       if-absent（只在库或表缺失时才建）/ never（完全不动）；</li>
 *   <li>压测跑完后由管理端按钮手动触发 {@code POST /api/admin/db/reset}。</li>
 * </ol>
 *
 * <p>之所以把“灌数据”做成可显式调用的动作，而不是只在启动时顺手做掉：
 * 清库必须可审计（谁在什么时候清的）、可关闭（共享库上直接禁掉）、
 * 并且能在两轮压测之间执行。绑在“重启服务”上会让这三点都做不到，
 * 还会顺手冲掉本来想留档对比的数据。
 */
@Service
public class DatabaseInitService implements ApplicationRunner {

    /** 初始化过程只写应用日志：库可能还没建，没地方写 */
    private static final Logger log = LoggerFactory.getLogger(DatabaseInitService.class);

    /** 执行初始化脚本重建全部内容与种子数据（脚本自带 DROP TABLE IF EXISTS） */
    public static final String MODE_FULL = "full";
    /**
     * 旧的“只清数据、保留表结构”模式。初始化脚本合并成单文件后<b>不再有这个能力</b>：
     * 结构与数据在同一份脚本里，要灌种子就必须重建表。
     * 保留常量只是为了不破坏已有调用与文档；传 data 会被降级成 full，
     * 并在响应里回 {@code downgradedToFull=true}。
     */
    public static final String MODE_DATA = "data";

    /** 唯一的初始化脚本；文件名跟着 Navicat 导出的库名走，改名会让启动直接失败 */
    static final String INIT_SCRIPT = "performance_testing.sql";

    /**
     * SQL 脚本的读取字符集，必须是 UTF-8。
     * 不显式指定的话 ResourceDatabasePopulator 会用 JVM 平台默认字符集读文件：
     * 中文 Windows（GBK）上初始化脚本里的 UTF-8 字节会被按 GBK 解码，
     * 灌进库的商品名就变成「鑻规灉 iPhone 16 Pro Max」。Java 18+ 默认才是 UTF-8，本项目跑 Java 17。
     */
    static final String SCRIPT_ENCODING = StandardCharsets.UTF_8.name();

    /** 表都不多，重建成本无所谓；顺序按“先删被引用的”排，逻辑上更清楚（实际没有外键） */
    private static final String[] TABLES = {"operation_log", "orders", "goods", "`user`"};

    /** 建库后执行脚本、判断表是否存在、TRUNCATE 都走这个连接池 */
    private final DataSource dataSource;
    /** 重建互斥锁。进行中再次触发直接报错而不是排队：两个人同时清库等于互相破坏，第二个的“成功”是假的 */
    private final AtomicBoolean busy = new AtomicBoolean(false);

    /** JDBC URL，用来截出库名与建库地址（库可能还不存在，DataSource 自己连不上去） */
    @Value("${spring.datasource.url}")
    private String url;
    /** 建库时用的账号，与业务连接池同一套配置 */
    @Value("${spring.datasource.username}")
    private String dbUser;
    /** 建库时用的口令，只在这一步使用，不出现在任何响应体里 */
    @Value("${spring.datasource.password}")
    private String dbPass;

    /** 启动时策略，默认 always 以保持既有行为与已有 JMeter 文档一致 */
    @Value("${db.init-mode:always}")
    private String initModeRaw;

    /** 手动重置开关：正式环境或共享库可以关掉，避免被误触发（关掉后接口返回 403） */
    @Value("${db.reset.enabled:true}")
    private boolean resetEnabled;

    /** 解析后的启动策略。run() 之前是默认值 ALWAYS，所以状态接口要在启动完成后才准确 */
    private volatile StartupMode startupMode = StartupMode.ALWAYS;
    /** 重置时间只能记在进程里：库都被重建了，没有地方持久化 */
    private volatile String lastResetAt;
    /** 上次重置用的模式（full / data），只在内存里，重启即丢 */
    private volatile String lastResetMode;
    /** 上次重置耗时（毫秒），只在内存里，用来判断库是不是刚重建过 */
    private volatile Long lastResetMs;

    /**
     * 只注入 DataSource；{@code @Value} 那些字段要等构造之后才注入，
     * 所以构造方法里绝不能读 initModeRaw / url，也绝不能碰数据库。
     */
    public DatabaseInitService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 启动时的初始化策略，对应配置 {@code db.init-mode}（解析见 parseStartupMode） */
    public enum StartupMode {
        /** 每次重启都清库重建（本项目最初的默认行为，JMeter 文档按它写） */
        ALWAYS,
        /** 只在四张表没建齐时才建，已有数据保留 */
        IF_ABSENT,
        /** 启动完全不碰库；这时表可能压根不存在，需要手动重置或自己执行初始化脚本 */
        NEVER
    }

    /** 配置值解析：认不出来的写法一律退回 ALWAYS，而不是让服务起不来 */
    public static StartupMode parseStartupMode(String raw) {
        if (raw == null) return StartupMode.ALWAYS;
        switch (raw.trim().toLowerCase()) {
            case "always", "on", "true", "":
                return StartupMode.ALWAYS;
            case "if-absent", "if_absent", "ifabsent", "missing":
                return StartupMode.IF_ABSENT;
            case "never", "off", "false", "skip":
                return StartupMode.NEVER;
            default:
                return StartupMode.ALWAYS;
        }
    }

    /** 实际生效的启动策略（run() 里解析配置后才准确；构造阶段是默认值 ALWAYS） */
    public StartupMode startupMode() {
        return startupMode;
    }

    /** 手动重置是否允许；false 时接口返回 403，前端按钮置灰 */
    public boolean isResetEnabled() {
        return resetEnabled;
    }

    /** 是否正在重建。true 时再调 reset 会直接抛 busy，不会排队 */
    public boolean isBusy() {
        return busy.get();
    }

    /** 从 JDBC URL 里截出库名（去掉 {@code ?} 之后的连接参数），用于建库判断与状态展示 */
    public String databaseName() {
        String dbName = url.substring(url.lastIndexOf('/') + 1);
        int q = dbName.indexOf('?');
        return q >= 0 ? dbName.substring(0, q) : dbName;
    }

    // ==================== 启动 ====================

    /**
     * 启动初始化入口。
     *
     * <p>任何一步失败都会让应用启动失败（宁可起不来，也不要起来后接口全 500）；
     * 建库先于执行脚本，因为 DataSource 连不上一个不存在的库。
     */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        startupMode = parseStartupMode(initModeRaw);
        ensureDatabase();
        switch (startupMode) {
            case ALWAYS -> {
                runInitScript();
                log.info("startup init: schema + seed rebuilt from {} (db.init-mode=always)", INIT_SCRIPT);
            }
            case IF_ABSENT -> {
                if (tablesMissing()) {
                    runInitScript();
                    log.info("startup init: tables were missing, created from {}", INIT_SCRIPT);
                } else {
                    log.info("startup init: skipped, all tables present (db.init-mode=if-absent)");
                }
            }
            case NEVER -> log.info("startup init: skipped by db.init-mode=never");
        }
    }

    // ==================== 手动重置 ====================

    /**
     * 压测间隙重新灌数据。
     *
     * @param mode {@link #MODE_FULL} 或 {@link #MODE_DATA}
     * @return 本次执行的元信息（生效模式、耗时、是否被降级为 full）
     * @throws IllegalArgumentException 模式不合法
     * @throws IllegalStateException    接口被配置关闭，或已有重置在跑
     */
    public Map<String, Object> reset(String mode) {
        String m = (mode == null || mode.isBlank()) ? MODE_FULL : mode.trim().toLowerCase();
        if (!MODE_FULL.equals(m) && !MODE_DATA.equals(m)) {
            throw new IllegalArgumentException("mode must be full or data");
        }
        if (!resetEnabled) {
            throw new IllegalStateException("db reset is disabled (db.reset.enabled=false)");
        }
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("database is initializing, retry later");
        }
        long t0 = System.currentTimeMillis();
        boolean downgradedToFull = false;
        try {
            ensureDatabase();
            // 结构与数据在同一份脚本里，没有“只清数据”这条路了：data 一律降级为 full，
            // 并把降级事实回给调用方，避免前端以为只清了数据、实际把表也重建了。
            if (MODE_DATA.equals(m)) {
                m = MODE_FULL;
                downgradedToFull = true;
            }
            runInitScript();

            long ms = System.currentTimeMillis() - t0;
            lastResetAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            lastResetMode = m;
            lastResetMs = ms;
            log.warn("manual db reset done: mode={}, elapsed={}ms", m, ms);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("mode", m);
            r.put("elapsedMs", ms);
            r.put("downgradedToFull", downgradedToFull);
            r.put("finishedAt", lastResetAt);
            return r;
        } catch (SQLException e) {
            throw new IllegalStateException("db reset failed: " + e.getMessage(), e);
        } finally {
            busy.set(false);
        }
    }

    /**
     * 概览页与 {@code GET /api/admin/db/state} 用的状态快照。
     *
     * <p>刻意不含口令等连接信息；连不上库时降级返回 tablesPresent=false 而不是抛异常，
     * 因为「表还没建好」正是这个接口最该被调用的时刻。
     */
    public Map<String, Object> state() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("initMode", initModeRaw);
        s.put("startupMode", startupMode.name());
        s.put("resetEnabled", resetEnabled);
        s.put("busy", busy.get());
        s.put("database", databaseName());
        boolean missing;
        try {
            missing = tablesMissing();
        } catch (Exception e) {
            // 连不上库时状态接口也要能返回（这正是“表还没建好”的场景），不能 500
            missing = true;
            log.warn("cannot read table metadata: {}", e.getMessage());
        }
        s.put("tablesPresent", !missing);
        s.put("lastResetAt", lastResetAt);
        s.put("lastResetMode", lastResetMode);
        s.put("lastResetMs", lastResetMs);
        return s;
    }

    // ==================== 内部 ====================

    /** 建库：DataSource 还连不上不存在的库，所以先用裸 JDBC 建一次 */
    private void ensureDatabase() throws SQLException {
        String baseUrl = url.substring(0, url.lastIndexOf('/'));
        // 不带连接参数（charset 等）也不能影响建库：库名靠 databaseName() 先剔掉 ? 后面的部分
        try (Connection conn = DriverManager.getConnection(baseUrl, dbUser, dbPass)) {
            conn.createStatement().execute(
                    "CREATE DATABASE IF NOT EXISTS `" + databaseName() + "` DEFAULT CHARACTER SET utf8mb4");
        }
    }

    /**
     * 执行初始化脚本：先建四张表（脚本内自带 DROP），再灌种子数据
     * （user 4 个账号、goods 15 个商品）。
     *
     * <p>破坏性来自脚本本身：它开头就对每张表 DROP TABLE IF EXISTS，跑一次等于把全库推倒重来。
     */
    private void runInitScript() {
        executeScript(INIT_SCRIPT);
    }

    /** 执行 classpath 下的 SQL 脚本；字符集与“出错即中断”的策略见方法体内注释。 */
    private void executeScript(String name) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource(name));
        // 必须显式指定 UTF-8，别依赖平台默认字符集，原因见 SCRIPT_ENCODING
        populator.setSqlScriptEncoding(SCRIPT_ENCODING);
        populator.setContinueOnError(false);
        populator.execute(dataSource);
    }

    /** 四张表是否还没建齐 */
    private boolean tablesMissing() throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME IN ('user','goods','orders','operation_log')";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, databaseName());
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next() || rs.getInt(1) < TABLES.length;
            }
        }
    }
}
