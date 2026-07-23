# 速购商城 API 测试文档

> 本文档供接口测试 / JMeter 压测人员使用，包含完整请求/响应示例及鉴权说明。

---

## 一、系统信息

| 项目 | 说明 |
|------|------|
| 框架 | SpringBoot 3.4.6 + MyBatis-Plus 3.5.11 |
| 数据库 | MySQL 8.0 |
| 接口文档 | `/doc.html`（Knife4j 在线文档） |
| 鉴权方式 | JWT Token（请求头 `token`），有效期 1 小时 |
| 统一响应格式 | `{"code":200,"msg":"success","data":{},"timestamp":1716307200000}` |

---

## 二、统一响应格式

所有接口均返回以下 JSON 结构：

```json
{
  "code": 200,
  "msg": "success",
  "data": { },
  "timestamp": 1716307200000
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| code | int | 200=成功, 400=参数错误, 401=鉴权失败, 403=无权限, 500=业务错误 |
| msg | string | 操作结果描述 |
| data | any | 返回数据，可能为 null |
| timestamp | long | 响应时间戳（毫秒） |

---

## 三、鉴权机制

### 3.1 Token 获取与使用

| 项目 | 说明 |
|------|------|
| 获取方式 | 调用 `/api/user/login` 登录成功后返回 `token` 字段 |
| 使用方式 | 所有需要鉴权的接口，在请求头中携带 `token: <JWT Token>` |
| 有效期 | 1 小时，过期后返回 `{"code":401,"msg":"token has expired"}` |
| 角色校验 | `/api/admin/**` 接口需要 `role=1`（管理员），否则返回 `{"code":403,"msg":"admin permission required"}` |

### 3.2 不需要 Token 的接口

| 方法 | 路径 |
|------|------|
| POST | `/api/user/login` |
| GET | `/doc.html`, `/swagger-ui/**`, `/v3/api-docs/**` |

---

## 四、密码加密规则（重要）

系统使用 **MD5 + 时间戳加盐** 加密：

```
password = MD5(明文密码 + 时间戳)
```

- `password` 每次请求不同（因为时间戳变化），天然防重放攻击
- 时间戳通过请求头 `ts` 传入（毫秒级 Unix 时间戳）
- **时间戳有效期 ±5 分钟**：服务端会校验 `ts` 与当前时间偏差不超过 5 分钟，超时返回 `"timestamp expired"`

**JMeter 中的实现方式（BeanShell）：**
```java
import org.apache.commons.codec.digest.DigestUtils;
String ts = String.valueOf(System.currentTimeMillis());
String password = DigestUtils.md5Hex(vars.get("password") + ts);
vars.put("ts", ts);
vars.put("password", password);
```

---

## 五、测试账号

| 用户名 | 明文密码 | 角色 | role |
|--------|----------|------|:---:|
| admin | 123456 | 管理员 | 1 |
| test001 | 123456 | 普通用户 | 0 |
| test002 | 123456 | 普通用户 | 0 |
| test003 | 123456 | 普通用户 | 0 |

> 演示项目密码明文存储。传输时 `password = MD5("123456" + ts)`。

---

## 六、接口详细说明

### 6.1 用户登录（公开接口）

```
POST /api/user/login
```

**请求头：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| ts | string | ✓ | 当前时间戳（毫秒），用于密码加盐 |
| Content-Type | string | ✓ | `application/json` |

**请求体：**

```json
{
  "username": "admin",
  "password": "3d5f2c1a8b9e4f6d7c8a9b0e1f2a3b4c"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| username | string | ✓ | 用户名 |
| password | string | ✓ | MD5(明文密码 + ts) 的结果（**注意：每次请求不同**） |

**成功响应 (code=200)：**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
   		"token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwidXNlcm5hbWUiOiJhZG1pbiIsInJvbGUiOjEsImlhdCI6MTcxNjMwNzIwMCwiZXhwIjoxNzE2MzEwODAwfQ.xxx",
    	"role": 1
  },
  "timestamp": 1716307200000
}
```

**Token 返回字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| token | string | JWT Token，用于后续接口鉴权，有效期 1 小时 |
| role | int | 用户角色：0=普通用户，1=管理员 |

**失败响应：**

```json
// 参数缺失 (code=400)
{
  "code": 400,
  "msg": "username, password and ts header are required",
  "data": null,
  "timestamp": 1716307200000
}

// 时间戳格式错误 (code=400)
{
  "code": 400,
  "msg": "invalid timestamp format",
  "data": null,
  "timestamp": 1716307200000
}

// 时间戳过期 (code=400)
{
  "code": 400,
  "msg": "timestamp expired, possible replay attack",
  "data": null,
  "timestamp": 1716307200000
}

// 用户名或密码错误 (code=401)
{
  "code": 401,
  "msg": "invalid username or password",
  "data": null,
  "timestamp": 1716307200000
}
```

**curl 示例：**
```bash
# 注意：ts 必须是当前时间戳（与服务器偏差超过 5 分钟会被拒绝）
# password = MD5(明文密码 + ts)，每次请求都不同
TS=$(date +%s%3N)  # 毫秒时间戳
PWD=$(echo -n "123456${TS}" | md5sum | cut -d' ' -f1)
curl -X POST 'http://localhost:8080/api/user/login' \
  -H 'Content-Type: application/json' \
  -H "ts: ${TS}" \
  -d "{\"username\":\"admin\",\"password\":\"${PWD}\"}"
```

---

### 6.2 获取当前用户信息

```
GET /api/user/info
```

**请求头：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| token | string | ✓ | JWT Token（登录获取） |

**成功响应 (code=200)：**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "id": 1,
    "username": "admin",
    "role": 1,
    "createTime": "2026-05-22T10:00:00"
  },
  "timestamp": 1716307200000
}
```

**失败响应：**

```json
// Token 缺失 (code=401)
{"code":401,"msg":"token is missing","data":null,"timestamp":1716307200000}

// Token 过期 (code=401)
{"code":401,"msg":"token has expired","data":null,"timestamp":1716307200000}

// Token 无效 (code=401)
{"code":401,"msg":"invalid token","data":null,"timestamp":1716307200000}

// 用户不存在 (code=404)
{"code":404,"msg":"user not found","data":null,"timestamp":1716307200000}
```

**curl 示例：**
```bash
curl -X GET 'http://localhost:8080/api/user/info' \
  -H 'token: eyJhbGciOiJIUzI1NiJ9...'
```

---

### 6.3 商品列表查询

```
GET /api/goods/list
```

**请求头：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| token | string | ✓ | JWT Token |

**请求参数（Query）：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| keyword | string | ✗ | 模糊搜索关键词（匹配商品名称），不传返回全部 |

**成功响应 (code=200)：**

```json
{
  "code": 200,
  "msg": "success",
  "data": [
    {
      "id": 1,
      "goodsName": "苹果 iPhone 16 Pro Max",
      "price": 9999.00,
      "stock": 500,
      "createTime": "2026-05-22T10:00:00"
    },
    {
      "id": 2,
      "goodsName": "华为 Mate 70 Pro",
      "price": 6999.00,
      "stock": 300,
      "createTime": "2026-05-22T10:00:00"
    }
  ],
  "timestamp": 1716307200000
}
```

**curl 示例：**
```bash
# 查询全部
curl -X GET 'http://localhost:8080/api/goods/list' -H 'token: xxx'

# 模糊搜索
curl -X GET 'http://localhost:8080/api/goods/list?keyword=手机' -H 'token: xxx'
```

---

### 6.4 购买商品（下单）

```
POST /api/order/buy
```

**请求头：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| Content-Type | string | ✓ | `application/json` |
| token | string | ✓ | JWT Token |

**请求体：**

```json
{
  "goodsId": 1
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| goodsId | long | ✓ | 要购买的商品 ID |

**成功响应 (code=200)：**

```json
{
  "code": 200,
  "msg": "order placed successfully",
  "data": {
    "id": 10,
    "orderNo": "a1b2c3d4e5f67890abcdef1234567890",
    "userId": 2,
    "goodsId": 1,
    "payPrice": 9999.00,
    "createTs": 1716307200000,
    "status": 0
  },
  "timestamp": 1716307200000
}
```

**失败响应：**

```json
// goodsId 缺失 (code=400)
{"code":400,"msg":"goodsId is required","data":null,"timestamp":1716307200000}

// 库存不足 (code=500)
{"code":500,"msg":"insufficient stock","data":null,"timestamp":1716307200000}
```

**并发说明：** 系统使用数据库原子操作 `UPDATE goods SET stock = stock - 1 WHERE id = ? AND stock > 0` 保证高并发下不超卖。库存耗尽后所有争抢请求返回 `"insufficient stock"`。

**curl 示例：**
```bash
curl -X POST 'http://localhost:8080/api/order/buy' \
  -H 'Content-Type: application/json' \
  -H 'token: xxx' \
  -d '{"goodsId":1}'
```

---

### 6.5 查询我的订单

```
GET /api/order/list
```

**请求头：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| token | string | ✓ | JWT Token |

**请求参数（Query）：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| keyword | string | ✗ | 订单号模糊搜索，不传返回全部订单 |

**成功响应 (code=200)：**

```json
{
  "code": 200,
  "msg": "success",
  "data": [
    {
      "id": 10,
      "orderNo": "a1b2c3d4e5f67890abcdef1234567890",
      "userId": 2,
      "goodsId": 1,
      "payPrice": 9999.00,
      "createTs": 1716307200000,
      "status": 0
    }
  ],
  "timestamp": 1716307200000
}
```

**订单状态说明：**

| status | 含义 |
|:------:|------|
| 0 | 未支付 |
| 1 | 已支付（管理员操作标记） |

**curl 示例：**
```bash
curl -X GET 'http://localhost:8080/api/order/list' -H 'token: xxx'
curl -X GET 'http://localhost:8080/api/order/list?keyword=a1b2' -H 'token: xxx'
```

---

### 6.6 系统运行状态（管理员）

```
GET /api/admin/status
```

**请求头：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| token | string | ✓ | 管理员 JWT Token（role=1） |

**成功响应 (code=200)：**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "uptime": 1234567,
    "availableProcessors": 8,
    "heapUsed": 52428800,
    "heapMax": 4294967296,
    "nonHeapUsed": 16777216,
    "threadCount": 25,
    "peakThreadCount": 30,
    "totalMemory": 268435456,
    "freeMemory": 134217728,
    "maxMemory": 4294967296
  },
  "timestamp": 1716307200000
}
```

**curl 示例：**
```bash
curl -X GET 'http://localhost:8080/api/admin/status' -H 'token: <admin_token>'
```

---

### 6.7 操作日志列表（管理员）

```
GET /api/admin/logs
```

**请求参数（Query）：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| keyword | string | ✗ | 关键词搜索（匹配操作类型/详情/用户名） |

**成功响应 (code=200)：**

```json
{
  "code": 200,
  "msg": "success",
  "data": [
    {
      "id": 1,
      "adminId": 1,
      "username": "admin",
      "action": "ADMIN_LOGIN",
      "detail": "管理员登录",
      "createTime": "2026-05-22T10:00:00"
    }
  ],
  "timestamp": 1716307200000
}
```

**操作类型（action）枚举：**

| action | 说明 |
|--------|------|
| ADMIN_LOGIN | 管理员登录 |
| CREATE_USER | 新增用户 |
| UPDATE_USER | 编辑用户 |
| DELETE_USER | 删除用户 |
| CREATE_GOODS | 新增商品 |
| UPDATE_GOODS | 编辑商品 |
| DELETE_GOODS | 删除商品 |
| PAY_ORDER | 标记订单已支付 |

---

### 6.8 用户管理 — 列表（管理员）

```
GET /api/admin/users
```

**请求参数（Query）：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| keyword | string | ✗ | 按用户名模糊搜索 |

**成功响应 (code=200)：**

```json
{
  "code": 200,
  "msg": "success",
  "data": [
    {
      "id": 1,
      "username": "admin",
      "role": 1,
      "createTime": "2026-05-22T10:00:00"
    }
  ],
  "timestamp": 1716307200000
}
```

---

### 6.9 用户管理 — 新增（管理员）

```
POST /api/admin/users
```

**请求头：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| Content-Type | string | ✓ | `application/json` |
| token | string | ✓ | 管理员 JWT Token |

**请求体：**

```json
{
  "username": "newUser",
  "password": "123456",
  "role": 0
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| username | string | ✓ | 用户名（不可重复） |
| password | string | ✓ | 明文密码（演示项目，服务端直接存储） |
| role | int | ✗ | 角色，默认 0 |

**成功响应 (code=200)：**
```json
{"code":200,"msg":"created","data":{"id":5,"username":"newUser","role":0,"createTime":"..."},"timestamp":1716307200000}
```

**失败响应：** `{"code":500,"msg":"username already exists"}`

---

### 6.10 用户管理 — 编辑（管理员）

```
PUT /api/admin/users/{id}
```

**请求头：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| Content-Type | string | ✓ | `application/json` |
| token | string | ✓ | 管理员 JWT Token |

**路径参数：** `id` — 用户 ID

**请求体：**

```json
{
  "password": "newPassword",
  "role": 1
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| password | string | ✗ | 新密码，为空则不修改 |
| role | int | ✗ | 新角色 |

> 注意：username 不可修改。

**成功响应 (code=200)：** `{"code":200,"msg":"updated","data":null,"timestamp":...}`

**失败响应：** `{"code":500,"msg":"user not found"}`

---

### 6.11 用户管理 — 删除（管理员）

```
DELETE /api/admin/users/{id}
```

**路径参数：** `id` — 用户 ID

**成功响应 (code=200)：** `{"code":200,"msg":"deleted","data":null,"timestamp":...}`

**失败响应：** `{"code":500,"msg":"user not found"}`

---

### 6.12 商品管理 — 新增（管理员）

```
POST /api/admin/goods
```

**请求头：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| Content-Type | string | ✓ | `application/json` |
| token | string | ✓ | 管理员 JWT Token |

**请求体：**

```json
{
  "goodsName": "华为 Mate 70 Pro",
  "price": 6999.00,
  "stock": 300
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| goodsName | string | ✓ | 商品名称 |
| price | decimal | ✓ | 单价 |
| stock | int | ✓ | 库存数量 |

---

### 6.13 商品管理 — 编辑（管理员）

```
PUT /api/admin/goods/{id}
```

**请求头：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| Content-Type | string | ✓ | `application/json` |
| token | string | ✓ | 管理员 JWT Token |

**路径参数：** `id` — 商品 ID

**请求体：**

```json
{
  "goodsName": "华为 Mate 70 Pro",
  "price": 5999.00,
  "stock": 200
}
```

---

### 6.14 商品管理 — 删除（管理员）

```
DELETE /api/admin/goods/{id}
```

**路径参数：** `id` — 商品 ID

---

### 6.15 订单管理 — 全部列表（管理员）

```
GET /api/admin/orders
```

**请求参数（Query）：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| keyword | string | ✗ | 按订单号模糊搜索 |

**成功响应 (code=200)：**

```json
{
  "code": 200,
  "msg": "success",
  "data": [
    {
      "id": 10,
      "orderNo": "a1b2c3d4e5f67890abcdef1234567890",
      "userId": 2,
      "goodsId": 1,
      "payPrice": 9999.00,
      "createTs": 1716307200000,
      "status": 0
    }
  ],
  "timestamp": 1716307200000
}
```

---

### 6.16 订单管理 — 标记已支付（管理员）

```
PUT /api/admin/orders/{id}/pay
```

**请求头：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| Content-Type | string | ✓ | `application/json` |
| token | string | ✓ | 管理员 JWT Token |

**路径参数：** `id` — 订单 ID

**成功响应 (code=200)：** `{"code":200,"msg":"paid","data":null,"timestamp":...}`

**失败响应：** `{"code":500,"msg":"order not found"}`

---

## 七、接口汇总表

### 用户端接口（/api）

| 方法 | 路径 | 说明 | Token | Admin |
|------|------|------|:---:|:---:|
| POST | `/user/login` | 登录，返回 token + role | ✗ | ✗ |
| GET | `/user/info` | 当前用户信息 | ✓ | ✗ |
| GET | `/goods/list` | 商品列表（?keyword=） | ✓ | ✗ |
| POST | `/order/buy` | 购买商品 | ✓ | ✗ |
| GET | `/order/list` | 我的订单（?keyword=） | ✓ | ✗ |

### 管理端接口（/api/admin）

| 方法 | 路径 | 说明 | Token | Admin |
|------|------|------|:---:|:---:|
| GET | `/status` | 系统运行状态 | ✓ | ✓ |
| GET | `/logs` | 操作日志 | ✓ | ✓ |
| GET | `/users` | 用户列表 | ✓ | ✓ |
| POST | `/users` | 新增用户 | ✓ | ✓ |
| PUT | `/users/{id}` | 编辑用户 | ✓ | ✓ |
| DELETE | `/users/{id}` | 删除用户 | ✓ | ✓ |
| POST | `/goods` | 新增商品 | ✓ | ✓ |
| PUT | `/goods/{id}` | 编辑商品 | ✓ | ✓ |
| DELETE | `/goods/{id}` | 删除商品 | ✓ | ✓ |
| GET | `/orders` | 全部订单 | ✓ | ✓ |
| PUT | `/orders/{id}/pay` | 标记已支付 | ✓ | ✓ |

---

## 八、JMeter 测试建议

### 8.1 测试计划结构

```
Test Plan
├── setUp Thread Group（初始化）
│   └── 登录获取 Token 并保存到 JMeter 变量
├── Thread Group — 用户端压测
│   ├── 商品列表查询
│   ├── 下单购买
│   └── 我的订单查询
├── Thread Group — 管理端操作
│   └── 管理端各接口
└── tearDown Thread Group（清理）
```

### 8.2 关键点

1. **密码加密**：每次登录前需重新计算 `password = MD5(明文 + ts)`，ts 必须用实时时间戳（±5 分钟窗口，过期会被拒绝）
2. **Token 传递**：登录后用 JSON 提取器获取 `$.data.token`，存入变量，后续请求头中携带
3. **并发下单**：模拟多用户抢购同一商品，验证库存扣减正确性（不超卖）
4. **Token 过期**：1 小时后 Token 失效，需重新登录获取新 Token
5. **管理员判断**：通过登录返回的 `$.data.role` 判断，role=1 才可调用管理接口

### 8.3 压测规格

| 指标 | 要求 |
|------|------|
| 最大并发用户 | 300 |
| 登录 + 查询 + 下单 | 覆盖全部业务场景 |
| 数据库连接池 | 默认 HikariCP |

---

## 九、错误码速查

| code | 含义 | 常见原因 |
|:---:|------|------|
| 200 | 成功 | — |
| 400 | 参数错误 | 必填字段缺失、时间戳过期或格式错误 |
| 401 | 鉴权失败 | Token 缺失/过期/无效，或用户名密码错误 |
| 403 | 无权限 | 非管理员调用 /api/admin/** 接口 |
| 404 | 资源不存在 | 用户不存在 |
| 415 | 不支持的媒体类型 | Content-Type 不是 `application/json` |
| 500 | 服务端错误 | 库存不足、用户名重复、记录不存在 |
