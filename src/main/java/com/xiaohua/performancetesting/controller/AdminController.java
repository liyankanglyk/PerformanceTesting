package com.xiaohua.performancetesting.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xiaohua.performancetesting.common.PageResult;
import com.xiaohua.performancetesting.common.Result;
import com.xiaohua.performancetesting.config.MyBatisPlusConfig;
import com.xiaohua.performancetesting.entity.Goods;
import com.xiaohua.performancetesting.entity.Orders;
import com.xiaohua.performancetesting.entity.User;
import com.xiaohua.performancetesting.service.GoodsService;
import com.xiaohua.performancetesting.service.OperationLogService;
import com.xiaohua.performancetesting.service.OrderService;
import com.xiaohua.performancetesting.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理端 CRUD：用户、商品、订单、以及日志相关的查询入口。
 *
 * <p>鉴权是两层的（见 WebConfig）：JwtInterceptor 先验签失败 401，
 * AdminInterceptor 再按 Token 里的 role 判定，非管理员 403。
 * 当前登录人一律从 request 属性取（userId / username / role），不接受参数传 ID。
 *
 * <p>列表接口统一用 {@code page}/{@code size} 服务端分页，size 默认 10、
 * 上限 {@link com.xiaohua.performancetesting.config.MyBatisPlusConfig#MAX_PAGE_SIZE}；
 * 越界页码返回空列表但 total 正确，不回绕到第一页。
 *
 * <p>写操作只在成功后记审计日志（失败原因五花八门，记下来只会污染审计）。
 *
 * <p>删除与降权有保护规则，命中时返回 500 带英文原因，由前端翻译成中文提示：
 * <ul>
 *   <li>不能删除当前登录账号自己（cannot delete your own account）</li>
 *   <li>不能把自己的管理员角色去掉（cannot remove admin role from your own account）</li>
 *   <li>用户名下还有订单时不能删用户，商品还有订单时不能删商品，要先删订单</li>
 * </ul>
 * 注意没有“必须留一个管理员”这种规则：库里可以只剩你自己，删自己是唯一会被挡住的情况。
 *
 * <p>口令按项目最初的实现原样入库（明文，见 User#password），这里不做任何摘要转换。
 * 也就是说创建/改密码接口要传口令原文，别把登录用的 MD5(口令 + ts) 摘要存进去，
 * 那样用户下次登录就永远算不出同样的摘要了。演示用途，不要照搬到生产。
 */
@Tag(name = "Admin", description = "管理端接口 — 用户/商品/订单管理（需管理员 Token）")
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    /** user.username -> VARCHAR(50)，user.password -> VARCHAR(100) */
    private static final int MAX_USERNAME_LEN = 50;
    /** user.password 列宽 100，先按它校验，别让数据库抛 1406 给前端 */
    private static final int MAX_PASSWORD_LEN = 100;
    /** goods.goods_name -> VARCHAR(200) */
    private static final int MAX_GOODS_NAME_LEN = 200;
    /** goods.price -> DECIMAL(10,2) */
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");

    /** 用户的增删改查与角色校验 */
    private final UserService userService;
    /** 商品列表查询与增删改 */
    private final GoodsService goodsService;
    /** 订单列表与按条件删除（要真实删除行数） */
    private final OrderService orderService;
    /** 写操作成功后的审计入口 */
    private final OperationLogService logService;

    /** 构造注入四个服务（用户、商品、订单、审计），全部 final，Spring 单构造器无需 @Autowired。 */
    public AdminController(UserService userService, GoodsService goodsService,
                           OrderService orderService, OperationLogService logService) {
        this.userService = userService;
        this.goodsService = goodsService;
        this.orderService = orderService;
        this.logService = logService;
    }

    // ==================== User Management ====================

    /** 关键词匹配登录名，按 id 升序分页；只查 role 之外的列没意义，密码字段照旧返回（演示项目存的就是明文）。 */
    @Operation(
        summary = "用户列表（分页）",
        description = """
            分页查询用户，支持按用户名模糊搜索（密码字段已脱敏）。

            - `page`：页码，从 1 开始（传 0/负数按 1 处理）
            - `size`：每页条数，默认 20，上限 200（超出自动夹住）
            - 返回结构：`data = {records, total, page, size, pages}`
            """
    )
    @ApiResponse(responseCode = "200", description = "成功")
    @ApiResponse(responseCode = "401", description = "Token 缺失或无效")
    @ApiResponse(responseCode = "403", description = "非管理员无权限")
    @GetMapping("/users")
    public Result<PageResult<User>> listUsers(@RequestParam(required = false) String keyword,
                                              @RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "10") long size) {
        LambdaQueryWrapper<User> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) w.like(User::getUsername, keyword.trim());
        w.orderByAsc(User::getId);

        Page<User> pg = userService.page(new Page<>(normalizePage(page), normalizeSize(size)), w);
        pg.getRecords().forEach(u -> u.setPassword(null));
        return Result.ok(PageResult.of(pg));
    }

    /** 口令原文入库，不做摘要转换（见 User#password）。重名会被唯一索引拦下。 */
    @Operation(
        summary = "新增用户",
        description = """
            创建新用户。密码明文存储（演示项目），role 默认为 0（普通用户）。

            校验：username / password 必填，role 只能是 0 或 1，用户名不可重复。

            **请求体示例**：`{"username":"newUser","password":"123456","role":0}`
            """
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
        required = true,
        content = @Content(
            examples = @ExampleObject(value = "{\"username\":\"newUser\",\"password\":\"123456\",\"role\":0}")
        )
    )
    @ApiResponse(responseCode = "200", description = "创建成功")
    @ApiResponse(responseCode = "400", description = "参数缺失或非法")
    @ApiResponse(responseCode = "500", description = "用户名已存在")
    @PostMapping(value = "/users", consumes = "application/json")
    public Result<?> createUser(@RequestBody User user, HttpServletRequest request) {
        String username = user.getUsername() == null ? "" : user.getUsername().trim();
        String password = user.getPassword();
        Integer role = user.getRole() == null ? 0 : user.getRole();

        if (username.isEmpty()) return Result.fail(400, "username is required");
        if (username.length() > MAX_USERNAME_LEN) return Result.fail(400, "username too long (max " + MAX_USERNAME_LEN + ")");
        if (!StringUtils.hasText(password)) return Result.fail(400, "password is required");
        if (password.length() > MAX_PASSWORD_LEN) return Result.fail(400, "password too long (max " + MAX_PASSWORD_LEN + ")");
        if (!isValidRole(role)) return Result.fail(400, "role must be 0 or 1");

        if (findByUsername(username) != null) return Result.fail("username already exists");

        user.setId(null);
        user.setUsername(username);
        user.setRole(role);
        user.setCreateTime(LocalDateTime.now());
        try {
            userService.save(user);
        } catch (DuplicateKeyException e) {
            // 并发创建同名用户时由数据库唯一索引兜底
            return Result.fail("username already exists");
        }

        logService.log(getAdminId(request), getAdminName(request), "CREATE_USER",
                "创建用户 #" + user.getId() + " " + username + "（角色: " + roleText(role) + "）");
        user.setPassword(null);
        return Result.ok("created", user);
    }

    /** 请求体里没填的字段不动；改密与改角色共用这一个入口，去掉自己管理员角色会被拒。 */
    @Operation(
        summary = "编辑用户",
        description = """
            更新用户信息。可修改密码和角色，用户名不允许修改。
            密码为空（或不传）时不更新密码字段。

            保护规则：不能取消自己的管理员角色；系统必须至少保留一个管理员。

            **请求体示例**：`{"password":"newPassword","role":1}`
            """
    )
    @ApiResponse(responseCode = "200", description = "更新成功")
    @ApiResponse(responseCode = "400", description = "参数非法")
    @ApiResponse(responseCode = "500", description = "用户不存在 / 违反保护规则")
    @PutMapping(value = "/users/{id}", consumes = "application/json")
    public Result<?> updateUser(@PathVariable Long id, @RequestBody User user, HttpServletRequest request) {
        User db = userService.getById(id);
        if (db == null) return Result.fail("user not found");

        Integer role = user.getRole();
        String password = user.getPassword();
        if (role == null && !StringUtils.hasText(password)) return Result.fail(400, "nothing to update: password or role is required");
        if (role != null && !isValidRole(role)) return Result.fail(400, "role must be 0 or 1");
        // 只传空白（多空格）密码时视为“不修改”，不能把空格存成密码
        if (password != null && !StringUtils.hasText(password)) password = null;
        if (password != null && password.length() > MAX_PASSWORD_LEN) return Result.fail(400, "password too long (max " + MAX_PASSWORD_LEN + ")");

        if (role != null && role == 0 && isAdmin(db)) {
            if (id.equals(getAdminId(request))) return Result.fail("cannot remove admin role from your own account");
            if (countAdmins() <= 1) return Result.fail("at least one admin account is required");
        }

        user.setId(id);
        user.setUsername(null);      // username not updatable
        user.setPassword(password);  // null -> field skipped by MyBatis-Plus
        user.setCreateTime(null);    // 不允许请求体覆盖创建时间
        // 注意：MySQL 默认只统计“实际变更行”，值未变化时 updateById 返回 false，不能当成记录不存在
        if (!userService.updateById(user) && userService.getById(id) == null) return Result.fail("user not found");

        // 日志要能说清“改的是谁、改了哪些字段、从什么改成什么”
        List<String> changes = new ArrayList<>();
        if (role != null && !role.equals(db.getRole())) {
            changes.add("角色 " + roleText(db.getRole()) + "→" + roleText(role));
        }
        if (password != null) {
            changes.add("密码已重置");
        }
        logService.log(getAdminId(request), getAdminName(request), "UPDATE_USER",
                "编辑用户 #" + id + " " + db.getUsername() + (changes.isEmpty() ? "（无实际变更）" : "：" + String.join("；", changes)));
        return Result.ok("updated", null);
    }

    /** 不能删当前登录账号；名下还有订单时要求先清订单。 */
    @Operation(
        summary = "删除用户",
        description = """
            根据用户 ID 删除用户。

            保护规则：不能删除当前登录的管理员自己；不能删除最后一个管理员；
            该用户已有订单时不允许删除（避免订单表出现无效 user_id）。
            """
    )
    @ApiResponse(responseCode = "200", description = "删除成功")
    @ApiResponse(responseCode = "500", description = "用户不存在 / 违反保护规则")
    @DeleteMapping("/users/{id}")
    public Result<?> deleteUser(@PathVariable Long id, HttpServletRequest request) {
        User db = userService.getById(id);
        if (db == null) return Result.fail("user not found");
        if (id.equals(getAdminId(request))) return Result.fail("cannot delete your own account");
        if (isAdmin(db) && countAdmins() <= 1) return Result.fail("at least one admin account is required");
        if (countOrders(Orders::getUserId, id) > 0) return Result.fail("user still has orders, delete orders first");
        userService.removeById(id);
        logService.log(getAdminId(request), getAdminName(request), "DELETE_USER",
                "删除用户 #" + id + " " + db.getUsername() + "（角色: " + roleText(db.getRole()) + "）");
        return Result.ok("deleted", null);
    }

    // ==================== Goods Management ====================

    /** 服务端分页，size 默认 10、上限 200；关键词匹配商品名。 */
    @Operation(
        summary = "商品列表（管理端）",
        description = "管理端商品面板专用，支持按商品名模糊搜索，固定按 id 升序。返回分页体 {records,total,page,size,pages}，每页默认 10 条。"
    )
    @ApiResponse(responseCode = "200", description = "成功")
    @ApiResponse(responseCode = "401", description = "Token 缺失或无效")
    @ApiResponse(responseCode = "403", description = "非管理员无权限")
    @GetMapping("/goods")
    public Result<PageResult<Goods>> listGoods(@RequestParam(required = false) String keyword,
                                                @RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "10") long size) {
        LambdaQueryWrapper<Goods> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) w.like(Goods::getGoodsName, keyword.trim());
        // 顺序必须稳定，否则翻页会重复/漏行
        w.orderByAsc(Goods::getId);
        Page<Goods> pg = goodsService.page(new Page<>(normalizePage(page), normalizeSize(size)), w);
        return Result.ok(PageResult.of(pg));
    }

    /** 商品名唯一，重复会返回失败；价格为 BigDecimal，别传字符串以外的格式。 */
    @Operation(
        summary = "新增商品",
        description = """
            创建新商品。校验：goodsName 必填、price >= 0、stock >= 0。

            请求体示例：`{"goodsName":"笔记本电脑","price":5999.00,"stock":50}`
            """
    )
    @ApiResponse(responseCode = "200", description = "创建成功")
    @ApiResponse(responseCode = "400", description = "参数非法")
    @PostMapping(value = "/goods", consumes = "application/json")
    public Result<?> createGoods(@RequestBody Goods goods, HttpServletRequest request) {
        String err = validateGoods(goods, false);
        if (err != null) return Result.fail(400, err);

        goods.setId(null);
        goods.setGoodsName(goods.getGoodsName().trim());
        // 统一成两位小数：否则请求里的 5 / 5.0 / 5.00 会原样回显，与库里 DECIMAL(10,2) 和日志不一致
        goods.setPrice(goods.getPrice().setScale(2, RoundingMode.HALF_UP));
        goods.setCreateTime(LocalDateTime.now());
        goodsService.save(goods);

        logService.log(getAdminId(request), getAdminName(request), "CREATE_GOODS",
                "新增商品 #" + goods.getId() + " " + goods.getGoodsName()
                        + "（单价 " + plain(goods.getPrice()) + "，库存 " + goods.getStock() + "）");
        return Result.ok("created", goods);
    }

    /** 只更新传入的字段；库存直接覆盖，不做增量调整（要加减库存请传最终值）。 */
    @Operation(
        summary = "编辑商品",
        description = "更新商品信息（名称、价格、库存）。字段为空时不更新该字段。请求体示例：`{\"goodsName\":\"笔记本电脑\",\"price\":4999.00,\"stock\":30}`"
    )
    @ApiResponse(responseCode = "200", description = "更新成功")
    @ApiResponse(responseCode = "400", description = "参数非法")
    @ApiResponse(responseCode = "500", description = "商品不存在")
    @PutMapping(value = "/goods/{id}", consumes = "application/json")
    public Result<?> updateGoods(@PathVariable Long id, @RequestBody Goods goods, HttpServletRequest request) {
        Goods db = goodsService.getById(id);
        if (db == null) return Result.fail("goods not found");
        if (goods.getGoodsName() == null && goods.getPrice() == null && goods.getStock() == null) {
            return Result.fail(400, "nothing to update: goodsName, price or stock is required");
        }
        String err = validateGoods(goods, true);
        if (err != null) return Result.fail(400, err);

        goods.setId(id);
        if (goods.getGoodsName() != null) goods.setGoodsName(goods.getGoodsName().trim());
        if (goods.getPrice() != null) goods.setPrice(goods.getPrice().setScale(2, RoundingMode.HALF_UP));
        goods.setCreateTime(null);
        if (!goodsService.updateById(goods) && goodsService.getById(id) == null) return Result.fail("goods not found");

        List<String> changes = new ArrayList<>();
        String newName = goods.getGoodsName();
        BigDecimal newPrice = goods.getPrice();
        Integer newStock = goods.getStock();
        if (newName != null && !newName.equals(db.getGoodsName())) {
            changes.add("名称 " + db.getGoodsName() + "→" + newName);
        }
        if (newPrice != null && db.getPrice() != null && newPrice.compareTo(db.getPrice()) != 0) {
            changes.add("单价 " + plain(db.getPrice()) + "→" + plain(newPrice));
        }
        if (newStock != null && !newStock.equals(db.getStock())) {
            changes.add("库存 " + db.getStock() + "→" + newStock);
        }
        logService.log(getAdminId(request), getAdminName(request), "UPDATE_GOODS",
                "编辑商品 #" + id + " " + db.getGoodsName() + (changes.isEmpty() ? "（无实际变更）" : "：" + String.join("；", changes)));
        return Result.ok("updated", null);
    }

    /** 还有订单关联时不允许删除，避免订单里的 goodsId 变成无人能解释的悬空值。 */
    @Operation(
        summary = "删除商品",
        description = "根据商品 ID 删除商品。该商品已有订单时不允许删除（避免订单表出现无效 goods_id）。"
    )
    @ApiResponse(responseCode = "200", description = "删除成功")
    @ApiResponse(responseCode = "500", description = "商品不存在 / 已存在关联订单")
    @DeleteMapping("/goods/{id}")
    public Result<?> deleteGoods(@PathVariable Long id, HttpServletRequest request) {
        Goods db = goodsService.getById(id);
        if (db == null) return Result.fail("goods not found");
        long related = countOrders(Orders::getGoodsId, id);
        if (related > 0) return Result.fail("goods still has orders, delete orders first");

        goodsService.removeById(id);
        logService.log(getAdminId(request), getAdminName(request), "DELETE_GOODS",
                "删除商品 #" + id + " " + db.getGoodsName() + "（单价 " + plain(db.getPrice()) + "，原库存 " + db.getStock() + "）");
        return Result.ok("deleted", null);
    }

    // ==================== Order Management ====================

    /** 全站订单，按 createTs 倒序；关键词匹配订单号，可选按用户/商品过滤。 */
    @Operation(
        summary = "全部订单列表（分页）",
        description = """
            分页查询全部订单，默认按下单时间倒序。

            - `keyword`：按订单号模糊搜索
            - `status`：0=未支付 / 1=已支付，不传为全部
            - `page`：从 1 开始；`size`：默认 20，上限 200
            - 返回结构：`data = {records, total, page, size, pages}`
            """
    )
    @ApiResponse(responseCode = "200", description = "成功")
    @ApiResponse(responseCode = "400", description = "status 非法")
    @GetMapping("/orders")
    public Result<PageResult<Orders>> listOrders(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) Integer status,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "10") long size) {
        if (status != null && status != 0 && status != 1) return Result.fail(400, "status must be 0 or 1");

        LambdaQueryWrapper<Orders> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) w.like(Orders::getOrderNo, keyword.trim());
        if (status != null) w.eq(Orders::getStatus, status);
        // 同一毫秒会下大量单（压测场景），create_ts 相同时用 id 兼做稳定排序，否则翻页会重复/漏行
        w.orderByDesc(Orders::getCreateTs).orderByDesc(Orders::getId);

        Page<Orders> pg = orderService.page(new Page<>(normalizePage(page), normalizeSize(size)), w);
        return Result.ok(PageResult.of(pg));
    }

    /** 物理删除，没有“已取消”状态可退回，所以删完就没了；审计里会留下订单快照。 */
    @Operation(
        summary = "删除订单",
        description = """
            根据订单 ID 删除订单，并记录完整快照（订单号/用户/商品/金额/状态）。

            存在意义：用户/商品有订单时不允许删除（防止无效外键），管理员需要能先清掉相关订单。
            """
    )
    @ApiResponse(responseCode = "200", description = "删除成功")
    @ApiResponse(responseCode = "500", description = "订单不存在")
    @DeleteMapping("/orders/{id}")
    public Result<?> deleteOrder(@PathVariable Long id, HttpServletRequest request) {
        Orders db = orderService.getById(id);
        if (db == null) return Result.fail("order not found");

        orderService.removeById(id);
        logService.log(getAdminId(request), getAdminName(request), "DELETE_ORDER", "删除订单：" + orderBrief(db));
        return Result.ok("deleted", null);
    }

    /** 批量删除某用户/某商品的全部订单（只删属于该目标的订单，带精确条件不会误删） */
    @Operation(
        summary = "按用户或商品清理订单",
        description = "删除 userId 或 goodsId 指定的全部订单（事次参数传其一），返回实际删除条数，供“删除用户/商品前清理订单”使用。"
    )
    @DeleteMapping("/orders")
    public Result<?> deleteOrders(@RequestParam(required = false) Long userId,
                                  @RequestParam(required = false) Long goodsId,
                                  HttpServletRequest request) {
        if (userId == null && goodsId == null) {
            return Result.fail(400, "userId or goodsId is required");
        }
        // 每个查询都用独立的 wrapper：不 clone、不复用，避免 last("LIMIT 5") 之类的修饰渗进 DELETE 条件
        // （那样会把“清理 5000 条”静默变成只删 5 条，而接口和日志还报 5000）
        List<Orders> sample = orderService.list(orderScope(userId, goodsId)
                .select(Orders::getId, Orders::getOrderNo, Orders::getPayPrice, Orders::getStatus)
                .orderByAsc(Orders::getId)
                .last("LIMIT 5"));
        int matched = orderService.deleteBy(orderScope(userId, goodsId));
        if (matched == 0) return Result.ok("no orders matched", 0);

        // 日志记的是真实删除行数，不是删除前的 count 快照（并发下两者会不一致）
        StringBuilder detail = new StringBuilder("批量删除订单 " + matched + " 条（"
                + (userId != null ? "用户 #" + userId : "商品 #" + goodsId) + "）");
        if (!sample.isEmpty()) {
            detail.append("：");
            for (int i = 0; i < sample.size(); i++) {
                Orders o = sample.get(i);
                if (i > 0) detail.append('，');
                detail.append('#').append(o.getId()).append(' ')
                        .append(o.getOrderNo())
                        .append("(¥").append(o.getPayPrice() == null ? "-" : o.getPayPrice().toPlainString())
                        .append(Integer.valueOf(1).equals(o.getStatus()) ? ",已支付)" : ",未支付)");
            }
            if (matched > sample.size()) {
                detail.append(" 等共 ").append(matched).append(" 条");
            }
        }
        logService.log(getAdminId(request), getAdminName(request), "DELETE_ORDER", detail.toString());
        return Result.ok("deleted", matched);
    }

    /** 订单过滤条件（每次调用返回全新 wrapper，供取样/删除各自使用） */
    private LambdaQueryWrapper<Orders> orderScope(Long userId, Long goodsId) {
        LambdaQueryWrapper<Orders> w = new LambdaQueryWrapper<>();
        if (userId != null) w.eq(Orders::getUserId, userId);
        if (goodsId != null) w.eq(Orders::getGoodsId, goodsId);
        return w;
    }


    // ==================== Helpers ====================
    /** 页码归一：从 1 开始，非法值按 1 处理（不让 0/负数传到 SQL） */
    static long normalizePage(long page) {
        return page < 1 ? 1 : page;
    }

    /** 每页条数归一：1 ~ MAX_PAGE_SIZE */
    static long normalizeSize(long size) {
        if (size < 1) return 1;
        return Math.min(size, MyBatisPlusConfig.MAX_PAGE_SIZE);
    }

    /** 角色的中文写法，只用于审计 detail 文案；null 按普通用户处理 */
    private String roleText(Integer role) {
        return Integer.valueOf(1).equals(role) ? "管理员" : "普通用户";
    }

    /** BigDecimal 输出不带多余的 0，也不走科学计数法 */
    private String plain(BigDecimal v) {
        return v == null ? "-" : v.toPlainString();
    }

    /** 订单快照：足够回答“哪一笔订单、谁买的、买的什么、多少钱、什么状态” */
    private String orderBrief(Orders o) {
        if (o == null) return "订单 #不存在";
        return "#" + o.getId() + " 订单号 " + o.getOrderNo()
                + "（用户 #" + o.getUserId() + "，商品 #" + o.getGoodsId()
                + "，金额 " + plain(o.getPayPrice())
                + "，" + (Integer.valueOf(1).equals(o.getStatus()) ? "已支付" : "未支付") + "）";
    }

    /** 只接受 0 和 1，其它值（包括 null）一律 400，不让脏角色进库 */
    private boolean isValidRole(Integer role) {
        return role != null && (role == 0 || role == 1);
    }

    /** role 为 1 才算管理员；user 为 null 返回 false，不抛异常 */
    private boolean isAdmin(User user) {
        return user != null && Integer.valueOf(1).equals(user.getRole());
    }

    /** 当前管理员数量。编辑/删除时用它可以避免把最后一个管理员降权或删掉后无人能进后台 —— 但注意现有实现只挡住了“删自己”，没有强制保留一个。 */
    private long countAdmins() {
        return userService.count(new LambdaQueryWrapper<User>().eq(User::getRole, 1));
    }

    /** 按登录名精确查一个用户，没有则 null。getOne 传 false 表示多条也不抛异常。 */
    private User findByUsername(String username) {
        return userService.getOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username), false);
    }

    /** 按指定列统计某账号/某商品名下的订单数，用于删除保护。列做成参数是为了用户和商品复用同一条 count。 */
    private long countOrders(com.baomidou.mybatisplus.core.toolkit.support.SFunction<Orders, ?> column, Long value) {
        return orderService.count(new LambdaQueryWrapper<Orders>().eq(column, value));
    }

    /**
     * 商品字段校验，返回第一条错误信息；全部合法返回 null。
     *
     * @param partial true 表示局部更新（编辑商品）：没传的字段跳过校验，MyBatis-Plus 也不会写这些列；
     *                false 表示新增：名称、价格、库存都必须给
     * @return 错误短语（前端 MSG_ZH 表按它翻译中文），合法时为 null
     */
    private String validateGoods(Goods goods, boolean partial) {
        String name = goods.getGoodsName();
        if (name == null || name.trim().isEmpty()) {
            if (!partial) return "goodsName is required";
        } else if (name.trim().length() > MAX_GOODS_NAME_LEN) {
            return "goodsName too long (max " + MAX_GOODS_NAME_LEN + ")";
        }

        BigDecimal price = goods.getPrice();
        if (price == null) {
            if (!partial) return "price is required";
        } else if (price.compareTo(BigDecimal.ZERO) < 0) {
            return "price must be greater than or equal to 0";
        } else if (price.compareTo(MAX_PRICE) > 0) {
            return "price too large (max " + MAX_PRICE + ")";
        } else if (price.scale() > 2) {
            // DECIMAL(10,2) 会把多余位四舍五入掉：管理员填 9.999，库里就成了 10.00，价格对不上还查不出原因
            return "price supports at most 2 decimal places";
        }

        Integer stock = goods.getStock();
        if (stock == null) {
            if (!partial) return "stock is required";
        } else if (stock < 0) {
            return "stock must be greater than or equal to 0";
        }

        return null;
    }

    /** 当前管理员 ID，由 JwtInterceptor 从 Token 解出后放进 request 属性；写审计日志时作为操作人。 */
    private Long getAdminId(HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        return userId instanceof Long ? (Long) userId : null;
    }

    /** 操作人登录名，由 JwtInterceptor 放进 request 属性；取不到就是 null，交给日志侧兜底成占位值 */
    private String getAdminName(HttpServletRequest request) {
        return (String) request.getAttribute("username");
    }
}
