package com.xiaohua.performancetesting.service;

import com.xiaohua.performancetesting.entity.OperationLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * 日志的 IP 与角色现在是独立列（管理端表格要单列展示），由服务层从当前请求上下文补齐：
 * 4 个控制器不必各写一遍，也避免把 IP 混进 detail 里没法检索。
 */
class OperationLogServiceTest {

    private OperationLogService service;
    private ArgumentCaptor<OperationLog> captor;

    @BeforeEach
    void setUp() {
        service = spy(new OperationLogService());
        // 脱离 Spring 时 @Value 不生效，手工给回默认配置
        org.springframework.test.util.ReflectionTestUtils.setField(service, "userActionsEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "trustForwardedHeaders", false);
        // save() 走 MyBatis-Plus，单测里打桩掉，只验证补齐的字段
        doReturn(true).when(service).save(any(OperationLog.class));
        captor = ArgumentCaptor.forClass(OperationLog.class);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void requestWith(String remote, String xff, Object role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        if (xff != null) request.addHeader("X-Forwarded-For", xff);
        if (role != null) request.setAttribute("role", role);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private OperationLog captured() {
        verify(service).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("自动补 ip 与 role：IPv6 回环记成 127.0.0.1，角色取自 Token 声明")
    void fillsIpAndRole() {
        requestWith("0:0:0:0:0:0:0:1", null, 1);
        service.log(1L, "admin", "ADMIN_LOGIN", "管理员登录");
        OperationLog saved = captured();
        assertEquals("127.0.0.1", saved.getIp());
        assertEquals(1, saved.getRole());
        assertEquals("管理员登录", saved.getDetail());
        assertEquals(1L, saved.getOperatorId());
    }

    @Test
    @DisplayName("默认不信任代理头：自造 XFF 不会变成审计 IP")
    void forwardedHeaderNotTrustedByDefault() {
        requestWith("203.0.113.77", "1.1.1.1", 0);
        service.log(2L, "test001", "USER_LOGIN", "用户登录");
        assertEquals("203.0.113.77", captured().getIp());
    }

    @Test
    @DisplayName("打开 ip.trust-forwarded-headers 后才采信 XFF 首跳")
    void forwardedHeaderTrustedWhenEnabled() {
        ReflectionTestUtils.setField(service, "trustForwardedHeaders", true);
        requestWith("10.0.0.1", "203.0.113.9, 10.0.0.2", 0);
        service.log(2L, "test001", "USER_LOGIN", "用户登录");
        assertEquals("203.0.113.9", captured().getIp());
    }

    @Test
    @DisplayName("普通用户日志同样带角色 0（列表里要区分管理员/用户）")
    void userRoleRecorded() {
        requestWith("127.0.0.1", null, 0);
        service.logUser(5L, "buyer", "PLACE_ORDER", "#1 订单号 x");
        OperationLog saved = captured();
        assertEquals(0, saved.getRole());
        assertEquals("buyer", saved.getUsername());
    }

    @Test
    @DisplayName("开关关闭时普通用户行为不写日志（管理员动作不受影响）")
    void userActionsSwitch() {
        requestWith("127.0.0.1", null, 0);
        ReflectionTestUtils.setField(service, "userActionsEnabled", false);
        service.logUser(5L, "buyer", "PLACE_ORDER", "x");
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).save(any(OperationLog.class));

        service.log(1L, "admin", "ADMIN_LOGIN", "x");
        org.mockito.Mockito.verify(service).save(any(OperationLog.class));
    }

    @Test
    @DisplayName("取不到请求上下文（如以后改异步写日志）时记 '-'，不抛异常")
    void noRequestContext() {
        RequestContextHolder.resetRequestAttributes();
        service.log(1L, "admin", "ADMIN_LOGIN", "管理员登录");
        OperationLog saved = captured();
        assertEquals("-", saved.getIp());
        assertNull(saved.getRole());
    }

    @Test
    @DisplayName("超长 detail 被截断到列宽（500）")
    void detailTruncated() {
        requestWith("127.0.0.1", null, 1);
        service.log(1L, "admin", "UPDATE_GOODS", "x".repeat(900));
        assertEquals(500, captured().getDetail().length());
    }

    @Test
    @DisplayName("写日志失败不能影响主业务（以前 detail 超长会把“新增成功”变成报错）")
    void saveFailureIsSwallowed() {
        requestWith("127.0.0.1", null, 1);
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(service).save(any(OperationLog.class));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.log(1L, "admin", "CREATE_GOODS", "新增商品 #9 x"));
    }
}
