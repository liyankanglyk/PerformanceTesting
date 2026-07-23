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
| http://localhost:6060/doc.html  | 接口文档 |

## 测试账号

| 用户名 | 密码 | 角色 |
|--------|------|:---:|
| admin | 123456 | 管理员 |
| test001 ~ 003 | 123456 | 普通用户 |

## 技术栈

SpringBoot 3.4.6 / MyBatis-Plus 3.5.11 / MySQL 8.0 / Knife4j / JWT / 纯 HTML+CSS+JS

## 功能

**用户端** — 模糊搜索商品 → 下单购买 → 查看个人订单

**管理后台** — 系统概览 / 用户管理 / 商品管理 / 订单管理 / 操作日志

## 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/user/login | 登录（Header: ts，Body: password = MD5(明文+ts)） |
| GET | /api/goods/list | 商品列表（?keyword= 模糊搜索） |
| POST | /api/order/buy | 下单（Body: {"goodsId":1}） |
| GET | /api/order/list | 我的订单（?keyword= 搜索订单号） |
| GET/POST/PUT/DELETE | /api/admin/** | 管理接口（需管理员 Token） |

## 鉴权

`/api/user/login` 公开，`/api/**` 需 JWT Token，`/api/admin/**` 需管理员角色。

Token 有效期 1 小时，过期后需重新登录。

## 下单设计

```sql
UPDATE goods SET stock = stock - 1 WHERE id = ? AND stock > 0
```

数据库原子更新防超卖，受影响行数 0 表示库存不足。

## 项目结构

```
src/main/java/com/xiaohua/performancetesting/
├── common/Result.java
├── config/          # Knife4j / MyBatis-Plus / 拦截器注册
├── controller/      # User / Goods / Order / Admin / System
├── entity/          # User / Goods / Orders / OperationLog
├── interceptor/     # JwtInterceptor / AdminInterceptor
├── mapper/          # 含原子扣减库存 SQL
├── service/         # 含操作日志记录
└── util/            # JWT / MD5

src/main/resources/
├── application.properties
├── schema.sql + data.sql   # 每次启动重建表和数据
└── static/                 # login / index / admin + css + js
```
