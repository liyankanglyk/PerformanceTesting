package com.xiaohua.performancetesting.interceptor;

import com.xiaohua.performancetesting.entity.User;
import com.xiaohua.performancetesting.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 管理端权限拦截器：角色/账号状态以数据库为准，
 * 修复“改了角色或删了账号，旧 Token 在 1 小时内仍然是管理员”。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminInterceptorTest {

    @Mock UserService userService;

    AdminInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AdminInterceptor(userService);
    }

    private MockHttpServletRequest req(Integer tokenRole, Long userId) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/admin/users");
        r.setAttribute("role", tokenRole);
        r.setAttribute("userId", userId);
        r.setAttribute("username", "admin");
        return r;
    }

    private User user(Integer role) {
        User u = new User();
        u.setId(1L);
        u.setUsername("admin");
        u.setRole(role);
        return u;
    }

    @Test
    @DisplayName("Token role=0 直接 403，不查数据库")
    void nonAdminTokenRejected() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req(0, 1L), res, new Object()));
        assertEquals(HttpServletResponse.SC_FORBIDDEN, res.getStatus());
        assertTrue(res.getContentAsString().contains("\"code\":403"));
    }

    @Test
    @DisplayName("Token 里是管理员但数据库已被降级 -> 立即 403")
    void demotedAccountLosesAdminAtOnce() throws Exception {
        when(userService.getById(any(java.io.Serializable.class))).thenReturn(user(0));
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req(1, 1L), res, new Object()));
        assertTrue(res.getContentAsString().contains("role has been changed"));
    }

    @Test
    @DisplayName("Token 有效但账号已被删除 -> 401，前端会跳登录页")
    void deletedAccountRejected() throws Exception {
        when(userService.getById(any(java.io.Serializable.class))).thenReturn(null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req(1, 1L), res, new Object()));
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, res.getStatus());
        assertTrue(res.getContentAsString().contains("\"code\":401"));
    }

    @Test
    @DisplayName("数据库中仍是管理员 -> 放行，并以数据库值刷新请求属性")
    void adminAllowed() throws Exception {
        when(userService.getById(any(java.io.Serializable.class))).thenReturn(user(1));
        MockHttpServletRequest request = req(1, 1L);
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request, res, new Object()));
        assertEquals(1, request.getAttribute("role"));
        assertEquals("admin", request.getAttribute("username"));
    }

    @Test
    @DisplayName("OPTIONS 预检请求放行")
    void optionsAllowed() throws Exception {
        MockHttpServletRequest r = new MockHttpServletRequest("OPTIONS", "/api/admin/users");
        assertTrue(interceptor.preHandle(r, new MockHttpServletResponse(), new Object()));
    }
}
