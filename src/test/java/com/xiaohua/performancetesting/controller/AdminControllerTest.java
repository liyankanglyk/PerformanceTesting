package com.xiaohua.performancetesting.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xiaohua.performancetesting.entity.Goods;
import com.xiaohua.performancetesting.entity.Orders;
import com.xiaohua.performancetesting.entity.User;
import com.xiaohua.performancetesting.service.GoodsService;
import com.xiaohua.performancetesting.service.OperationLogService;
import com.xiaohua.performancetesting.service.OrderService;
import com.xiaohua.performancetesting.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端接口的回归测试（不依赖数据库，Service 全部 mock）。
 * 覆盖修复后的参数校验与保护规则。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminControllerTest {

    @Mock UserService userService;
    @Mock GoodsService goodsService;
    @Mock OrderService orderService;
    @Mock OperationLogService logService;

    MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    /**
     * LambdaUpdateWrapper.set(...) 会立即解析实体列名，依赖 MyBatis-Plus 的 TableInfo 缓存。
     * 单元测试没有 SqlSessionFactory，这里手动初始化，保证与真实启动后的行为一致。
     */
    @org.junit.jupiter.api.BeforeAll
    static void initMybatisPlusCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Orders.class);
        TableInfoHelper.initTableInfo(assistant, User.class);
        TableInfoHelper.initTableInfo(assistant, Goods.class);
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AdminController(userService, goodsService, orderService, logService))
                .setControllerAdvice(new com.xiaohua.performancetesting.common.GlobalExceptionHandler())
                .build();
    }

    private User user(Long id, String name, Integer role) {
        User u = new User();
        u.setId(id);
        u.setUsername(name);
        u.setRole(role);
        return u;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder admin(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        // 模拟 JwtInterceptor 写入的请求属性（当前登录管理员 id=1 / admin）
        return builder.requestAttr("userId", 1L).requestAttr("username", "admin");
    }

    // ---------- 用户新增 ----------

    @Test
    @DisplayName("新增用户：缺少用户名/密码返回 400，不再打到数据库报 500")
    void createUser_missingFields() throws Exception {
        mvc.perform(admin(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", "")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
        verify(userService, never()).save(any(User.class));
    }

    @Test
    @DisplayName("新增用户：role 非 0/1 返回 400")
    void createUser_badRole() throws Exception {
        mvc.perform(admin(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"u1\",\"password\":\"p1\",\"role\":5}")))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("新增用户：用户名重复返回业务失败且提示明确")
    void createUser_duplicateName() throws Exception {
        when(userService.getOne(any(), anyBoolean())).thenReturn(user(2L, "u1", 0));
        mvc.perform(admin(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"u1\",\"password\":\"p1\",\"role\":0}")))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("username already exists"));
        verify(userService, never()).save(any(User.class));
    }

    @Test
    @DisplayName("新增用户：成功后回填 createTime，响应不回显密码")
    void createUser_success() throws Exception {
        when(userService.getOne(any(), anyBoolean())).thenReturn(null);
        String body = mvc.perform(admin(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u1\",\"password\":\"p1\",\"role\":0}")))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.createTime").exists())
                .andReturn().getResponse().getContentAsString();
        assertTrue(json.readTree(body).path("data").path("password").isNull(), "响应中的密码必须脱敏: " + body);
        verify(userService).save(any(User.class));
        verify(logService).log(eq(1L), eq("admin"), eq("CREATE_USER"), anyString());
    }

    // ---------- 用户编辑 ----------

    @Test
    @DisplayName("编辑用户：不能把自己（当前登录管理员）降级")
    void updateUser_cannotDemoteSelf() throws Exception {
        when(userService.getById(1L)).thenReturn(user(1L, "admin", 1));
        mvc.perform(admin(put("/api/admin/users/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":0}")))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("cannot remove admin role from your own account"));
        verify(userService, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("编辑用户：不能把最后一个管理员降级")
    void updateUser_cannotDemoteLastAdmin() throws Exception {
        when(userService.getById(2L)).thenReturn(user(2L, "admin2", 1));
        when(userService.count(any())).thenReturn(1L);
        mvc.perform(admin(put("/api/admin/users/2").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":0}")))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("at least one admin account is required"));
    }

    @Test
    @DisplayName("编辑用户：请求体不能覆盖用户名与创建时间")
    void updateUser_ignoresUsernameAndCreateTime() throws Exception {
        when(userService.getById(3L)).thenReturn(user(3L, "test001", 0));
        mvc.perform(admin(put("/api/admin/users/3").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"hacked\",\"createTime\":\"2000-01-01T00:00:00\",\"password\":\"newpwd\"}")))
                .andExpect(jsonPath("$.code").value(200));
        verify(userService).updateById(argThat((User u) ->
                u.getUsername() == null && u.getCreateTime() == null && "newpwd".equals(u.getPassword())));
    }

    // ---------- 用户删除 ----------

    @Test
    @DisplayName("删除用户：不能删除当前登录账号")
    void deleteUser_cannotDeleteSelf() throws Exception {
        when(userService.getById(1L)).thenReturn(user(1L, "admin", 1));
        mvc.perform(admin(delete("/api/admin/users/1")))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("cannot delete your own account"));
        verify(userService, never()).removeById(anyLong());
    }

    @Test
    @DisplayName("删除用户：存在订单的用户不允许删除（避免订单悬空 user_id）")
    void deleteUser_hasOrders() throws Exception {
        when(userService.getById(4L)).thenReturn(user(4L, "test002", 0));
        when(orderService.count(any())).thenReturn(2L);
        mvc.perform(admin(delete("/api/admin/users/4")))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("user still has orders, delete orders first"));
        verify(userService, never()).removeById(anyLong());
    }

    // ---------- 商品 ----------

    @Test
    @DisplayName("编辑用户：空请求体不会生成非法 SQL（MP 无字段可更新）")
    void updateUser_emptyBody() throws Exception {
        when(userService.getById(3L)).thenReturn(user(3L, "test001", 0));
        mvc.perform(admin(put("/api/admin/users/3").contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(jsonPath("$.code").value(400));
        verify(userService, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("编辑商品：空请求体返回 400")
    void updateGoods_emptyBody() throws Exception {
        when(goodsService.getById(5L)).thenReturn(new Goods());
        mvc.perform(admin(put("/api/admin/goods/5").contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(jsonPath("$.code").value(400));
        verify(goodsService, never()).updateById(any(Goods.class));
    }

    @Test
    @DisplayName("新增商品：负库存/负单价被拒绝")
    void createGoods_invalidValues() throws Exception {
        mvc.perform(admin(post("/api/admin/goods").contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsName\":\"鼠标\",\"price\":-1,\"stock\":10}")))
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(admin(post("/api/admin/goods").contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsName\":\"鼠标\",\"price\":10,\"stock\":-5}")))
                .andExpect(jsonPath("$.code").value(400));
        verify(goodsService, never()).save(any(Goods.class));
    }

    @Test
    @DisplayName("新增商品：价格超出 DECIMAL(10,2) 范围被拒绝")
    void createGoods_priceTooLarge() throws Exception {
        mvc.perform(admin(post("/api/admin/goods").contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsName\":\"鼠标\",\"price\":100000000.00,\"stock\":1}")))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("新增商品：成功后响应体带 createTime")
    void createGoods_success() throws Exception {
        mvc.perform(admin(post("/api/admin/goods").contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsName\":\"鼠标\",\"price\":99.90,\"stock\":1}")))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.createTime").exists());
        verify(logService).log(eq(1L), eq("admin"), eq("CREATE_GOODS"), anyString());
    }

    @Test
    @DisplayName("删除商品：存在订单的商品不允许删除")
    void deleteGoods_hasOrders() throws Exception {
        when(goodsService.getById(5L)).thenReturn(new Goods());
        when(orderService.count(any())).thenReturn(1L);
        mvc.perform(admin(delete("/api/admin/goods/5")))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("goods still has orders, delete orders first"));
        verify(goodsService, never()).removeById(anyLong());
    }

    // ---------- 订单 ----------

    @Test
    @DisplayName("管理端不再提供“标记已支付”（支付是用户端行为，见 PUT /api/order/pay/{orderNo}）")
    void adminPayEndpointRemoved() {
        boolean stillMapped = java.util.Arrays.stream(AdminController.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(org.springframework.web.bind.annotation.PutMapping.class))
                .filter(java.util.Objects::nonNull)
                .anyMatch(p -> java.util.Arrays.stream(p.value()).anyMatch(v -> v.contains("pay")));
        org.junit.jupiter.api.Assertions.assertFalse(stillMapped, "管理端不应再存在支付类接口");
    }

    @Test
    @DisplayName("请求体不是合法 JSON：返回统一响应体 code=400（旧实现返回 Spring 默认错误体）")
    void brokenJsonUsesUnifiedResult() throws Exception {
        mvc.perform(admin(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON).content("{username:")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("invalid request body"));
    }

    @Test
    @DisplayName("参数类型不匹配：必须 400 + 可读文案（旧实现落在 500 并且把 Java 转换异常原文吐给前端）")
    void typeMismatchIsBadRequest() throws Exception {
        mvc.perform(admin(get("/api/admin/users").param("page", "abc")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("invalid parameter: page"))
                // 内部信息不能漏：ConversionFailedException 原文带类名与 Target type
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Failed to convert"))));
        mvc.perform(admin(get("/api/admin/goods").param("size", "1.5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("invalid parameter: size"));
    }

    @Test
    @DisplayName("编辑用户：空白密码视为“不修改”，不会把空格存成密码")
    void updateUser_blankPasswordIgnored() throws Exception {
        when(userService.getById(3L)).thenReturn(user(3L, "test001", 0));
        mvc.perform(admin(put("/api/admin/users/3").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":0,\"password\":\"    \"}")))
                .andExpect(jsonPath("$.code").value(200));
        verify(userService).updateById(argThat((User u) -> u.getPassword() == null && Integer.valueOf(0).equals(u.getRole())));
    }

    @Test
    @DisplayName("编辑用户：只有空白密码且不改角色 -> 400")
    void updateUser_onlyBlankPassword() throws Exception {
        when(userService.getById(3L)).thenReturn(user(3L, "test001", 0));
        mvc.perform(admin(put("/api/admin/users/3").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"   \"}")))
                .andExpect(jsonPath("$.code").value(400));
        verify(userService, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("新增用户：纯空格用户名被拒绝")
    void createUser_spacesOnlyUsername() throws Exception {
        mvc.perform(admin(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"    \",\"password\":\"p\"}")))
                .andExpect(jsonPath("$.code").value(400));
        verify(userService, never()).save(any(User.class));
    }

    @Test
    @DisplayName("商品：超出两位小数被拒绝（DECIMAL(10,2) 会静默四舍五入）")
    void createGoods_priceScaleTooLarge() throws Exception {
        mvc.perform(admin(post("/api/admin/goods").contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsName\":\"鼠标\",\"price\":9.999,\"stock\":1}")))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("price supports at most 2 decimal places"));
        verify(goodsService, never()).save(any(Goods.class));
    }

    @Test
    @DisplayName("新增商品：与已有商品同名直接 400，不落库也不记日志")
    void createGoods_duplicateName() throws Exception {
        Goods exists = new Goods();
        exists.setId(7L);
        exists.setGoodsName("鼠标");
        when(goodsService.getOne(any(), eq(false))).thenReturn(exists);

        mvc.perform(admin(post("/api/admin/goods").contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsName\":\"鼠标\",\"price\":99.90,\"stock\":1}")))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("goods name already exists"));

        verify(goodsService, never()).save(any(Goods.class));
        verify(logService, never()).log(any(), any(), eq("CREATE_GOODS"), any());
    }

    @Test
    @DisplayName("新增商品：并发抢同一名字时由唯一索引兜底，仍然是 400 而不是 500")
    void createGoods_duplicateFromDatabaseWin() throws Exception {
        // 先查没有、写入时撞索引：应用层的检查在并发下不可靠，这条测的就是兜底分支
        when(goodsService.getOne(any(), eq(false))).thenReturn(null);
        when(goodsService.save(any(Goods.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));

        mvc.perform(admin(post("/api/admin/goods").contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsName\":\"新商品\",\"price\":1.00,\"stock\":1}")))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("goods name already exists"));
    }

    @Test
    @DisplayName("编辑商品：改名撞上别人的商品名返回 400，不会把 500 抛给前端")
    void updateGoods_renameCollides() throws Exception {
        Goods db = new Goods();
        db.setId(5L);
        db.setGoodsName("旧名称");
        db.setPrice(new BigDecimal("100.00"));
        db.setStock(3);
        when(goodsService.getById(5L)).thenReturn(db);

        Goods other = new Goods();
        other.setId(9L);
        other.setGoodsName("鼠标");
        when(goodsService.getOne(any(), eq(false))).thenReturn(other);

        mvc.perform(admin(put("/api/admin/goods/5").contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsName\":\"鼠标\"}")))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("goods name already exists"));
        verify(goodsService, never()).updateById(any(Goods.class));
    }

    @Test
    @DisplayName("编辑商品：名字没改（查到的就是自己）不该被当成重名拒绝")
    void updateGoods_sameNameOnSelfIsAllowed() throws Exception {
        Goods db = new Goods();
        db.setId(5L);
        db.setGoodsName("鼠标");
        db.setPrice(new BigDecimal("100.00"));
        db.setStock(3);
        when(goodsService.getById(5L)).thenReturn(db);
        when(goodsService.getOne(any(), eq(false))).thenReturn(db);
        when(goodsService.updateById(any(Goods.class))).thenReturn(true);

        mvc.perform(admin(put("/api/admin/goods/5").contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsName\":\"鼠标\",\"stock\":8}")))
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("商品：只把库存改成 0 是合法的部分更新")
    void updateGoods_stockZeroOnly() throws Exception {
        Goods db = new Goods();
        db.setId(5L);
        db.setGoodsName("旧名称");
        db.setPrice(new BigDecimal("100.00"));
        db.setStock(3);
        when(goodsService.getById(5L)).thenReturn(db);
        mvc.perform(admin(put("/api/admin/goods/5").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stock\":0}")))
                .andExpect(jsonPath("$.code").value(200));
        verify(goodsService).updateById(argThat((Goods g) ->
                Integer.valueOf(0).equals(g.getStock()) && g.getGoodsName() == null && g.getPrice() == null && g.getCreateTime() == null));

        // 日志必须能回答“改了哪个商品、改了什么、从多少改成多少”
        ArgumentCaptor<String> goodsDetail = ArgumentCaptor.forClass(String.class);
        verify(logService).log(eq(1L), eq("admin"), eq("UPDATE_GOODS"), goodsDetail.capture());
        String gd = goodsDetail.getValue();
        org.junit.jupiter.api.Assertions.assertTrue(gd.contains("#5") && gd.contains("旧名称"), gd);
        org.junit.jupiter.api.Assertions.assertTrue(gd.contains("库存 3→0"), gd);
        org.junit.jupiter.api.Assertions.assertFalse(gd.contains("单价"), "未提交的字段不该出现在变更里: " + gd);
    }

    @Test
    @DisplayName("管理端有 GET /admin/goods（旧版只有用户端 /goods/list，按文档调管理端 405）")
    void listGoodsEndpoint() throws Exception {
        when(goodsService.page(any(), any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<Goods> pg = inv.getArgument(0);
            Goods g = new Goods();
            g.setId(5L);
            g.setGoodsName("手机");
            pg.setRecords(java.util.List.of(g));
            pg.setTotal(1);
            return pg;
        });
        mvc.perform(admin(get("/api/admin/goods").param("keyword", "手机")))
                .andExpect(jsonPath("$.code").value(200))
                // 管理端商品列表已改服务端分页：响应体是 {records,total,page,size,pages}
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.total").value(1));
        verify(goodsService).page(any(), any());
    }

    @Test
    @DisplayName("删除订单：200 并把订单快照写进日志")
    void deleteOrder_success() throws Exception {
        Orders o = new Orders();
        o.setId(9L);
        o.setOrderNo("abc123");
        o.setUserId(2L);
        o.setGoodsId(5L);
        o.setPayPrice(new BigDecimal("66.60"));
        o.setStatus(0);
        when(orderService.getById(9L)).thenReturn(o);
        mvc.perform(admin(delete("/api/admin/orders/9")))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("deleted"));
        verify(orderService).removeById(9L);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(logService).log(eq(1L), eq("admin"), eq("DELETE_ORDER"), detail.capture());
        String d = detail.getValue();
        for (String expect : new String[]{"abc123", "#2", "#5", "66.60", "未支付"}) {
            org.junit.jupiter.api.Assertions.assertTrue(d.contains(expect), "日志缺少「" + expect + "」: " + d);
        }
    }

    @Test
    @DisplayName("删除订单：订单不存在")
    void deleteOrder_notFound() throws Exception {
        when(orderService.getById(98L)).thenReturn(null);
        mvc.perform(admin(delete("/api/admin/orders/98")))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("order not found"));
        verify(orderService, never()).removeById(anyLong());
    }

    @Test
    @DisplayName("批量清理订单：缺参被拒；成功时返回删除条数")
    void deleteOrdersByOwner() throws Exception {
        mvc.perform(admin(delete("/api/admin/orders")))
                .andExpect(jsonPath("$.code").value(400));

        when(orderService.deleteBy(any())).thenReturn(3);   // 真实删除行数，而不是删除前的 count 快照
        when(orderService.list((com.baomidou.mybatisplus.core.conditions.Wrapper<Orders>) any())).thenReturn(java.util.List.of(
                sampleOrder(41L, "aaa111", "5.00", 0),
                sampleOrder(42L, "bbb222", "12.30", 1)));
        mvc.perform(admin(delete("/api/admin/orders").param("userId", "7")))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(3));
        verify(orderService).deleteBy(any());
        verify(logService).log(eq(1L), eq("admin"), eq("DELETE_ORDER"),
                argThat((String d) -> d.contains("3 条") && d.contains("用户 #7")
                        // 批量删除也要能追溯到具体单据（旧版只记条数，事后无法对账）
                        && d.contains("aaa111") && d.contains("bbb222")
                        && d.contains("¥12.30") && d.contains("已支付") && d.contains("未支付")
                        && d.contains("等共 3 条")));
    }

    private Orders sampleOrder(Long id, String no, String price, Integer status) {
        Orders o = new Orders();
        o.setId(id);
        o.setOrderNo(no);
        o.setPayPrice(new java.math.BigDecimal(price));
        o.setStatus(status);
        return o;
    }

    @Test
    @DisplayName("批量清理订单：无匹配时不动数据也不写日志")
    void deleteOrdersNoneMatched() throws Exception {
        when(orderService.deleteBy(any())).thenReturn(0);
        mvc.perform(admin(delete("/api/admin/orders").param("goodsId", "7")))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("no orders matched"));
        verify(orderService).deleteBy(any());
        verify(logService, never()).log(any(), any(), eq("DELETE_ORDER"), any());
    }

    @Test
    @DisplayName("编辑用户日志：记下用户名与字段变更前后")
    void updateUserLogDescribesChange() throws Exception {
        when(userService.getById(3L)).thenReturn(user(3L, "test001", 0));
        mvc.perform(admin(put("/api/admin/users/3").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":1,\"password\":\"newpwd\"}")))
                .andExpect(jsonPath("$.code").value(200));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(logService).log(eq(1L), eq("admin"), eq("UPDATE_USER"), detail.capture());
        String d = detail.getValue();
        org.junit.jupiter.api.Assertions.assertTrue(d.contains("#3") && d.contains("test001"), d);
        org.junit.jupiter.api.Assertions.assertTrue(d.contains("角色 普通用户→管理员"), d);
        org.junit.jupiter.api.Assertions.assertTrue(d.contains("密码已重置"), d);
    }

    @Test
    @DisplayName("重复提交同样的值：日志标出“无实际变更”")
    void updateUserLogMarksNoChange() throws Exception {
        when(userService.getById(3L)).thenReturn(user(3L, "test001", 1));
        when(userService.count(any())).thenReturn(2L);
        mvc.perform(admin(put("/api/admin/users/3").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":1}")))
                .andExpect(jsonPath("$.code").value(200));
        verify(logService).log(eq(1L), eq("admin"), eq("UPDATE_USER"),
                argThat((String d) -> d.contains("无实际变更")));
    }

    @Test
    @DisplayName("新增/删除日志带上 ID 与名称（旧版只有 username=xxx / id=1）")
    void createAndDeleteLogsCarryIdentity() throws Exception {
        when(userService.getOne(any(), anyBoolean())).thenReturn(null);
        mvc.perform(admin(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newone\",\"password\":\"p\",\"role\":1}")))
                .andExpect(jsonPath("$.code").value(200));
        verify(logService).log(eq(1L), eq("admin"), eq("CREATE_USER"),
                argThat((String d) -> d.contains("newone") && d.contains("管理员")));

        when(userService.getById(4L)).thenReturn(user(4L, "u4", 0));
        when(orderService.count(any())).thenReturn(0L);
        mvc.perform(admin(delete("/api/admin/users/4")))
                .andExpect(jsonPath("$.code").value(200));
        verify(logService).log(eq(1L), eq("admin"), eq("DELETE_USER"),
                argThat((String d) -> d.contains("#4") && d.contains("u4")));
    }

    @Test
    @DisplayName("回归：提交未变化的值（MySQL 返回 0 行）不能被当成“用户不存在”")
    void updateUser_noOpChangeStillSucceeds() throws Exception {
        when(userService.getById(3L)).thenReturn(user(3L, "test001", 0));
        when(userService.updateById(any(User.class))).thenReturn(false);   // 值未变 -> affected rows 0
        mvc.perform(admin(put("/api/admin/users/3").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":0}")))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("updated"));
    }

    @Test
    @DisplayName("编辑时目标记录已不存在：不再谎报成功")
    void updateUser_rowDisappears() throws Exception {
        // 第一次 getById 负在存在，更新后再次 getById 时已被其他请求删除
        when(userService.getById(3L)).thenReturn(user(3L, "test001", 0), (User) null);
        when(userService.updateById(any(User.class))).thenReturn(false);
        mvc.perform(admin(put("/api/admin/users/3").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":1}")))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("user not found"));
    }

    // ---------- 分页 ----------

    @Test
    @DisplayName("用户列表：page/size 归一后传给 MyBatis-Plus 的 Page")
    void listUsersNormalizesPaging() throws Exception {
        when(userService.page(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        mvc.perform(admin(get("/api/admin/users").param("page", "0").param("size", "99999")))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(200))
                .andExpect(jsonPath("$.data.records").isArray());
        ArgumentCaptor<com.baomidou.mybatisplus.core.metadata.IPage<User>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.metadata.IPage.class);
        verify(userService).page(cap.capture(), any());
        org.junit.jupiter.api.Assertions.assertEquals(1L, cap.getValue().getCurrent());
        org.junit.jupiter.api.Assertions.assertEquals(200L, cap.getValue().getSize());
    }

    @Test
    @DisplayName("用户列表：响应是 {records,total,page,size,pages}，不再是裸数组")
    void listUsersReturnsPageObject() throws Exception {
        when(userService.page(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        mvc.perform(admin(get("/api/admin/users")))
                .andExpect(jsonPath("$.data.records.length()").value(0))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.pages").value(0));
    }

    @Test
    @DisplayName("订单列表：status 只能是 0/1")
    void listOrdersRejectsBadStatus() throws Exception {
        mvc.perform(admin(get("/api/admin/orders").param("status", "5")))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("status must be 0 or 1"));
        verify(orderService, never()).page(any(), any());
    }

    @Test
    @DisplayName("订单列表：默认第 1 页 20 条，且带 create_ts+id 稳定排序")
    void listOrdersDefaults() throws Exception {
        when(orderService.page(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        mvc.perform(admin(get("/api/admin/orders")))
                .andExpect(jsonPath("$.data.page").value(1))
                // 需求：每页 10 条
                .andExpect(jsonPath("$.data.size").value(10));
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<Orders>> wc =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(orderService).page(any(), wc.capture());
        String sql = wc.getValue().getTargetSql();
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("create_ts DESC"), sql);
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("id DESC"), "必须有第二排序键，否则同毫秒下单翻页会重复/漏行: " + sql);
    }

    @Test
    @DisplayName("分页归一函数：0/负数页码按 1，size 夹在 1~200")
    void normalizeHelpers() {
        org.junit.jupiter.api.Assertions.assertEquals(1L, AdminController.normalizePage(0));
        org.junit.jupiter.api.Assertions.assertEquals(1L, AdminController.normalizePage(-7));
        org.junit.jupiter.api.Assertions.assertEquals(9L, AdminController.normalizePage(9));
        org.junit.jupiter.api.Assertions.assertEquals(1L, AdminController.normalizeSize(0));
        org.junit.jupiter.api.Assertions.assertEquals(200L, AdminController.normalizeSize(999999));
        org.junit.jupiter.api.Assertions.assertEquals(20L, AdminController.normalizeSize(20));
    }

    @Test
    @DisplayName("价格精度：BigDecimal 原样返回两位小数")
    void goodsPriceScale() {
        BigDecimal price = new BigDecimal("9999.00");
        org.junit.jupiter.api.Assertions.assertEquals("9999.00", price.toPlainString());
    }
}
