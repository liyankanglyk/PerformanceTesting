package com.xiaohua.performancetesting.controller;

import com.xiaohua.performancetesting.entity.User;
import com.xiaohua.performancetesting.service.OperationLogService;
import com.xiaohua.performancetesting.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 登录审计：普通用户以前完全不记日志（只有 ADMIN_LOGIN），
 * 现在按角色分成 ADMIN_LOGIN / USER_LOGIN，用户端走可关闭的 logUser 通道。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserControllerTest {

    @Mock UserService userService;
    @Mock OperationLogService logService;

    MockMvc mvc;
    UserController controller;

    @BeforeEach
    void setUp() {
        controller = new UserController(userService, logService);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private User user(Long id, String name, Integer role) {
        User u = new User();
        u.setId(id);
        u.setUsername(name);
        u.setRole(role);
        return u;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(String username) {
        return post("/api/user/login")
                .header("ts", String.valueOf(System.currentTimeMillis()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"deadbeef\"}");
    }

    @Test
    @DisplayName("普通用户登录：写 USER_LOGIN（IP 由服务层另存独立列）")
    void normalUserLoginIsLogged() throws Exception {
        when(userService.login(eq("test001"), anyString(), anyString())).thenReturn("TOKEN");
        when(userService.getOne(any(), anyBoolean())).thenReturn(user(2L, "test001", 0));
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "trustForwardedHeaders", true);

        mvc.perform(login("test001").header("X-Forwarded-For", "203.0.113.9, 10.0.0.1"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.role").value(0));

        // 来源 IP 现在是独立列（由 OperationLogService 从请求上下文补），detail 不再拼 IP
        verify(logService).logUser(eq(2L), eq("test001"), eq("USER_LOGIN"),
                org.mockito.ArgumentMatchers.eq("用户登录"));
        verify(logService, never()).log(any(), any(), any(), any());
    }

    @Test
    @DisplayName("管理员登录：仍然写 ADMIN_LOGIN（不受用户端开关影响）")
    void adminLoginIsLogged() throws Exception {
        when(userService.login(eq("admin"), anyString(), anyString())).thenReturn("TOKEN");
        when(userService.getOne(any(), anyBoolean())).thenReturn(user(1L, "admin", 1));
        mvc.perform(login("admin"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.role").value(1));
        verify(logService).log(eq(1L), eq("admin"), eq("ADMIN_LOGIN"), argThat((String d) -> d.contains("管理员登录")));
        verify(logService, never()).logUser(any(), any(), any(), any());
    }

    @Test
    @DisplayName("role 为 null 的脏数据按普通用户处理，不会 NPE")
    void nullRoleTreatedAsUser() throws Exception {
        when(userService.login(eq("legacy"), anyString(), anyString())).thenReturn("TOKEN");
        when(userService.getOne(any(), anyBoolean())).thenReturn(user(8L, "legacy", null));
        mvc.perform(login("legacy"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.role").value(0));
        verify(logService).logUser(eq(8L), eq("legacy"), eq("USER_LOGIN"), anyString());
    }

    @Test
    @DisplayName("密码错误：401 且不写任何日志（避免被爆破尝试刷满日志表）")
    void failedLoginNotLogged() throws Exception {
        when(userService.login(anyString(), anyString(), anyString())).thenReturn(null);
        mvc.perform(login("admin")).andExpect(jsonPath("$.code").value(401));
        verify(logService, never()).log(any(), any(), any(), any());
        verify(logService, never()).logUser(any(), any(), any(), any());
    }

    @Test
    @DisplayName("登录成功但账号已被删除：401，不发 token")
    void userVanishedBetweenLoginAndLog() throws Exception {
        when(userService.login(eq("admin"), anyString(), anyString())).thenReturn("TOKEN");
        when(userService.getOne(any(), anyBoolean())).thenReturn(null);
        mvc.perform(login("admin")).andExpect(jsonPath("$.code").value(401));
        verify(logService, never()).log(any(), any(), any(), any());
    }

    // ---------- 退出登录（以前完全没有这个接口，所以退出从不进日志）----------

    @Test
    @DisplayName("审计 IP 不能被客户端伪造：默认不采信代理头")
    void spoofedForwardedHeaderIgnoredByDefault() throws Exception {
        when(userService.login(eq("test001"), anyString(), anyString())).thenReturn("TOKEN");
        when(userService.getOne(any(), anyBoolean())).thenReturn(user(2L, "test001", 0));
        mvc.perform(login("test001").header("X-Forwarded-For", "1.1.1.1"))
                .andExpect(jsonPath("$.code").value(200));
        verify(logService).logUser(eq(2L), eq("test001"), eq("USER_LOGIN"),
                org.mockito.ArgumentMatchers.eq("用户登录"));
    }

    @Test
    @DisplayName("普通用户退出：USER_LOGOUT + IPv4 写法")
    void userLogoutIsLogged() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "trustForwardedHeaders", true);
        when(userService.getById(2L)).thenReturn(user(2L, "test001", 0));
        mvc.perform(post("/api/user/logout").requestAttr("userId", 2L).requestAttr("username", "test001")
                        .header("X-Forwarded-For", "203.0.113.9, 10.0.0.1"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("已退出"));
        verify(logService).logUser(eq(2L), eq("test001"), eq("USER_LOGOUT"),
                org.mockito.ArgumentMatchers.eq("用户退出"));
    }

    @Test
    @DisplayName("管理员退出：ADMIN_LOGOUT 走必记通道")
    void adminLogoutIsAlwaysLogged() throws Exception {
        when(userService.getById(1L)).thenReturn(user(1L, "admin", 1));
        mvc.perform(post("/api/user/logout").requestAttr("userId", 1L).requestAttr("username", "admin"))
                .andExpect(jsonPath("$.code").value(200));
        verify(logService).log(eq(1L), eq("admin"), eq("ADMIN_LOGOUT"), argThat((String d) -> d.contains("管理员退出")));
        verify(logService, never()).logUser(any(), any(), any(), any());
    }

    @Test
    @DisplayName("IPv6 回环在日志里记成 127.0.0.1（而不是 0:0:0:0:0:0:0:1）")
    void loopbackLoggedAsIpv4() throws Exception {
        when(userService.login(eq("test001"), anyString(), anyString())).thenReturn("TOKEN");
        when(userService.getOne(any(), anyBoolean())).thenReturn(user(2L, "test001", 0));
        mvc.perform(login("test001").with(r -> { r.setRemoteAddr("0:0:0:0:0:0:0:1"); return r; }))
                .andExpect(jsonPath("$.code").value(200));
        verify(logService).logUser(eq(2L), eq("test001"), eq("USER_LOGIN"),
                org.mockito.ArgumentMatchers.eq("用户登录"));
    }

    @Test
    @DisplayName("绕过拦截器（无 userId）：401 且不写无主日志")
    void logoutWithoutUserIsRejected() throws Exception {
        mvc.perform(post("/api/user/logout"))
                .andExpect(jsonPath("$.code").value(401));
        verify(logService, never()).log(any(), any(), any(), any());
        verify(logService, never()).logUser(any(), any(), any(), any());
    }
}
