package com.xiaohua.performancetesting.controller;

import com.xiaohua.performancetesting.entity.Orders;
import com.xiaohua.performancetesting.entity.User;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 用户端行为审计：普通用户下单必须留下 PLACE_ORDER 日志，
 * 且走 logUser（受 operation-log.user-actions-enabled 开关控制）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderControllerTest {

    @Mock OrderService orderService;
    @Mock UserService userService;
    @Mock OperationLogService logService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(orderService, userService, logService)).build();
    }

    private User user(Long id, String name) {
        User u = new User();
        u.setId(id);
        u.setUsername(name);
        u.setRole(0);
        return u;
    }

    private Orders order() {
        Orders o = new Orders();
        o.setId(77L);
        o.setOrderNo("aabbccddeeff00112233445566778899");
        o.setUserId(5L);
        o.setGoodsId(3L);
        o.setPayPrice(new BigDecimal("12.30"));
        o.setStatus(0);
        return o;
    }

    @Test
    @DisplayName("下单成功：写 PLACE_ORDER 日志，带订单号/商品/金额")
    void buyLogsPlaceOrder() throws Exception {
        when(userService.getById(5L)).thenReturn(user(5L, "test001"));
        when(orderService.buy(5L, 3L)).thenReturn(order());

        mvc.perform(post("/api/order/buy").requestAttr("userId", 5L).requestAttr("username", "test001")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"goodsId\":3}"))
                .andExpect(jsonPath("$.code").value(200));

        verify(logService).logUser(eq(5L), eq("test001"), eq("PLACE_ORDER"),
                argThat((String d) -> d.contains("aabbccddeeff00112233445566778899")
                        && d.contains("商品 #3") && d.contains("12.30")));
    }

    @Test
    @DisplayName("账号已被删除：401 且不写日志（不产生幽灵订单）")
    void buyWithDeletedAccountNoLog() throws Exception {
        when(userService.getById(9L)).thenReturn(null);
        mvc.perform(post("/api/order/buy").requestAttr("userId", 9L).requestAttr("username", "ghost")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"goodsId\":3}"))
                .andExpect(jsonPath("$.code").value(401));
        verify(logService, never()).logUser(any(), any(), any(), any());
        verify(orderService, never()).buy(any(), any());
    }

    @Test
    @DisplayName("库存不足：返回业务错误，不写成功日志")
    void buyFailureNotLogged() throws Exception {
        when(userService.getById(5L)).thenReturn(user(5L, "test001"));
        when(orderService.buy(5L, 3L)).thenThrow(new RuntimeException("insufficient stock"));
        mvc.perform(post("/api/order/buy").requestAttr("userId", 5L).requestAttr("username", "test001")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"goodsId\":3}"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("insufficient stock"));
        verify(logService, never()).logUser(any(), any(), any(), any());
    }

    @Test
    @DisplayName("goodsId 缺失：400 且不写日志")
    void buyMissingGoodsId() throws Exception {
        mvc.perform(post("/api/order/buy").requestAttr("userId", 5L)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(400));
        verify(logService, never()).logUser(any(), any(), any(), any());
    }

    // ---------- 用户端确认支付（原 “管理端标记已支付” 迁移到这里）----------

    /** 真实订单号是 32 位十六进制串，测试里用等长的数字串，避免把“短得非法”的值当成正常用例 */
    private static String no(long id) {
        return String.format("%032d", id);
    }

    private Orders order(Long id, Long userId, Integer status) {
        Orders o = new Orders();
        o.setId(id);
        o.setOrderNo(no(id));
        o.setUserId(userId);
        o.setGoodsId(3L);
        o.setPayPrice(new BigDecimal("12.30"));
        o.setStatus(status);
        return o;
    }

    @Test
    @DisplayName("支付自己的订单：条件更新 0->1 成功，并写 PAY_ORDER 日志")
    void payOwnOrder() throws Exception {
        when(orderService.getOne(any(), eq(false))).thenReturn(order(77L, 5L, 0));
        when(orderService.update(any())).thenReturn(true);
        when(orderService.getById(77L)).thenReturn(order(77L, 5L, 1));

        mvc.perform(put("/api/order/pay/" + no(77)).requestAttr("userId", 5L).requestAttr("username", "test001"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("paid"))
                .andExpect(jsonPath("$.data.status").value(1));

        // 支付是资金状态变更：走必记通道，不受 operation-log.user-actions-enabled 影响
        verify(logService).log(eq(5L), eq("test001"), eq("PAY_ORDER"),
                argThat((String d) -> d.contains("确认支付") && d.contains(no(77)) && d.contains("12.30")));
        verify(logService, never()).logUser(any(), any(), eq("PAY_ORDER"), any());
    }

    @Test
    @DisplayName("付别人的订单：403，不改状态不写日志")
    void paySomeoneElsosOrder() throws Exception {
        when(orderService.getOne(any(), eq(false))).thenReturn(order(78L, 99L, 0));
        mvc.perform(put("/api/order/pay/" + no(78)).requestAttr("userId", 5L).requestAttr("username", "test001"))
                .andExpect(jsonPath("$.code").value(403));
        verify(orderService, never()).update(any());
        verify(logService, never()).logUser(any(), any(), any(), any());
    }

    @Test
    @DisplayName("订单不存在：404")
    void payUnknownOrder() throws Exception {
        when(orderService.getOne(any(), eq(false))).thenReturn(null);
        mvc.perform(put("/api/order/pay/" + no(999)).requestAttr("userId", 5L))
                .andExpect(jsonPath("$.code").value(404));
        verify(orderService, never()).update(any());
    }

    @Test
    @DisplayName("UPDATE 谓词带 user_id：不依赖“读一次再改”的时序")
    void payUpdateScopedToOwner() throws Exception {
        when(orderService.getOne(any(), eq(false))).thenReturn(order(81L, 5L, 0));
        when(orderService.update(any())).thenReturn(true);
        when(orderService.getById(81L)).thenReturn(order(81L, 5L, 1));
        mvc.perform(put("/api/order/pay/" + no(81)).requestAttr("userId", 5L).requestAttr("username", "test001"))
                .andExpect(jsonPath("$.code").value(200));
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<Orders>> cap =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(orderService).update(cap.capture());
        String sql = String.valueOf(cap.getValue().getSqlSegment());
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("user_id"), "UPDATE 条件缺少归属列: " + sql);
    }

    @Test
    @DisplayName("重复提交（双击/重试）：第二次 500 且不重复写日志")
    void payAlreadyPaid() throws Exception {
        when(orderService.getOne(any(), eq(false))).thenReturn(order(79L, 5L, 1));
        when(orderService.getById(79L)).thenReturn(order(79L, 5L, 1));
        when(orderService.update(any())).thenReturn(false);      // status 已不是 0
        mvc.perform(put("/api/order/pay/" + no(79)).requestAttr("userId", 5L).requestAttr("username", "test001"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("order already paid"));
        verify(logService, never()).log(any(), any(), any(), any());
    }

    @Test
    @DisplayName("订单在支付瞬间被删除：报 404 而不是“已支付”")
    void payOrderVanishedMidUpdate() throws Exception {
        when(orderService.getOne(any(), eq(false))).thenReturn(order(82L, 5L, 0));
        when(orderService.update(any())).thenReturn(false);
        when(orderService.getById(82L)).thenReturn(null);        // 行已经不在了
        mvc.perform(put("/api/order/pay/" + no(82)).requestAttr("userId", 5L))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("order not found"));
    }

    @Test
    @DisplayName("订单号格式非法：400 且不查库（白名单正则，编码过的注入串也一样拦）")
    void payRejectsBadOrderNo() throws Exception {
        mvc.perform(put("/api/order/pay/bad_order_01").requestAttr("userId", 5L))
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(put("/api/order/pay/1%27%20or%20%271%27%3D%271").requestAttr("userId", 5L))
                .andExpect(jsonPath("$.code").value(400));
        verify(orderService, never()).getOne(any(), anyBoolean());
        mvc.perform(put("/api/order/pay/short").requestAttr("userId", 5L))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("没有 userId 属性（绕过了拦截器）：401")
    void payWithoutUser() throws Exception {
        mvc.perform(put("/api/order/pay/" + no(80)))
                .andExpect(jsonPath("$.code").value(401));
        verify(orderService, never()).getOne(any(), anyBoolean());
    }
}
