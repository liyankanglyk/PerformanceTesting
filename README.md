# 速购商城性能测试系统

SpringBoot 单体项目，供 JMeter 性能压测练习。

## 快速开始

**1. 创建数据库**

```sql
CREATE DATABASE IF NOT EXISTS performance_testing DEFAULT CHARACTER SET utf8mb4;
```

**2. 修改配置** → `application.properties`

```properties
spring.datasource.url=jdbc:mysql://localhost:3308/performance_testing
spring.datasource.username=root
spring.datasource.password=123456
```

**3. 启动** — 每次启动自动重建表并插入初始测试数据

```bash
# IDEA 直接运行主类，或：
mvn clean package -DskipTests
java -jar target/PerformanceTesting-0.0.1-SNAPSHOT.jar
```

**4. 访问**

| 地址                              | 说明 |
|---------------------------------|------|
| http://localhost:6060/login.html | 登录页 |
| http://localhost:6060/admin.html | 管理后台（仅管理员） |
| http://localhost:6060/doc.html  | 接口文档（自动跳转 Swagger UI） |
| http://localhost:6060/swagger-ui/index.html | 接口文档原始地址 |

## 测试账号

| 用户名 | 密码 | 角色 |
|--------|------|:---:|
| admin | 123456 | 管理员 |
| test001 ~ 003 | 123456 | 普通用户 |

## 技术栈

SpringBoot 3.4.6 / MyBatis-Plus 3.5.11 / MySQL 8.0 / SpringDoc OpenAPI 2.8.5 (Swagger UI) / JWT / 纯 HTML+CSS+JS

> 接口文档原用 Knife4j 4.5.0，但其内置 springdoc 2.3.0 与 Spring Boot 3.4（Spring Framework 6.2）二进制不兼容，
> `/v3/api-docs` 与 `/doc.html` 会直接 500；现已换为官方 springdoc-openapi 2.8.5，`/doc.html` 保留为跳转地址。

## 功能

**用户端** — 模糊搜索商品 → 下单购买 → 在「我的订单」里确认支付 → 退出

页面同样走 hash 路由：`index.html#/goods?keyword=`、`index.html#/orders?keyword=`，刷新不会掉回商品列表。

**管理后台** — 系统概览 / 用户管理 / 商品管理 / 订单管理 / 操作日志

页面状态走 hash 路由（`#/users?keyword=&page=&size=`、`#/orders?status=&page=&size=`），
刷新 / 收藏 / 前进后退都会还原当前面板与筛选条件；列表为服务端分页，写操作全部留审计日志。

审计能力（压测时可按需开关）：

页面里操作日志表的列：**ID / 操作人 / 角色 / 操作类型（中文）/ 详情 / IP / 时间**。
四个管理端列表（用户、商品、订单、日志）全部服务端分页，**每页默认 10 条**。

| 记录内容 | 说明 |
|------|------|
| 管理员增删改 | 始终记录，detail 含目标 ID/名称与变更前后（如 `单价 100.00→66.60；库存 3→0`） |
| 普通用户行为 | `USER_LOGIN` / `USER_LOGOUT` / `PLACE_ORDER` / `PAY_ORDER`，由 `operation-log.user-actions-enabled` 控制（默认 true） |
| 登录与退出来源 | `ip` 独立列，统一 IPv4 写法（本机 `0:0:0:0:0:0:0:1` → `127.0.0.1`）。**默认不采信 `X-Forwarded-For`**（客户端可自造），前面有 nginx/SLB 时才设 `ip.trust-forwarded-headers=true` |
| 操作类型展示 | 前端显示中文（`用户下单（PLACE_ORDER）`），枚举值保留在 title 与小字里便于对脚本 |

> 失败的变更不写日志（登录失败、库存不足、参数校验 400 都不会产生噪声）。
> 实测：开启用户行为审计时下单平均 25.5ms / P95 38ms，关闭时 18ms / P95 26ms；
> **做正式压测基线时请固定该开关状态。**

## 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/user/login | 登录（Header: ts，Body: password = MD5(明文+ts)） |
| GET | /api/goods/list | 商品列表（?keyword= 模糊搜索） |
| POST | /api/order/buy | 下单（Body: {"goodsId":1}） |
| GET | /api/order/list | 我的订单（?keyword= 搜索订单号） |
| PUT | /api/order/pay/{orderNo} | **确认支付（用户端，只能付自己的单）** |
| POST | /api/user/logout | 退出登录（写审计日志；JWT 无状态，不使旧 Token 失效） |
| GET/POST/PUT/DELETE | /api/admin/** | 管理接口（需管理员 Token） |

管理接口清单：`GET /status`、`GET /summary`（概览聚合）、`GET /db/state`、`POST /db/reset`（系统重置，需 `confirm=RESET`）、`GET /logs`（分页 ?page=&size=&keyword=&action=）、`GET /log-actions`、
`GET|POST /users`、`PUT|DELETE /users/{id}`、`GET|POST /goods`、`PUT|DELETE /goods/{id}`、
`GET /orders`、`DELETE /orders/{id}`、`DELETE /orders?userId=|goodsId=`

> 旧版的管理端 `PUT /admin/orders/{id}/pay`（标记已支付）已下线：支付是用户端行为，
> 改用 `PUT /api/order/pay/{orderNo}`；管理端只保留订单删除/清理。

## 列表分页

`/api/admin/users`、`/api/admin/goods`、`/api/admin/orders`、`/api/admin/logs` 全部服务端分页（每页默认 10 条）；用户端 `/goods/list`、`/order/list` 仍返回完整数组：

| 参数 | 默认 | 说明 |
|------|:---:|------|
| page | 1 | 从 1 开始；0/负数按 1 处理 |
| size | 20 | 上限 200，超出自动夹住（`maxLimit` 双重保护） |
| keyword | — | 用户名 / 订单号模糊搜索 |
| status | — | 仅订单：0=未支付，1=已支付 |

响应 `data = {records, total, page, size, pages}`（**不再是数组**，JMeter 断言改取 `$.data.records` / `$.data.total`）。
页码越界返回空 `records`，`total/pages` 仍正确；订单按 `create_ts DESC, id DESC` 排序，避免同毫秒下单导致翻页重复/漏行。

分页依赖 `mybatis-plus-jsqlparser`（MyBatis-Plus 3.5.9+ 把分页等内拦截器拆成了独立模块）
和 `MyBatisPlusConfig` 中注册的 `PaginationInnerInterceptor`——少了它们 `page()` 不报错，但会静默把全表读进内存。

## 鉴权

`/api/user/login` 公开，`/api/**` 需 JWT Token，`/api/admin/**` 需管理员角色。

Token 有效期 1 小时，过期后需重新登录。

## 下单设计

```sql
UPDATE goods SET stock = stock - 1 WHERE id = ? AND stock > 0
```

数据库原子更新防超卖，受影响行数 0 表示库存不足。

## 管理端校验与保护规则

写接口都会先做参数校验（code=400）再落库，并带以下保护规则（code=500 + 明确 msg）：

| 场景 | 规则 |
|------|------|
| 新增/编辑用户 | username、password 必填且不超列宽；role 只能是 0 或 1；用户名不可重复、不可修改 |
| 删除用户 | 不能删除当前登录账号；不能删除最后一个管理员；该用户已有订单时拒绝删除 |
| 编辑用户角色 | 不能取消自己的管理员角色；不能让系统失去最后一个管理员 |
| 新增/编辑商品 | goodsName 必填且不重复（库上有 `goods_name` 唯一索引；重名返回 400 `goods name already exists`，并发抢名由索引兜底）；price ≥ 0、≤ DECIMAL(10,2) 上限且最多两位小数；stock ≥ 0 |
| 删除商品 | 该商品已有订单时拒绝删除（避免订单悬空 goods_id） |
| 订单删除 | `DELETE /orders/{id}` 删单条；`DELETE /orders?userId=\|goodsId=` 批量清理并返回条数——上面两行保护规则的出路，管理页会引导一步完成 |
| 操作日志 | 每条都记录“操作人 + 目标 ID/名称 + 变更前后”（如 `单价 100.00→66.60；库存 3→0`），不记明文密码；失败的变更不写日志 |
| 标记订单支付 | 条件更新 `status 0 -> 1`，重复调用返回 `order already paid` 且不重复写日志 |
| 角色/账号变更 | 管理接口每次回查数据库角色，被降级或删号的旧 Token 立即失效（403 / 401） |
| 系统概览 | 一次 `/admin/summary` 出业务计数 + 近 N 分钟速率 + 库存 + JVM；默认 5s 自动刷新（可关），离开该面板或标签页隐藏即停；阈值上色：堆 >70% 橙、>85% 红，支付率 <95% 橙，售罄商品 >0 橙 |
| 数字参数校验 | `?page=abc`、`?size=1.5`、`?windowMinutes=abc` 返回 400 + `invalid parameter: <名>`，不再 500，也不透出 Java 异常原文 |
| 压测数据准备 | 初始化内容只有**一份脚本** `src/main/resources/performance_testing.sql`（每张表先 `DROP` 再 `CREATE`，紧跟种子 `INSERT`）。`db.init-mode` 决定启动要不要跑它（默认 `always`；`if-absent` = 只在表没建齐时跑；`never` = 完全不动）。跑完一轮想清空，直接点概览页「系统重置」（要输入 RESET，执行会写 `DB_RESET` 审计）。**压测进行中不要点** |
| 中文编码 | SQL 脚本固定按 UTF-8 读、JDBC 连接显式 `characterEncoding=UTF-8`。中文 Windows 默认字符集是 GBK，少了这两处，商品名会灌成「鑻规灉 iPhone 16 Pro Max」这类乱码；已经乱了的话执行一次系统重置即可恢复。初始化脚本的列字符集已统一为 `utf8mb4`（旧版转储是 `utf8`/mb3，emoji 存不下） |
| 下单 | 账号已被管理员删除时拒绝下单，避免产生无效 user_id 的订单 |
| 存储引擎 | 初始化脚本已把四张表统一为 `ENGINE = InnoDB`。**为什么要管这个**：MyISAM 不支持事务，`buy()` 的 `@Transactional` 回滚不生效，会出现“库存扣了、订单没插进去”的缺口；防超卖两者都成立（扣库存是单语句条件 `UPDATE`）。老库若是 MyISAM，用下面一段 ALTER 迁移，或直接点「系统重置」重建 |


### 老库迁移到 InnoDB / utf8mb4（不想重建数据时用）

`db.init-mode=always`（默认）下次启动会自动用新脚本重建，**不需要**手工迁移。
只有你把库留着、想原地改引擎时才跑这段：

```sql
ALTER TABLE `user`          ENGINE = InnoDB, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE `goods`         ENGINE = InnoDB, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE `orders`        ENGINE = InnoDB, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE `operation_log` ENGINE = InnoDB, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 商品名唯一索引（先确认没有重名，否则这条会失败）
SELECT goods_name, COUNT(*) c FROM `goods` GROUP BY goods_name HAVING c > 1;
ALTER TABLE `goods` ADD UNIQUE INDEX `goods_name` (`goods_name`);
```

> 顺序有讲究：`ALTER TABLE ... ENGINE` 是拷表重建，期间该表不可写；四张表加起来在压测后
> 可能有几十万行订单，别在压测进行中做。

## 项目结构

```
src/main/java/com/xiaohua/performancetesting/
├── common/Result.java            # 统一响应 + 全局异常/错误出口
├── config/          # OpenAPI / MyBatis-Plus / 拦截器注册
├── controller/      # User / Goods / Order / Admin / System
├── entity/          # User / Goods / Orders / OperationLog
├── interceptor/     # JwtInterceptor / AdminInterceptor
├── mapper/          # 含原子扣减库存 SQL
├── service/         # 含操作日志记录
└── util/            # JWT / MD5

src/main/resources/
├── application.properties
├── performance_testing.sql # 唯一的初始化脚本（整库转储：DROP+CREATE+种子 INSERT），按 db.init-mode 决定是否执行，也可在概览页点「系统重置」手动重建
└── static/                 # login / index / admin + css + js
```
