package com.xiaohua.performancetesting.service;

import com.xiaohua.performancetesting.service.DatabaseInitService.StartupMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动开关与手动重置的前置校验。
 * 这些用例都停在“还没碰数据库”的阶段，所以不需要真实 MySQL；
 * 真库行为由 D:/tmp/init-live.mjs 端到端覆盖。
 */
class DatabaseInitServiceTest {

    private DatabaseInitService service(String url, String initMode, boolean resetEnabled) {
        DatabaseInitService s = new DatabaseInitService(null);
        ReflectionTestUtils.setField(s, "url", url);
        ReflectionTestUtils.setField(s, "initModeRaw", initMode);
        ReflectionTestUtils.setField(s, "resetEnabled", resetEnabled);
        return s;
    }

    @Test
    @DisplayName("SQL 脚本按 UTF-8 读取：中文 Windows（GBK 平台默认）上不能把商品名灌成乱码")
    void scriptsAreReadAsUtf8() throws Exception {
        assertEquals("UTF-8", DatabaseInitService.SCRIPT_ENCODING);
        String data = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/data.sql"), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(data.contains("苹果 iPhone 16 Pro Max"), "UTF-8 读取应得到正常中文");
        // 反证：同一份字节按 GBK 解码就会乱码——这正是线上「鑻规灉」的成因
        String gbk = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/resources/data.sql")),
                java.nio.charset.Charset.forName("GBK"));
        org.junit.jupiter.api.Assertions.assertFalse(gbk.contains("苹果"),
                "如果这条失败，说明 data.sql 已经不是 UTF-8 编码保存的");
        assertTrue(gbk.contains("鑻规灉") || !gbk.equals(data), "GBK 误读应产生不同结果，否则本用例无效");
    }

    @Test
    @DisplayName("db.init-mode 认得三种写法（含别名），默认/未知值退回 ALWAYS")
    void parsesStartupMode() {
        assertEquals(StartupMode.ALWAYS, DatabaseInitService.parseStartupMode("always"));
        assertEquals(StartupMode.ALWAYS, DatabaseInitService.parseStartupMode("ON"));
        assertEquals(StartupMode.ALWAYS, DatabaseInitService.parseStartupMode("true"));
        assertEquals(StartupMode.ALWAYS, DatabaseInitService.parseStartupMode(null));
        assertEquals(StartupMode.ALWAYS, DatabaseInitService.parseStartupMode("   "));

        assertEquals(StartupMode.IF_ABSENT, DatabaseInitService.parseStartupMode("if-absent"));
        assertEquals(StartupMode.IF_ABSENT, DatabaseInitService.parseStartupMode(" IF_ABSENT "));
        assertEquals(StartupMode.IF_ABSENT, DatabaseInitService.parseStartupMode("missing"));

        assertEquals(StartupMode.NEVER, DatabaseInitService.parseStartupMode("never"));
        assertEquals(StartupMode.NEVER, DatabaseInitService.parseStartupMode("skip"));
        assertEquals(StartupMode.NEVER, DatabaseInitService.parseStartupMode("false"));

        // 打错字不能让服务起不来：退回 ALWAYS 并照常启动
        assertEquals(StartupMode.ALWAYS, DatabaseInitService.parseStartupMode("sometime"));
    }

    @Test
    @DisplayName("库名从 jdbc url 解析，带 ? 参数也能取对")
    void parsesDatabaseName() {
        assertEquals("performance_testing",
                service("jdbc:mysql://localhost:3380/performance_testing", "always", true).databaseName());
        assertEquals("performance_testing",
                service("jdbc:mysql://localhost:3380/performance_testing?useSSL=false", "always", true).databaseName());
    }

    @Test
    @DisplayName("重置模式只接受 full / data，其它一律拒绝（空值按 full 处理，不能静默不清库）")
    void rejectsUnknownResetMode() {
        DatabaseInitService s = service("jdbc:mysql://h/db", "always", true);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> s.reset("schema"));
        assertEquals("mode must be full or data", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> s.reset("FULLY"));
    }

    @Test
    @DisplayName("配置关了手动重置：直接拒绝，且不去连接数据库")
    void refusesWhenDisabled() {
        DatabaseInitService s = service("jdbc:mysql://h/db", "never", false);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.reset("full"));
        assertTrue(e.getMessage().contains("disabled"));
        assertEquals(false, s.isResetEnabled());
    }

    @Test
    @DisplayName("已有重置在跑：第二次请求拿 409 语义的异常，不会两个 TRUNCATE 交叉")
    void refusesWhenBusy() {
        DatabaseInitService s = service("jdbc:mysql://h/db", "always", true);
        ((AtomicBoolean) ReflectionTestUtils.getField(s, "busy")).set(true);
        assertTrue(s.isBusy());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.reset("data"));
        assertEquals("database is initializing, retry later", e.getMessage());
    }

    @Test
    @DisplayName("state() 只暴露策略与时间，不含数据源口令")
    void stateHasNoCredentials() {
        DatabaseInitService s = service("jdbc:mysql://h/db", "if-absent", true);
        ReflectionTestUtils.setField(s, "dbPass", "secret");
        // startupMode 由 run() 在启动时解析；这里模拟“已经跑过启动阶段”
        ReflectionTestUtils.setField(s, "startupMode", StartupMode.IF_ABSENT);
        var state = s.state();
        assertEquals("IF_ABSENT", state.get("startupMode"));
        assertEquals("if-absent", state.get("initMode"));
        assertEquals("db", state.get("database"));
        assertEquals(true, state.get("resetEnabled"));
        assertEquals(false, state.get("tablesPresent"), "连不上库时应报“表未就绪”，而不是抛异常");
        org.junit.jupiter.api.Assertions.assertFalse(state.toString().contains("secret"),
                "状态里不能带出口令: " + state);
    }
}
