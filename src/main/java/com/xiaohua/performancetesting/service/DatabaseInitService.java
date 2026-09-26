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
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 建库 + 灌初始数据的唯一入口。两条触发路径共用同一套逻辑：
 * <ol>
 *   <li>启动时按 {@code db.init-mode} 决定：always（每次重启清库重建，旧行为）/
 *       if-absent（只在库或表缺失时才建）/ never（完全不动）；</li>
 *   <li>压测跑完后由管理端按钮手动触发 {@code POST /api/admin/db/reset}。</li>
 * </ol>
 *
 * <p>以前只有第 1 条：只要服务重启，上一轮压测数据就被冲掉，想留档对比做不到；
 * 而不重启又没法把订单/日志清干净重跑一轮。所以把初始化抽成可调用的动作，
 * 启动只做「按需」，重新灌数据变成一次显式操作（可审计、可关）。
 */
@Service
public class DatabaseInitService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitService.class);

    /** 连表结构一起重建：执行 schema.sql（内含 DROP TABLE IF EXISTS）+ data.sql */
    public static final String MODE_FULL = "full";
    /** 只清数据：TRUNCATE 四张表 + data.sql，保留表结构与索引（DDL 没改时用这个，更快） */
    public static final String MODE_DATA = "data";

    /**
     * SQL 脚本的读取字符集，必须是 UTF-8。
     * 不显式指定的话 ResourceDatabasePopulator 会用 JVM 平台默认字符集读文件：
     * 中文 Windows（GBK）上 data.sql 里的 UTF-8 字节会被按 GBK 解码，
     * 灌进库的商品名就变成「鑻规灉 iPhone 16 Pro Max」。Java 18+ 默认才是 UTF-8，本项目跑 Java 17。
     */
    static final String SCRIPT_ENCODING = StandardCharsets.UTF_8.name();

    /** 表都不多，重建成本无所谓；顺序按“先删被引用的”排，逻辑上更清楚（实际没有外键） */
    private static final String[] TABLES = {"operation_log", "orders", "goods", "`user`"};

    private final DataSource dataSource;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    @Value("${spring.datasource.url}")
    private String url;
    @Value("${spring.datasource.username}")
    private String dbUser;
    @Value("${spring.datasource.password}")
    private String dbPass;

    /** 启动时策略，默认 always 以保持既有行为与已有 JMeter 文档一致 */
    @Value("${db.init-mode:always}")
    private String initModeRaw;

    /** 手动重置开关：正式环境或共享库可以关掉，避免被误触发（关掉后接口返回 403） */
    @Value("${db.reset.enabled:true}")
    private boolean resetEnabled;

    private volatile StartupMode startupMode = StartupMode.ALWAYS;
    /** 重置时间只能记在进程里：库都被重建了，没有地方持久化 */
    private volatile String lastResetAt;
    private volatile String lastResetMode;
    private volatile Long lastResetMs;

    public DatabaseInitService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public enum StartupMode {
        ALWAYS, IF_ABSENT, NEVER
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

    public StartupMode startupMode() {
        return startupMode;
    }

    public boolean isResetEnabled() {
        return resetEnabled;
    }

    public boolean isBusy() {
        return busy.get();
    }

    public String databaseName() {
        String dbName = url.substring(url.lastIndexOf('/') + 1);
        int q = dbName.indexOf('?');
        return q >= 0 ? dbName.substring(0, q) : dbName;
    }

    // ==================== 启动 ====================

    @Override
    public void run(ApplicationArguments args) throws Exception {
        startupMode = parseStartupMode(initModeRaw);
        ensureDatabase();
        switch (startupMode) {
            case ALWAYS -> {
                runSchema();
                runSeed();
                log.info("startup init: schema + data rebuilt (db.init-mode=always)");
            }
            case IF_ABSENT -> {
                if (tablesMissing()) {
                    runSchema();
                    runSeed();
                    log.info("startup init: tables were missing, schema + data created");
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
            // 表都没建（比如 db.init-mode=never 且从没手动建过库）：data 模式没有意义，直接建表
            if (MODE_DATA.equals(m) && tablesMissing()) {
                m = MODE_FULL;
                downgradedToFull = true;
            }
            if (MODE_FULL.equals(m)) {
                runSchema();
            } else {
                truncateData();
            }
            runSeed();

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

    /** 概览页/接口展示用的状态：不含任何口令信息 */
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

    private void runSchema() {
        executeScript("schema.sql");
    }

    private void runSeed() {
        executeScript("data.sql");
    }

    private void executeScript(String name) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource(name));
        // 必须显式指定 UTF-8：不设的话 Spring 用平台默认字符集读脚本文件，
        // 中文 Windows（GBK）上会把 data.sql 里的 UTF-8 字节当 GBK 解码，
        // 灌进去的商品名就是 “鑻规灉 iPhone 16 Pro Max” 这种乱码（Java 18+ 默认 UTF-8 才会暂藏这个坑）。
        populator.setSqlScriptEncoding(SCRIPT_ENCODING);
        populator.setContinueOnError(false);
        populator.execute(dataSource);
    }

    /** 只清数据、不动表结构与索引；四张表都清，回到“刚建库”的状态 */
    private void truncateData() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String t : TABLES) {
                st.execute("TRUNCATE TABLE " + t);
            }
        }
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
