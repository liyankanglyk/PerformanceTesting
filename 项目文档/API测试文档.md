# 速购商城 API 测试文档

> 本文档供接口测试 / JMeter 压测人员使用，包含完整请求/响应示例及鉴权说明。

---

## 一、系统信息

| 项目 | 说明 |
|------|------|
| 框架 | SpringBoot 3.4.6 + MyBatis-Plus 3.5.11 |
| 数据库 | MySQL 8.0 |
| 接口文档 | `/doc.html` → `/swagger-ui/index.html`（springdoc-openapi 2.8.5） |
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
curl -X POST 'http://localhost:6060/api/user/login' \
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
curl -X GET 'http://localhost:6060/api/user/info' \
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
curl -X GET 'http://localhost:6060/api/goods/list' -H 'token: xxx'

# 模糊搜索
curl -X GET 'http://localhost:6060/api/goods/list?keyword=手机' -H 'token: xxx'
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
// 商品不存在 (code=500)
{"code":500,"msg":"goods not found","data":null,"timestamp":1716307200000}
// 账号已被管理员删除，旧 Token 不能再下单 (code=401)
{"code":401,"msg":"account not found, please login again","data":null,"timestamp":1716307200000}
```

**并发说明：** 系统使用数据库原子操作 `UPDATE goods SET stock = stock - 1 WHERE id = ? AND stock > 0` 保证高并发下不超卖。库存耗尽后所有争抢请求返回 `"insufficient stock"`，商品不存在则返回 `"goods not found"`（两者不再混淆）。

**curl 示例：**
```bash
curl -X POST 'http://localhost:6060/api/order/buy' \
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
curl -X GET 'http://localhost:6060/api/order/list' -H 'token: xxx'
curl -X GET 'http://localhost:6060/api/order/list?keyword=a1b2' -H 'token: xxx'
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
    "maxMemory": 4294967296,
    "heapPct": 0.0122,
    "gcCount": 16,
    "gcTimeMs": 99
  },
  "timestamp": 1716307200000
}
```

**字段说明（本轮新增 3 个，旧字段一个没动）**

| 字段 | 类型 | 说明 |
|------|------|------|
| heapPct | number | 堆使用率 0~1，可能为 `null`（JVM 未上报 -Xmx 时）；概览页进度条用 |
| gcCount / gcTimeMs | number | 进程启动以来的 GC 次数与累计耗时（ms） |

已有 JMeter 断言路径（`$.data.heapUsed` 等）不受影响；新字段是纯追加。

**curl 示例：**
```bash
curl -X GET 'http://localhost:6060/api/admin/status' -H 'token: <admin_token>'
```

---

### 6.7 操作日志列表（管理员，分页）

```
GET /api/admin/logs?page=1&size=10&keyword=&action=
```

**请求参数（Query）：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| page | long | ✗ | 页码，从 1 开始 |
| size | long | ✗ | **每页 10 条（默认）**，上限 200 |
| keyword | string | ✗ | 关键词搜索（匹配操作人 / 操作类型 / 操作详情 / **IP**） |
| action | string | ✗ | 按操作类型精确筛选，如 `UPDATE_GOODS` |

> 旧版是 `limit`（一次性返回 N 条），已换成 `page`/`size`；响应体也从数组变成分页对象。
> JMeter 断言路径要跟着改：`$.data` → `$.data.records`。

**成功响应 (code=200)：**

```json
{
  "code": 200,
  "msg": "success",
  "data": [
    {
      "id": 12,
      "adminId": 1,
      "username": "admin",
      "action": "UPDATE_GOODS",
      "detail": "编辑商品 #16 闭环商品：单价 100.00→66.60；库存 3→0",
      "createTime": "2026-05-22T10:00:00"
    }
  ],
  "timestamp": 1716307200000
}
```

**响应字段（管理端日志表列 = ID / 操作人 / 角色 / 操作类型 / 详情 / IP / 时间）：**

| 字段 | 含义 | 说明 |
|------|------|------|
| `id` | 日志 ID | 同秒写入靠它保证翻页稳定 |
| `operatorId` | 操作人 ID | 原 `adminId`；管理员与普通用户都可能是操作人 |
| `username` | 操作人用户名 | |
| `role` | 操作人角色 | `1` 管理员 / `0` 普通用户；取自 Token 声明，登录/退出时由控制器补齐 |
| `action` | 操作类型枚举 | 前端只显示中文（见 `ACTION_ZH`），原枚举在单元格 `title` 里 |
| `detail` | 详情 | 含变更前后对比；**不再拼 IP**（IP 是独立列，避免两处不一致） |
| `ip` | 来源 IP | 统一 IPv4 写法，可用 `keyword` 直接按 IP 搜 |
| `createTime` | 时间 | `yyyy-MM-ddTHH:mm:ss` |

**detail 内容约定（旧版只写 `id=1` / `name=xxx`，事后无法审计）：**

| action | detail 示例 |
|--------|-------------|
| ADMIN_LOGIN | `管理员登录（IP 0:0:0:0:0:0:0:1）` |
| USER_LOGIN | `用户登录（IP 127.0.0.1）`（**普通用户登录**，原先完全不记录） |
| ADMIN_LOGOUT | `管理员退出（IP 127.0.0.1）` |
| USER_LOGOUT | `用户退出（IP 127.0.0.1）`（原先没有退出接口，退出从不留痕） |
| PLACE_ORDER | `#1 订单号 1d41fee5…（商品 #16，金额 12.30）`（**普通用户下单**） |
| CREATE_USER | `创建用户 #5 cli_001（角色: 普通用户）` |
| UPDATE_USER | `编辑用户 #5 cli_001：角色 普通用户→管理员；密码已重置` / `…（无实际变更）` |
| DELETE_USER | `删除用户 #5 cli_001（角色: 普通用户）` |
| CREATE_GOODS | `新增商品 #16 闭环商品（单价 100.00，库存 3）` |
| UPDATE_GOODS | `编辑商品 #16 闭环商品：单价 100.00→66.60；库存 3→0` |
| DELETE_GOODS | `删除商品 #17 将被下单（单价 5.00，原库存 4）` |
| PAY_ORDER | `确认支付：#1 订单号 6c2b…（商品 #17，金额 5.00）`（**由用户自己支付**，操作人=该用户） |
| DELETE_ORDER | 单条：`删除订单：#1 订单号 6c2b…（用户 #5，商品 #17，金额 5.00，未支付）`；批量：`批量删除订单 3 条（用户 #6）：#41 aaa111(¥5.00,未支付)，#42 bbb222(¥12.30,已支付) 等共 3 条`（最多列 5 个样本，防止一条日志撑爆字段） |

> **IP 一律 IPv4 写法**：本机/内网用 localhost 访问时，Servlet 给出的其实是 IPv6 回环 `0:0:0:0:0:0:0:1`，日志里统一归一化为 `127.0.0.1`；`::ffff:10.1.2.3` 归一化为 `10.1.2.3`；走反向代理时优先取 `X-Forwarded-For` 第一跳、其次 `X-Real-IP`，非法值（含换行、端口、SQL 片段等）一律丢弃并回落到 socket 地址，避免把请求头原样写进日志。真实 IPv6 外网地址无法凭空变成 IPv4，会保留紧凑写法。
>
> **操作人是谁**：`operator_id` / `username` 现在既可能是管理员，也可能是普通用户（`USER_LOGIN`、`PLACE_ORDER` 来自用户端），其余动作仍只来自管理端。
>
> **审计 IP 能不能信？** `X-Forwarded-For` / `X-Real-IP` 客户端可以自己写，所以默认**不采信**，
> 日志里的 IP 一律取 socket 地址（`ip.trust-forwarded-headers=false`）。
> 只有确认前面挂了会覆写这些头的 nginx/SLB 时才打开：`ip.trust-forwarded-headers=true`（也可用启动参数）。
> 打开后取 `X-Forwarded-For` 第一跳；单跳不是合法 IP 就整跳丢弃，绝不让请求头内容原样进日志。
>
> **用户行为审计开关**：管理端增删改日志始终记录；普通用户行为由配置控制
>
> ```properties
> # application.properties，也可用启动参数 --operation-log.user-actions-enabled=false
> operation-log.user-actions-enabled=true
> ```
>
> 只记录**成功**的写操作（登录失败、下单失败、查询都不写），避免压测时刷满日志表。
> 实测开销（本机串行各 30 次采样，量级参考）：开启时下单平均 25.5ms / P95 38ms，关闭时平均 18ms / P95 26ms —— 约等于每次下单多一次 INSERT 往返。**做压测基线时请固定该开关状态。**

> 日志不记录明文密码；保护规则拒绝的变更（返回 500/400）不会产生日志行，所以日志里的每一条都是真实生效的操作。

**操作类型（action）枚举：**

| action | 说明 |
|--------|------|
> 管理后台的「操作类型」列与筛选下拉**显示中文**（如 `用户下单（PLACE_ORDER）`），原始枚举值仍保留在单元格的 `title` 与小字 code 里，方便和接口/脚本对照；映射表在 `/js/api.js` 的 `ACTION_ZH`，管理端与用户端共用一份。

| ADMIN_LOGIN | 管理员登录 |
| CREATE_USER | 新增用户 |
| UPDATE_USER | 编辑用户 |
| DELETE_USER | 删除用户 |
| CREATE_GOODS | 新增商品 |
| UPDATE_GOODS | 编辑商品 |
| DELETE_GOODS | 删除商品 |
| ADMIN_LOGOUT | 管理员退出 |
| USER_LOGIN | 用户登录 |
| USER_LOGOUT | 用户退出 |
| PLACE_ORDER | 用户下单 |
| PAY_ORDER | 用户支付（确认支付，操作人是用户本人） |
| DELETE_ORDER | 删除订单（单条或批量清理） |

---

### 6.8 用户管理 — 列表（管理员，分页）

```
GET /api/admin/users
```

**请求参数（Query）：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| keyword | string | ✗ | 按用户名模糊搜索 |
| page | long | ✗ | 页码，从 1 开始；传 0/负数按 1 处理 |
| size | long | ✗ | 每页条数，默认 20，上限 200（超出自动夹住） |

**成功响应 (code=200)：** `data` 是分页对象，不再是数组

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "records": [
      { "id": 1, "username": "admin", "role": 1, "createTime": "2026-05-22T10:00:00", "password": null }
    ],
    "total": 25,
    "page": 1,
    "size": 10,
    "pages": 3
  },
  "timestamp": 1716307200000
}
```

> 页码越界（如 page=9999）返回 `records: []` 但 `total/pages` 仍然正确，不会回绕到第一页。

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

**失败响应：**
- `{"code":400,"msg":"username is required"}` / `password is required` / `username too long (max 50)` / `password too long (max 100)` / `role must be 0 or 1`
- `{"code":500,"msg":"username already exists"}`
- `{"code":400,"msg":"invalid request body"}`（请求体不是合法 JSON）

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
| password | string | ✗ | 新密码，为空或纯空格则不修改 |
| role | int | ✗ | 新角色（只能是 0 或 1） |

> 注意：username 不可修改；提交未变化的值仍返回成功（幂等）。

**成功响应 (code=200)：** `{"code":200,"msg":"updated","data":null,"timestamp":...}`

**失败响应：**
- `{"code":500,"msg":"user not found"}`
- `{"code":400,"msg":"nothing to update: password or role is required"}`（空请求体）
- `{"code":400,"msg":"role must be 0 or 1"}`
- `{"code":500,"msg":"cannot remove admin role from your own account"}`（不能取消自己的管理员角色）
- `{"code":500,"msg":"at least one admin account is required"}`

> username 与 create_time 不可通过请求体修改（传了也会被服务端忽略）。

---

### 6.11 用户管理 — 删除（管理员）

```
DELETE /api/admin/users/{id}
```

**路径参数：** `id` — 用户 ID

**成功响应 (code=200)：** `{"code":200,"msg":"deleted","data":null,"timestamp":...}`

**失败响应：**
- `{"code":500,"msg":"user not found"}`
- `{"code":500,"msg":"cannot delete your own account"}`
- `{"code":500,"msg":"at least one admin account is required"}`
- `{"code":500,"msg":"user still has orders, delete orders first"}`

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
| goodsName | string | ✓ | 商品名称（≤ 200） |
| price | decimal | ✓ | 单价（0 ≤ price ≤ 99999999.99） |
| stock | int | ✓ | 库存数量（≥ 0） |

**成功响应 (code=200)：** `{"code":200,"msg":"created","data":{"id":16,"goodsName":"华为 Mate 70 Pro","price":6999.00,"stock":300,"createTime":"2026-05-22T10:00:00"},"timestamp":...}`

**失败响应（code=400）：** `goodsName is required` / `goodsName too long (max 200)` / `price is required` /
`price must be greater than or equal to 0` / `price too large (max 99999999.99)` / `price supports at most 2 decimal places` /
`stock is required` / `stock must be greater than or equal to 0`

> 价格最多两位小数：超出会被 `DECIMAL(10,2)` 静默四舍五入（9.999 变 10.00），因此服务端直接拒绝。

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

> 字段不传（或传 null）表示不修改该字段；三个字段全为空时返回 `400 nothing to update`。
> 提交与库里完全相同的值仍返回成功（MySQL 只统计实际变更行，affected rows = 0 不代表失败）。
> 校验规则与新增相同；商品不存在返回 `{"code":500,"msg":"goods not found"}`。

---

### 6.14 商品管理 — 删除（管理员）

```
DELETE /api/admin/goods/{id}
```

**路径参数：** `id` — 商品 ID

**成功响应：** `{"code":200,"msg":"deleted","data":null,"timestamp":...}`

**失败响应：**
- `{"code":500,"msg":"goods not found"}`
- `{"code":500,"msg":"goods still has orders, delete orders first"}`

---

### 6.15 订单管理 — 全部列表（管理员，分页）

```
GET /api/admin/orders
```

**请求参数（Query）：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| keyword | string | ✗ | 按订单号模糊搜索 |
| status | int | ✗ | 0=未支付 / 1=已支付；不传为全部；其他值返回 `400 status must be 0 or 1` |
| page | long | ✗ | 页码，从 1 开始 |
| size | long | ✗ | 每页条数，默认 20，上限 200 |

**成功响应 (code=200)：**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "records": [
      { "id": 10, "orderNo": "a1b2c3...", "userId": 2, "goodsId": 1, "payPrice": 9999.00, "createTs": 1716307200000, "status": 0 }
    ],
    "total": 30,
    "page": 1,
    "size": 20,
    "pages": 2
  },
  "timestamp": 1716307200000
}
```

> 排序：`create_ts DESC, id DESC`。同一毫秒会下很多单（并发抢秒杀场景），必须靠 id 做第二排序键，
> 否则翻页会出现重复行/漏行；断言时不要假设全局严格单调仅靠 createTs。

---

### 6.16 订单管理 — 标记已支付（管理员）**【已下线】**

> 支付是用户端行为，管理员代付既不符合业务语义也会污染审计。该接口已删除：
> `PUT /api/admin/orders/{id}/pay` 现在返回 404/405，请改用 **6.23 确认支付订单（用户端）**。


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

**失败响应：**
- `{"code":500,"msg":"order not found"}`
- `{"code":500,"msg":"order already paid"}`（重复标记支付不会再次成功，也不会重复写日志）

> 该接口不需要请求体，`PUT /api/admin/orders/{id}/pay` 直接调用即可（旧版强制 Content-Type: application/json，无 body 的 curl/JMeter 请求会 415）。

---

### 6.18 商品管理 — 列表（管理员，分页）

```
GET /api/admin/goods
```

> 旧版没有这个接口，管理端商品面板只能借用用户端 `/goods/list`，按文档对 `/api/admin/goods` 发 GET 会拿到 405。

**请求参数（Query）：** `page`（默认 1）、`size`（默认 10，上限 200）、`keyword`（按商品名模糊搜索，可选）

**成功响应：** `data = {records, total, page, size, pages}`，`records` 元素结构同 `/goods/list`，固定按 id 升序。

> 旧版是一次性返回完整数组，现已与服务端分页统一；JMeter 取列表项请用 `$.data.records[*].id`。

---

### 6.19 订单管理 — 删除（管理员）

```
DELETE /api/admin/orders/{id}
```

**成功响应：** `{"code":200,"msg":"deleted","data":null,"timestamp":...}`

**失败响应：** `{"code":500,"msg":"order not found"}`（重复删除同样返回该 msg）

---

### 6.20 订单管理 — 按用户/商品批量清理（管理员）

```
DELETE /api/admin/orders?userId={id}
DELETE /api/admin/orders?goodsId={id}
```

用途：删除用户/商品前清掉关联订单（否则会被保护规则拒绝）。

**成功响应：** `{"code":200,"msg":"deleted","data":3,"timestamp":...}`（data = 实际删除条数）
无匹配时：`{"code":200,"msg":"no orders matched","data":0}`（不写日志）

**失败响应：** `{"code":400,"msg":"userId or goodsId is required"}`（两个参数都不传时拒绝，防误删全表）

---

### 6.21 操作日志 — 操作类型列表（管理员）

```
GET /api/admin/log-actions
```

**成功响应：** `{"code":200,"data":["ADMIN_LOGIN","CREATE_GOODS",...]}`

---

### 6.22 退出登录

```
POST /api/user/logout
```

**请求头**：`token`（必填，缺失时 `JwtInterceptor` 直接返回 HTTP 401）

**成功响应：** `{"code":200,"msg":"success","data":"已退出","timestamp":1716307200000}`

**行为**：管理员记 `ADMIN_LOGOUT`（始终记录），普通用户记 `USER_LOGOUT`（受`operation-log.user-actions-enabled` 开关控制）。

> **注意**：本项目是**无状态 JWT**，该接口只写审计日志，**不会让 Token 失效**。客户端仍需自行清除本地 token；要做真正的服务端踢人需引入 token 黑名单（Redis），当前未实现。

管理后台 `admin.html` 与商城 `index.html` 的「退出」按钮都会先调用本接口再跳转，因此退出在操作日志里可查。

### 6.23 确认支付订单（用户端）

```
PUT /api/order/pay/{orderNo}
```

**请求头**：`token`（当前登录用户）  **路径参数**：下单接口返回的 `orderNo`（32 位）

**成功响应：** `{"code":200,"msg":"paid","data":{"id":1,"orderNo":"…","status":1,…}}`

| 场景 | code | msg |
| :--- | :--- | :--- |
| 正常支付（0→1） | 200 | paid |
| 订单不属于当前用户 | 403 | forbidden: not your order |
| 订单号不存在 | 404 | order not found |
| 订单号格式非法（含编码过的注入串） | 400 | invalid orderNo |
| 重复支付（双击/JMeter 重试） | 500 | order already paid |

**并发与幂等**：条件更新 `WHERE id = ? AND status = 0`，只有一次能改成功，失败的那次不写日志，因此一笔订单的 `PAY_ORDER` 日志最多一条。

**商城页面**：「我的订单」里未支付行显示 `确认支付 ¥金额` 按钮，已支付行显示「已完成支付」。

### 6.24 系统概览汇总（管理员，新增）

```
GET /api/admin/summary?windowMinutes=5
```

**用途**：系统概览页专用聚合接口。一次请求返回「业务计数 + 下单/支付速率 + 库存 + JVM」，
替代前端打 4 个列表接口只为取 `total` 的做法。

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| windowMinutes | int | ✗ | 速率统计窗口，**默认 5**，夹到 1-60（传 0/999999 不报错，按边界生效）；非数字返回 400 |

**成功响应 (code=200)：**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "windowMinutes": 5,
    "generatedAt": 1716307200000,
    "counts": { "users": 4, "goods": 15, "orders": 12480, "paidOrders": 9800, "logs": 25000 },
    "rate": { "placed": 620, "paid": 610, "placedPerMin": 124.0, "payRate": 0.984, "windowMinutes": 5 },
    "stock": { "total": 5230, "soldOutGoods": 0 },
    "jvm": { "uptime": 123456, "heapUsed": 52428800, "heapMax": 4294967296, "heapPct": 0.0122,
             "threadCount": 25, "peakThreadCount": 30, "gcCount": 16, "gcTimeMs": 99,
             "availableProcessors": 8, "nonHeapUsed": 16777216 }
  },
  "timestamp": 1716307200000
}
```

**注意点**

| 点 | 说明 |
|----|------|
| `rate.payRate` 可能为 `null` | 窗口内没有新订单时支付率无定义，**不是 0**（0 会被误读成吞吐掉了）；前端显示 `-` |
| `rate.placedPerMin` | `窗口内订单数 / 窗口分钟数`，保留 1 位小数 |
| `counts.orders - counts.paidOrders` | 未支付订单数（概览卡片直接算给前端看） |
| 无 `money` / GMV | 按需求概览不展示成交金额，接口不返回该字段（省掉两条 `SUM(pay_price)`） |
| 开销 | 每次调用 7 条 `COUNT`，实测平均 14-30ms。**压测时不要把概览自动刷新调到 1s**，也不要把它放进 JMeter 高并发线程组 |
| 索引依赖 | `orders.idx_create_ts`、`orders.idx_status_create_ts`（本轮新增）；没有它们速率查询会全表扫 |
| 审计 | 只读接口，不写 operation_log |

**curl 示例：**
```bash
curl -X GET 'http://localhost:6060/api/admin/summary?windowMinutes=15' -H 'token: <admin_token>'
```

---

### 6.25 数据库初始化状态（管理员）

```
GET /api/admin/db/state
```

**响应 `data`：**

| 字段 | 类型 | 说明 |
|------|------|------|
| initMode / startupMode | string | 配置文件里的原值与解析结果：`always` / `if-absent` / `never`（无法识别的值按 `ALWAYS` 兜底，不让服务起不来） |
| resetEnabled | boolean | 手动重置是否放开（`db.reset.enabled`）；为 `false` 时概览页按钮变灰 |
| busy | boolean | 是否有重置正在执行 |
| database / tablesPresent | string / boolean | 库名与四张表是否就绪 |
| lastResetAt / lastResetMode / lastResetMs | — | 本进程上一次手动重置的时间、模式与耗时（重置会重建表，无处持久化，所以只记内存；重启后为空） |
| counts | object | 当前 user / goods / orders / operation_log 行数 |

不含任何数据源账号口令，可安全展示在页面上。

---

### 6.26 系统重置（管理员，破坏性）

```
POST /api/admin/db/reset?confirm=RESET&mode=full
```

压测跑完一轮后把库恢复到「刚部署完」的状态，不用重启服务。管理端系统概览页的**系统重置**按钮就是它，固定 `mode=full`。

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|:---:|------|
| confirm | string | ✓ | **必须一字不差等于 `RESET`**（区分大小写），否则 400 且完全不碰数据库。防 JMeter 脚本、防误点 |
| mode | string | ✗ | `full`（默认）执行 `performance_testing.sql`，表结构、索引与种子数据一起重建。**`data` 已失去独立含义**（结构与数据合并在同一份脚本里），传 `data` 会降级成 `full` 并回 `downgradedToFull=true`，保留取值只为兼容旧调用 |

**成功响应（code=200）：**

```json
{
  "code": 200,
  "msg": "数据库已重置，请重新登录",
  "data": {
    "mode": "full",
    "elapsedMs": 61,
    "downgradedToFull": false,
    "finishedAt": "2026-05-22 10:00:00",
    "counts": { "users": 4, "goods": 15, "orders": 0, "logs": 1 },
    "notice": "四张表已清空并重新灌入初始数据；账号 ID 可能已变化，请重新登录后再看页面"
  }
}
```

**失败响应**

| 场景 | code | msg |
|------|:---:|-----|
| 没带 / 带错 confirm | 400 | `confirm required: pass confirm=RESET` |
| mode 不是 full/data | 400 | `mode must be full or data` |
| 服务器关了 `db.reset.enabled=false` | 403 | `db reset is disabled (db.reset.enabled=false)` |
| 已有重置在跑（并发点击 / 脚本重放） | 409 | `database is initializing, retry later` |

**必须知道的几件事**

| 点 | 说明 |
|----|------|
| 启动不再自动清库？ | 由 `db.init-mode` 决定：`always`（默认，历史行为，每次重启清库重建）/ `if-absent`（只在库或表缺失时建）/ `never`（完全不动）。想「重启保留上一轮数据、需要时手动重置」就把默认值改成 `if-absent` |
| 审计顺序 | 先重建表、**后**写审计行，所以 `operation_log` 被清空后仍然查得到这次重置（`DB_RESET`，含模式与耗时，中文显示「重置测试数据」） |
| 会不会踢掉登录态 | **不必然**：`TRUNCATE`/重建会让自增 id 从 1 重新开始，管理员通常还是 id=1，旧 Token 仍指向它。但数据已经换了一套，所以前端重置成功后会主动跳登录页 |
| 压测进行中千万别调 | 会 `TRUNCATE` 正在写入的 `orders`，本轮数据当场作废；并发保护只防「两次重置交叉」，不防「重置撞上业务写入」 |
| JMeter | 该接口不要放进线程组（`confirm=RESET` 是给人的，不是给脚本的）。若一定要重置后再压测，放在 setUp Thread Group 里单次调用 |
| 中文乱码 | 脚本固定按 **UTF-8** 读取（`DatabaseInitService.SCRIPT_ENCODING`）。历史上没指定编码时，中文 Windows（GBK 平台默认字符集）会把脚本里的 UTF-8 字节按 GBK 解码，商品名变成「鑻规灉 iPhone 16 Pro Max」。若你已有这样一个库，执行一次系统重置即可恢复 |
| 存储引擎 | 初始化脚本 `performance_testing.sql` 已把四张表统一为 `ENGINE = InnoDB` + `utf8mb4`（早期转储是 MyISAM + utf8/mb3）。**为什么重要**：MyISAM 不支持事务，`POST /api/order/buy` 的 `@Transactional` 回滚不会生效（库存已扣、插订单失败时不会退回）；防超卖两者都成立，因为扣库存是单语句条件 `UPDATE`。老库可 `ALTER TABLE ... ENGINE = InnoDB` 原地迁移（见 README 的迁移 SQL），或直接执行系统重置重建 |

**curl 示例：**
```bash
curl -X POST 'http://localhost:6060/api/admin/db/reset?mode=full&confirm=RESET' -H 'token: <admin_token>'
```

---

### 6.17 管理端统一校验与保护规则（写压测脚本前必读）

| 接口 | 规则 | 失败 code |
|------|------|:---:|
| POST/PUT `/admin/users` | username、password 必填且不超列宽（50/100）；role 只能 0/1；密码传空串 = 不修改 | 400 |
| DELETE `/admin/users/{id}` | 不能删自己；不能删最后一个管理员；有订单的用户拒绝删除 | 500 |
| PUT `/admin/users/{id}` | 不能自降级；不能使系统失去最后一个管理员 | 500 |
| POST/PUT `/admin/goods` | goodsName 必填（≤200）且不可与已有商品重复（库上有 `goods_name` 唯一索引，编辑改名同样校验；重名返回 `400 goods name already exists`）；price ≥ 0 且 ≤ 99999999.99 且最多两位小数；stock ≥ 0 | 400 |
| DELETE `/admin/goods/{id}` | 有订单的商品拒绝删除 | 500 |
| PUT `/order/pay/{orderNo}` | 只能付自己的单；orderNo 白名单 `[0-9a-zA-Z-]{8,64}`；条件更新 0→1 幂等 | 400/403/404/500 |
| `POST /user/logout` | 需要有效 Token（写审计日志，不使 Token 失效） | 401 |
| POST `/order/buy` | 账号已被管理员删除时返回 401，不产生无效 user_id 订单 | 401 |
| 任意 `/admin/**` | 角色被改掉或账号被删后，旧 Token 立即 403 / 401 | — |

所有异常（包括 404/405/415）都返回统一结构 `{"code":...,"msg":...,"data":null,"timestamp":...}`，
断言时取 `$.code` / `$.msg` 即可，不会再出现 Spring 默认错误体。

## 七、分页约定（管理端列表）

| 项 | 约定 |
|------|------|
| 适用接口 | **全部四个管理端列表**：`GET /admin/users`、`GET /admin/goods`、`GET /admin/orders`、`GET /admin/logs` |
| 分页参数 | `page`（从 1 开始）、`size`（**默认 10**） |
| size 上限 | 200（服务端与 MyBatis-Plus `maxLimit` 双重夹住，`size=99999` 只会返 200 条） |
| 非法值 | `page<1` 按 1；`size<1` 按 1（不会把 0/负数传进 SQL） |
| 越界页 | `records` 为空数组，`total/pages` 仍正确；不回绕 |
| 响应结构 | `data = {records, total, page, size, pages}` |
| JMeter 断言 | 取 `$.data.records`、`$.data.total`、`$.data.pages`（**不再是 `$.data` 数组**） |
| JSON 提取器 | 列表项用 `$.data.records[0].id`；分页循环压测用 `$.data.pages` |
| 未分页接口 | 仅用户端 `GET /goods/list`、`GET /order/list`（商城页面与压测脚本依赖完整数组）；管理端四个列表已全部分页 |

> 分页依赖 `mybatis-plus-jsqlparser` + `PaginationInnerInterceptor`。两者缺任一个时
> `page()` 不报错但会退化成“全表读进内存”，所以分页用例必须断言 `records.length ≤ size`。

## 八、管理后台页面路由（刷新不再掉回首页）

管理后台是单页应用，面板状态写在 URL hash 里，**刷新 / 收藏 / 前进后退都会还原当前视图**（含搜索词、筛选、页码）：

| 路由 | 面板 | 可携带参数 |
|------|------|-----------|
| `#/dashboard` | 系统概览 | 无 |
| `#/users` | 用户管理 | `keyword`、`page`、`size` |
| `#/goods` | 商品管理 | `keyword`、`page`、`size` |
| `#/orders` | 订单管理 | `keyword`、`status`、`page`、`size` |
| `#/logs` | 操作日志 | `keyword`、`action`、`page`、`size` |

示例：`http://localhost:6060/admin.html#/orders?status=0&page=2&size=50`

**用户端 `index.html` 同样走 hash 路由**（以前刷新一定掉回商品列表）：

| 路由 | 页面 | 可携带参数 |
| :--- | :--- | :--- |
| `#/goods` | 商品列表（默认） | `keyword` |
| `#/orders` | 我的订单（含确认支付） | `keyword` |

示例：`http://localhost:6060/index.html#/orders?keyword=c20520373b`

- 未知路由回落 `#/dashboard`；参数值非法时按默认处理（如 `status=9` 视为“全部”）。
- 翻页/搜索用 `history.replaceState` 更新 hash，**不污染历史记录**，后退键仍是“上一个面板”。
- 默认值（`page=1`、`size=20`、空关键词）不写进 URL，保持地址干净。
- 直接访问 `/admin.html` 会自动补 `#/dashboard`；普通用户带着 `#/users` 进来仍会被导向商城首页。

## 九、接口汇总表

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
| GET | `/summary` | **系统概览汇总（新增）**：业务计数 + 速率 + 库存 + JVM | ✓ | ✓ |
| GET | `/db/state` | **数据库初始化状态（新增）**：启动策略、是否可手动重置、上次重置 | ✓ | ✓ |
| POST | `/db/reset` | **系统重置（新增，破坏性）**：重建四张表并灌初始数据，需 `confirm=RESET` | ✓ | ✓ |
| GET | `/logs` | 操作日志（分页：?page=&size=&keyword=&action=） | ✓ | ✓ |
| GET | `/log-actions` | 日志操作类型列表 | ✓ | ✓ |
| GET | `/users` | 用户列表（分页 ?page=&size=&keyword=） | ✓ | ✓ |
| POST | `/users` | 新增用户 | ✓ | ✓ |
| PUT | `/users/{id}` | 编辑用户 | ✓ | ✓ |
| DELETE | `/users/{id}` | 删除用户 | ✓ | ✓ |
| GET | `/goods` | 商品列表（分页 ?page=&size=&keyword=） | ✓ | ✓ |
| POST | `/goods` | 新增商品 | ✓ | ✓ |
| PUT | `/goods/{id}` | 编辑商品 | ✓ | ✓ |
| DELETE | `/goods/{id}` | 删除商品 | ✓ | ✓ |
| GET | `/orders` | 全部订单（分页 ?page=&size=&keyword=&status=） | ✓ | ✓ |
| PUT | `/order/pay/{orderNo}` | **确认支付（用户端，仅自己的订单）** | ✓ | ✗ |
| POST | `/user/logout` | 退出登录并写审计日志 | ✓ | ✗ |
| DELETE | `/orders/{id}` | 删除订单 | ✓ | ✓ |
| DELETE | `/orders` | 按 userId/goodsId 批量清理订单 | ✓ | ✓ |

---

## 十、JMeter 测试建议

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
2. **管理端列表断言用分页字段**：`$.data.records`、`$.data.total`、`$.data.pages`（`data` 已不是数组）
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

## 十一、错误码速查

| code | 含义 | 常见原因 |
|:---:|------|------|
| 200 | 成功 | — |
| 400 | 参数错误 | 必填字段缺失、时间戳过期或格式错误、管理端字段校验不通过、请求体不是合法 JSON、缺少 ts 请求头、**数字参数类型不匹配**（`?page=abc` → `invalid parameter: page`） |
| 401 | 鉴权失败 | Token 缺失/过期/无效，用户名密码错误，或账号已被管理员删除 |
| 403 | 无权限 | 非管理员调用 /api/admin/**，或管理员角色已被后台修改（旧 Token 不再有效） |
| 404 | 资源不存在 | 路径不存在（/user/info 查不到用户时返回 code=404） |
| 405 / 415 | 请求方式/媒体类型 | 方法不支持、Content-Type 不是 application/json（响应体仍为统一格式） |
| 500 | 服务端错误 | 库存不足、用户名重复、记录不存在、违反管理端保护规则 |

> 修正：数字参数传了非数字（`?page=abc`、`?size=1.5`、`?windowMinutes=abc`）此前返回 **HTTP 500**，且把 Java 转换异常原文吞进 `msg`；现已归入 400 并返回 `invalid parameter: <参数名>`。
> 原因是 `MethodArgumentTypeMismatchException` 不实现 `ErrorResponse`，原先的统一分支拿不到状态码。已补单测

> 所有错误（含框架抛出的 4xx/5xx）都统一定义为 `{"code","msg","data","timestamp"}`，
> 断言时取 `$.code` / `$.msg`；业务错误仍以 HTTP 200 返回（与各 Controller 风格一致）。
