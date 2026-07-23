# 🚀 JMeter 核心元件
> 📌 **笔记结构**：核心元件（骨架）→ 辅助元件（增强）→ 执行顺序（重点）→ 实战示例
>

---

## 一、整体架构一览
```plain
测试计划（根容器）
└── 线程组（并发控制）
    └── 取样器（请求执行）
        ├── 辅助元件：逻辑控制器 / 定时器 / 监听器
        └── 辅助元件：断言 / 配置元件 / 前后置处理器
```

> 🧩 **核心元件** = 脚本骨架（必备）  
🔧 **辅助元件** = 功能增强（按需添加）

---

## 二、核心元件（必备骨架）
三者是**严格的层级包含关系**：`测试计划 → 线程组 → 取样器`

### 1. 🗂️ 测试计划
| 属性 | 说明 |
| --- | --- |
| **定位** | 最顶层容器，相当于整个项目的"根目录" |
| **作用** | 管理全局变量、控制执行顺序、配置类路径/插件依赖 |
| **使用** | 新建项目时自动生成，无需手动创建，只需配置全局属性 |


---

### 2. 👥 线程组
**定位**：并发用户模拟器，是"干活的人"

#### 关键配置项
| 配置项 | 作用 | 示例 |
| --- | --- | --- |
| **线程数** | 模拟的并发用户数 | 100 = 100个并发用户 |
| **Ramp-Up 时间（秒）** | 所有线程启动完成的总时长，避免瞬间冲击 | 100线程 + 10s = 每秒启动10个线程 |
| **循环次数** | 每个线程执行取样器的次数 | 永远循环 + 调度器 = 持续压测指定时长 |


---

### 3. ⚡ 取样器
**定位**：实际请求发起者，是"要干的活"

| 类型 | 用途 |
| --- | --- |
| **HTTP 请求取样器** | 最常用，模拟 Web 接口 / 网页的 HTTP/HTTPS 请求 |
| **JDBC 请求取样器** | 模拟数据库 SQL 请求 |
| **FTP 请求取样器** | 模拟文件上传/下载 |
| **SOAP/XML-RPC 取样器** | 模拟 WebService 接口请求 |

#### 附：HTTP 文件上传
HTTP 请求取样器的 **Files Upload** 标签页支持模拟文件上传。

| 配置项 | 说明 | 示例 |
| --- | --- | --- |
| **File Path** | 文件路径（建议用相对路径） | `./data/avatar.png` |
| **Parameter Name** | 接口定义的字段名 | `file` |
| **MIME Type** | MIME 类型，留空则自动推断 | `image/png` / 留空 |

配置要点：
- Basic 标签页方法选 `POST`，**不要手动设 Content-Type**（JMeter 自动处理 multipart/form-data）
- 多文件上传：在 Files Upload 中添加多行即可，Parameter Name 相同则服务器按数组接收
- 参数化：File Path 填 `${变量名}`，配合 CSV 数据文件设置可实现不同线程上传不同文件
- 路径统一用正斜杠 `/`，避免 Linux 执行机上报错

> ⚠️ 文件不存在时 JMeter 不会报错，只上传空内容，务必确认路径正确


---

## 三、辅助元件（功能增强）
### 1. 🔀 逻辑控制器
**作用**：控制取样器的执行逻辑（判断 / 循环 / 分支 / 顺序），不直接发请求，只决定"哪些取样器执行、怎么执行"。

#### ① 循环控制器
让其内部的取样器额外循环执行 N 次（注意：是在线程组循环次数**之内**再叠加循环）。

| 配置项 | 说明 |
| :---: | :---: |
| 循环次数 | 填数字 = 执行 N 次；勾选"永远" = 跟随线程组设置 |


> 💡 场景：模拟用户重复提交表单、批量写入数据
>

#### ② IF 控制器
根据条件表达式决定其内部的取样器是否执行，条件为 `true` 才执行。

| 配置项 | 说明 | 示例 |
| :---: | :---: | :---: |
| Expression | 判断条件，支持 JMeter 变量和函数 | `${code}==200` |
| Interpret Condition as Variable Expression | 勾选后，支持直接写 `${变量}` 形式的条件 | 建议勾选 |


> 💡 场景：登录成功（`${code}==200`）才执行下单，否则跳过
>

#### ③ 事务控制器
将多个取样器打包为一个"事务"，在聚合报告中**额外生成一条整体耗时统计**，衡量完整业务流程的性能。

| 配置项 | 说明 |
| :---: | :---: |
| Generate parent sample | 勾选后，报告中显示事务的整体耗时（推荐勾选） |
| Include duration of timer and pre-post processors | 是否把定时器/处理器时间计入事务耗时 |


> 💡 场景：将"搜索商品 → 加入购物车 → 下单"打包为一个事务，统计整个购物流程的响应时间
>

#### ④ 随机控制器（Random Controller）
从其内部的多个取样器中**随机选一个**执行，每次循环只执行其中之一。

> 💡 场景：模拟用户随机浏览不同页面（首页、商品页、活动页）
>

#### ⑤ 随机顺序控制器（Random Order Controller）
将其内部的取样器**打乱顺序后全部执行**，每次循环执行顺序不同（与随机控制器的区别：随机控制器只执行一个，此控制器全部执行但顺序随机）。

> 💡 场景：模拟用户以不同顺序访问多个页面
>

---

#### ⑥ 吞吐量控制器
精确控制某组请求在整体流量中的**执行比例或执行次数**。

| 配置项 | 说明 | 示例 |
| :---: | --- | --- |
| Based On | `Percent Executions`（按比例）或 `Total Executions`（按次数） | 选按比例 |
| Throughput | 具体的比例或次数 | 30（即 30% 的请求走这条分支） |


> 💡 场景：模拟 70% 用户只浏览、30% 用户下单的真实流量分布
>

#### ⑦ 仅一次控制器（Once Only Controller）
其内部的取样器在整个测试中**每个线程只执行一次**，无论线程组设置了多少次循环。

> 💡 场景：登录操作只需执行一次，后续循环只重复业务操作（如下单），不重复登录
>

#### ⑧ While 控制器（While Controller）
只要条件为 `true`，就持续循环执行内部的取样器。

| 配置项 | 说明 | 示例 |
| --- | --- | --- |
| Condition | 循环条件，`LAST` = 上一个取样器失败时停止 | `${flag}==true` |


> 💡 场景：轮询接口直到返回特定状态（如任务完成），再继续后续步骤
>

#### ⑨ ForEach 控制器（ForEach Controller）
遍历一组变量（通常由提取器提取到的数组），对每个元素执行内部的取样器。

| 配置项 | 说明 | 示例 |
| --- | --- | --- |
| Input variable prefix | 变量名前缀 | `id`（对应 `id_1`、`id_2`...） |
| Output variable name | 当前遍历元素的变量名 | `current_id` |
| Start index / End index | 遍历范围 | 留空则自动遍历所有 |


> 💡 场景：先提取列表接口返回的所有商品 ID，再逐个调用详情接口
>

#### ⑩ 简单控制器（Simple Controller）
纯粹的**分组容器**，不改变任何执行逻辑，只用于组织脚本结构、提升可读性。

> 💡 场景：将"登录流程"相关的取样器放进一个简单控制器，结构一目了然
>

### 2. ⏱️ 定时器
**作用**：在**每个取样器执行前**延迟一定时间，模拟用户思考间隔，让压测更贴近真实场景。

> ⚠️ 注意：定时器对**作用域内所有取样器**生效，放在线程组下 = 每个请求前都延迟；想只对某个请求延迟，就放到该取样器的子层级。
>

---

#### ① 固定定时器（Constant Timer）
每次请求前固定等待相同时长。

| 配置项 | 说明 | 示例 |
| --- | --- | --- |
| Thread Delay（毫秒） | 延迟时长，单位毫秒 | `3000` = 延迟 3 秒 |


#### ② 统一随机定时器（Uniform Random Timer）
在"固定延迟 + 随机偏移"范围内随机等待，模拟用户的不规则操作间隔。

| 配置项 | 说明 | 示例 |
| --- | --- | --- |
| Random Delay Maximum（毫秒） | 随机偏移的最大值 | `2000` |
| Constant Delay Offset（毫秒） | 固定基础延迟 | `1000` |


> 实际延迟范围 = `Constant` ~ `Constant + Random`，示例中为 1000ms ~ 3000ms
>

#### ③ 同步定时器（Synchronizing Timer）（集合点）
让所有线程**在此处等待集合**，凑够指定数量后同时放行，制造瞬间并发冲击。

| 配置项 | 说明 | 示例 |
| --- | --- | --- |
| Number of Simultaneous Users to Group | 等待集合的线程数 | `100`（等100个线程到齐后同时发） |
| Timeout in milliseconds | 等待超时时间，超时则强制放行，`0` = 永久等待 | `5000` |


> 💡 场景：秒杀压测，模拟 100 个用户在同一时刻点击"抢购"
>

#### ④ 高斯随机定时器（Gaussian Random Timer）
按正态分布随机延迟，比统一随机更贴近真实用户行为（大多数人集中在某个时间段操作）。

| 配置项 | 说明 |
| --- | --- |
| Deviation（毫秒） | 标准差，值越大延迟越分散 |
| Constant Delay Offset（毫秒） | 延迟中心值（均值） |


#### ⑤ 泊松随机定时器（Poisson Random Timer）
按泊松分布随机延迟，适合模拟网络请求到达的随机性（比高斯分布更偏向短等待、偶尔长等待）。

| 配置项 | 说明 |
| --- | --- |
| Lambda（毫秒） | 泊松分布的 λ 值，即平均延迟时长 |
| Constant Delay Offset（毫秒） | 固定基础延迟，叠加在随机值上 |


#### ⑥ 精确吞吐量定时器（Precise Throughput Timer）
不按固定延迟控制节奏，而是直接指定**目标 TPS**，由 JMeter 自动计算延迟以达到目标吞吐量。

| 配置项 | 说明 | 示例 |
| --- | --- | --- |
| Target throughput（per minute） | 目标每分钟请求数 | `600`（即 10 TPS） |
| Throughput period（seconds） | 吞吐量统计周期 | `60` |


> 💡 场景：需要精确控制压测 TPS（如固定 100 TPS 持续压测）时，比固定定时器更精准
>

### 3. 📊 监听器
**作用**：收集并展示取样器的执行结果，是查看压测数据的"可视化窗口"。

> ⚠️ **性能提示**：监听器会消耗额外资源，正式压测时建议**只保留聚合报告**，禁用察看结果树，避免影响压测结果。
>

#### ① 察看结果树（View Results Tree）
查看每一条请求的完整请求/响应详情，是**调试脚本的核心工具**。

| 面板 | 内容 |
| --- | --- |
| 请求 | 请求方法、URL、请求头、请求体 |
| 响应 | 响应码、响应头、响应体（支持 JSON/HTML/文本 格式化展示） |
| 取样器结果 | 响应时间、连接时间、发送/接收字节数 |


> 💡 绿色 = 成功，红色 = 失败（断言不通过或网络错误）
>

#### ② 聚合报告（Aggregate Report）
压测结果的**核心统计报告**，汇总所有请求的性能指标。

| 指标列 | 含义 |
| --- | --- |
| Samples | 总请求次数 |
| Average | 平均响应时间（ms） |
| Median | 中位数响应时间（50% 的请求在此时间内完成） |
| 90%/95%/99% Line | 90%/95%/99% 的请求响应时间上限，**性能基准的核心指标** |
| Min / Max | 最小 / 最大响应时间 |
| Error% | 错误率（请求失败的比例） |
| Throughput | 吞吐量（每秒处理的请求数，即 TPS/QPS） |
| Received KB/sec | 每秒接收的数据量 |


#### ③ 汇总报告（Summary Report）
比聚合报告更精简，字段更少，适合快速查看整体结果，不需要分位数数据时使用。

#### ④ Backend Listener
将压测数据**实时推送**到外部监控系统（InfluxDB + Grafana），实现压测过程的实时可视化大屏。

| 配置项 | 说明 |
| --- | --- |
| Backend Listener implementation | 选 `InfluxdbBackendListenerClient` |
| influxdbUrl | InfluxDB 写入地址，如 `http://localhost:8086/write?db=jmeter` |
| application | 本次压测的标识名，方便在 Grafana 中区分多次压测 |


### 4. ✅ 断言
**作用**：校验取样器的响应是否符合预期。断言失败会将请求标记为**失败**，计入错误率。

#### ① 响应断言（Response Assertion）
最常用的断言，对响应的各个部分进行文本匹配校验。

| 配置项 | 说明 | 示例 |
| --- | --- | --- |
| Apply to | 作用范围 | 通常选 `Main sample only` |
| Field to Test | 被测字段 | 响应体 / 响应码 / 响应头 / URL |
| Pattern Matching Rules | 匹配规则 | 包含 / 等于 / 正则匹配 / 否定（不包含） |
| Patterns to Test | 期望值 | `200`（校验响应码）、`"code":0`（校验响应体） |


#### ② JSON 断言（JSON Assertion）
专门针对 JSON 响应体，通过 JSON Path 精准定位字段后校验其值。

| 配置项 | 说明 | 示例 |
| --- | --- | --- |
| Assert JSON Path exists | JSON Path 表达式 | `$.code` |
| Additionally assert value | 勾选后才校验字段的具体值 | ✅ 勾选 |
| Expected Value | 期望值 | `0` |


> 💡 比响应断言更精准，不会因为响应体中其他地方恰好有相同文本而误判
>

#### ③ 持续时间断言（Duration Assertion）
校验响应时间是否超过阈值，用于验证接口的性能指标是否达标。

| 配置项 | 说明 | 示例 |
| --- | --- | --- |
| Duration in milliseconds | 响应时间上限（ms） | `2000`（超过2秒则断言失败） |


#### ④ 大小断言（Size Assertion）
校验响应数据的字节大小，可用于检测响应是否异常截断或内容缺失。

| 配置项 | 说明 |
| --- | --- |
| Size to Assert | 期望的字节大小 |
| Type of Comparison | `=` / `>` / `<` / `>=` / `<=` / `!=` |


#### ⑤ XPath 断言（XPath Assertion）
对 XML 或 HTML 格式的响应使用 XPath 表达式进行校验。

| 配置项 | 说明 | 示例 |
| :---: | :---: | :---: |
| Use Tidy | 是否用 Tidy 解析 HTML（非标准 XML 时勾选） | HTML 响应时勾选 |
| XPath | XPath 表达式 | `//status[text()='success']` |
| Negate | 勾选则断言"表达式不匹配"时通过 | 一般不勾选 |


#### ⑥ HTML 断言（HTML Assertion）
校验响应体是否是合法的 HTML（通过 JTidy 解析），可检测 HTML 语法错误数量。

| 配置项 | 说明 |
| --- | --- |
| Errors only | 只统计错误（不统计警告） |
| Error threshold | 允许的最大错误数，超过则断言失败 |
| Warning threshold | 允许的最大警告数 |


> 💡 场景：测试页面接口时，校验返回的 HTML 是否结构完整、无语法错误
>

#### ⑦ 比较断言（Compare Assertion）
将**多个取样器的响应**进行横向比对，校验响应内容或响应时间是否一致。

> 💡 场景：压测多个节点返回是否一致，或新旧接口版本响应差异对比（通常配合比较可视化监听器使用）
>

### 5. ⚙️ 配置元件
**作用**：为取样器提供公共配置和参数数据，在**配置元件执行阶段**（优先级最高）最先加载。

#### ① 用户定义的变量（User Defined Variables）
定义全局变量，整个测试计划内所有取样器均可通过 `${变量名}` 引用。

| 字段 | 说明 | 示例 |
| --- | --- | --- |
| Name | 变量名 | `host` |
| Value | 变量值 | `192.168.124.36` |


> 💡 最佳实践：把服务器地址、端口、环境标识都定义在这里，切换测试环境只改这一处。
>

#### ② HTTP 请求默认值（HTTP Request Defaults）
统一设置线程组内所有 HTTP 请求的公共参数，避免每个取样器重复填写。

| 配置项 | 说明 |
| :---: | --- |
| 服务器名称或 IP | 填 `${host}` 引用变量 |
| 端口号 | 如 `8080` |
| 协议 | `http` 或 `https` |
| 编码 | `UTF-8` |


> ⚠️ 取样器中单独填写的值会**覆盖**默认值，可以按需覆盖个别请求。
>

#### ③ CSV 数据文件设置（CSV Data Set Config）
从 CSV 文件中逐行读取数据，实现**参数化测试**（每个线程/每次循环使用不同的数据）。

| 配置项 | 说明 | 示例 |
| --- | --- | --- |
| Filename | CSV 文件路径（建议用相对路径） | `./data/users.csv` |
| Variable Names | 列名，用逗号分隔，对应 CSV 的列顺序 | `username,password` |
| Delimiter | 分隔符 | `,` |
| Recycle on EOF | 文件读完后是否循环读取 | `True`（数据不够时重复使用） |
| Stop thread on EOF | 文件读完后是否停止线程 | `False` |
| Sharing Mode | 数据共享模式 | `All threads`（所有线程共享，按行分配） |


> 💡 CSV 文件示例：
>

```plain
username,password
user01,pass01
user02,pass02
```

#### ④ HTTP Cookie 管理器（HTTP Cookie Manager）
自动管理会话 Cookie，模拟浏览器的 Cookie 行为，让 JMeter 像真实用户一样维持登录状态。

| 配置项 | 说明 |
| --- | --- |
| Cookie Policy | 通常选 `standard`（兼容大多数服务器） |
| Clear cookies each iteration | 每次循环是否清除 Cookie（模拟新用户时勾选） |


> 💡 只要添加了 Cookie 管理器，JMeter 会自动保存服务器返回的 `Set-Cookie`，并在后续请求中自动携带，无需手动处理。
>

#### ⑤ HTTP 信息头管理器（HTTP Header Manager）
统一设置请求头，常用于设置 `Content-Type`、`Authorization` 等固定请求头。

| Name | Value |
| --- | --- |
| `Content-Type` | `application/json;charset=UTF-8` |
| `Authorization` | `Bearer ${token}` |


> ⚠️ 放在**线程组级别** = 作用于所有请求；放在**取样器子级** = 只作用于该请求。
>

#### ⑥ 计数器（Counter）
生成自增整数，常用于需要唯一参数的场景。

| 配置项 | 说明 | 示例 |
| --- | --- | --- |
| Starting value | 起始值 | `1` |
| Increment | 每次增加的步长 | `1` |
| Maximum value | 最大值，到达后重置 | `9999` |
| Reference Name | 引用变量名 | `order_id`，使用时写 `${order_id}` |
| Track counter independently for each user | 每个线程独立计数 | 勾选（避免多线程计数冲突） |


#### ⑦ 随机变量（Random Variable）
生成指定范围内的随机整数，比计数器更简单，无需维护状态。

| 配置项 | 说明 | 示例 |
| --- | --- | --- |
| Variable Name | 变量名 | `random_id` |
| Minimum Value | 随机范围最小值 | `1` |
| Maximum Value | 随机范围最大值 | `9999` |
| Per Thread | 每个线程独立生成 | 勾选 |


#### ⑧ JDBC 连接配置（JDBC Connection Configuration）
配置数据库连接池，供 JDBC 请求取样器使用，实现数据库压测或数据准备。

| 配置项 | 说明 | 示例 |
| --- | --- | --- |
| Variable Name for created pool | 连接池名称（取样器中引用） | `mysql_pool` |
| Database URL | JDBC 连接串 | `jdbc:mysql://localhost:3306/testdb` |
| JDBC Driver class | 驱动类名 | `com.mysql.cj.jdbc.Driver` |
| Username / Password | 数据库账号密码 | — |


> ⚠️ 需要将对应数据库的 JDBC 驱动 jar 包放入 JMeter 的 `lib/` 目录
>

#### ⑨ Keystore 配置（Keystore Configuration）
配置 SSL 双向认证所需的客户端证书（KeyStore 文件），用于需要客户端证书的 HTTPS 接口测试。

| 配置项 | 说明 |
| --- | --- |
| Preload KeyStore | 是否预加载证书 |
| Variable name holding certificate alias | 存储证书别名的变量名，支持参数化 |
| Start index / End index | 多证书场景下的索引范围 |


### 6. 🔧 前置处理器
**作用**：在**取样器执行之前**自动触发，用于修改请求参数、生成动态数据、加密签名等预处理操作。

#### ① 用户参数（User Parameters）
为每个线程分别定义不同的参数值，实现线程级别的参数隔离。

| 配置项 | 说明 |
| --- | --- |
| Name | 参数名 |
| User_1 / User_2 ... | 每个线程对应的参数值 |


> 💡 适合线程数少且参数固定的场景；大量数据参数化建议用 CSV 数据文件设置。
>

#### ② BeanShell 前置处理器 / JSR223 前置处理器
通过 Java/Groovy 脚本在请求前执行自定义逻辑。

> 💡 JSR223（Groovy）性能远优于 BeanShell，**推荐优先使用 JSR223**。
>

常见用途：

```groovy
// 示例：生成当前时间戳并设置为变量
def timestamp = System.currentTimeMillis()
vars.put("timestamp", timestamp.toString())

// 示例：对密码进行 MD5 加密
import java.security.MessageDigest
def md5 = MessageDigest.getInstance("MD5")
def hash = md5.digest("123456".bytes).encodeHex().toString()
vars.put("encrypted_pwd", hash)
```

| 内置对象 | 说明 |
| --- | --- |
| `vars` | 读写 JMeter 变量，`vars.get("key")` / `vars.put("key", "value")` |
| `props` | 读写 JMeter 属性（跨线程组共享） |
| `log` | 写日志，`log.info("msg")` |
| `prev` | 获取上一个取样器的结果（前置处理器中较少用） |


---

#### ③ 正则表达式提取器（前置版）
与后置处理器中的正则提取器相同，但在**取样器执行前**运行，用于从之前请求的结果中提取数据，动态修改即将发出的请求参数。

> 💡 放在前置处理器位置时，`prev` 对象指向的是当前取样器**之前**最近一次执行的取样器结果
>

#### ④ HTML 链接解析器（HTML Link Parser）
自动解析响应体中的 HTML 链接，并将链接地址注入到后续的 HTTP 请求中，模拟浏览器自动跟随链接的行为。

> 💡 场景：测试网页爬取类场景，或需要模拟浏览器自动跳转跟随页面中的动态链接
>

#### ⑤ HTTP URL 重写修饰符（HTTP URL Re-writing Modifier）
当服务器使用 URL 重写（而非 Cookie）来维持 Session 时，自动从响应中提取 Session ID，并附加到后续请求的 URL 中。

| 配置项 | 说明 | 示例 |
| --- | --- | --- |
| Session Argument Name | Session 参数名 | `JSESSIONID` |
| Path Extension | 是否使用路径方式（`;JSESSIONID=xxx`） | 按服务器格式选择 |


### 7. 📥 后置处理器
**作用**：在**取样器执行之后**自动触发，从响应中提取数据保存为变量，供后续请求使用（即**接口关联**）。

#### ① JSON 提取器（JSON Extractor）
从 JSON 格式的响应体中，通过 JSON Path 表达式提取字段值。

| 配置项 | 说明 | 示例 |
| --- | --- | --- |
| Names of created variables | 提取后保存的变量名 | `token` |
| JSON Path expressions | JSON Path 提取路径 | `$.data.token` |
| Match No. | 匹配第几个结果，`-1` = 全部，`0` = 随机，`1` = 第一个 | `1` |
| Default Values | 提取失败时的默认值 | `NOT_FOUND`（便于排查） |


> 💡 JSON Path 语法速查：
>

```plain
$.key          → 根节点下的 key
$.data.token   → 嵌套字段
$.list[0]      → 数组第一个元素
$.list[*].id   → 数组所有元素的 id 字段
```

#### ② 正则表达式提取器（Regular Expression Extractor）
用正则从响应中提取数据，适用于非 JSON 格式（HTML、文本、XML）或需要复杂匹配的场景。

| 配置项 | 说明 | 示例 |
| --- | --- | --- |
| Apply to | 提取范围 | `Main sample only` |
| Field to check | 被提取的字段 | 响应体 / 响应头 / URL |
| Reference Name | 保存的变量名 | `token` |
| Regular Expression | 正则表达式，用 `()` 捕获目标内容 | `"token":"(.+?)"` |
| Template | 引用第几个捕获组 | `$1$`（第一个括号捕获的内容） |
| Match No. | 匹配第几个结果 | `1` |
| Default Value | 提取失败时的默认值 | `NOT_FOUND` |


**最常用的两个符号：**

| 符号 | 含义 | 记忆方式 |
| --- | --- | :---: |
| `.` | 匹配任意**单个**字符 | 一个随便什么 |
| `+` | 匹配前面的内容**一次或多次** | 1或n |
| `*`  | 匹配前面的内容 **零次或多次** | 0或n |
| `?` | 加在 `+` 后面，变成**非贪婪**模式，匹配尽可能少的内容 | 少吃点，到边界就停 |


> `(.+?)` 是提取器里最常用的组合，意思是：**捕获任意内容，碰到右侧边界就停下**
>

****

> **例1：从 JSON 响应中提取 token**
>
> 响应体：`{"code":0,"data":{"token":"xxxxxx","userId":88}}`
>
> 目标：提取` abc123xyz`
>
> 写法：在 token 的值左边找一个固定特征 "token":" 作为左边界，右边的 " 作为停止信号
>
> `"token":"(.+?)"`
>
> 
>
> **例2：从 HTML 页面提取表单隐藏字段**
>
> 响应体（片段）：`<input type="hidden" name="csrf_token" value="x9f2k7p">`
>
> 目标：提取 `x9f2k7p`
>
> 写法：`name="csrf_token" value="(.+?)"`
>
> ****
>
> **例3：提取响应码中的数字**
>
> 响应体：`{"code":200,"msg":"success"}`
>
> 目标：提取 `200`
>
> 写法：`"code":(\d+)`，`\d` 表示数字，`\d+` 表示一个或多个数字，比 `.+?` 更精确
>
> ****
>
> **例4：提取多个值（Match No. = -1）**
>
> 响应体：`{"list":[ {"id":1},{"id":2},{"id":3} ]}`
>
> 目标：提取所有 id
>
> 正则：`"id":(\d+)`，Match No. 填 `-1`
>
> JMeter 会自动生成：`变量名_1=1`、`变量名_2=2`、`变量名_3=3`，以及 `变量名_matchNr=3`（总数量）
>
> 后续可通过 `${userId_1}`、`${userId_2}` 分别引用
>

#### ③ XPath 提取器（XPath Extractor）
从 XML 或 HTML 响应中提取数据，适用于 SOAP 接口或页面内容提取。

| 配置项 | 说明 | 示例 |
| --- | --- | --- |
| XML Parsing Options | 是否使用 Tidy 解析 HTML | HTML 页面需勾选 `Use Tidy` |
| XPath query | XPath 表达式 | `//response/token/text()` |
| Reference Name | 保存的变量名 | `token` |


#### ④ JSR223 后置处理器
通过 Groovy 脚本处理复杂的响应数据，灵活性最高。

```groovy
// 示例：解析响应体 JSON，提取并处理数据
import groovy.json.JsonSlurper
def json = new JsonSlurper().parseText(prev.getResponseDataAsString())
def token = json.data.token
vars.put("token", token)
log.info("提取到 token：" + token)
```

#### ⑤ 边界提取器（Boundary Extractor）
通过指定**左边界**和**右边界**两个字符串，提取夹在中间的内容。比正则表达式更简单直观，无需学习正则语法。

| 配置项 | 说明 | 示例 |
| --- | --- | --- |
| Names of created variables | 保存的变量名 | `token` |
| Left Boundary | 目标内容左侧的固定字符串 | `"token":"` |
| Right Boundary | 目标内容右侧的固定字符串 | `"` |
| Match No. | 匹配第几个，`-1` = 全部，`1` = 第一个 | `1` |
| Default Values | 提取失败时的默认值 | `NOT_FOUND` |


> 💡 场景：响应体中有 `"token":"abc123"`，左边界填 `"token":"` 右边界填 `"` 即可提取到 `abc123`，比正则更易读
>

#### ⑥ 结果状态处理器（Result Status Action Handler）
当取样器返回失败结果时，控制后续的处理行为（而不是默认继续执行）。

| 配置项 | 说明 |
| --- | --- |
| Action to be taken after a Sampler error | 失败后的动作：继续 / 停止线程 / 停止测试 / 停止测试（立即） |


> 💡 场景：登录失败后立即停止当前线程，避免后续请求因缺少 Token 而产生大量无效错误
>

---

## 四、📐 常用函数
函数是 JMeter 内置的工具方法，可以在任意输入框中通过 `${__函数名(...)}` 的方式调用，用于动态生成参数值。

> 打开方式：菜单栏 `选项 → 函数助手对话框`（快捷键 Ctrl+Shift+F1），可以可视化生成函数表达式，不用手写。
>

### 4.1 时间相关

#### ① __time（时间戳）

| 格式 | 表达式 | 输出示例 |
| --- | --- | --- |
| 当前毫秒时间戳 | `${__time(,)}` | `1779541089610` |
| 指定格式时间 | `${__time(yyyy-MM-dd HH:mm:ss,)}` | `2026-05-23 14:30:00` |
| 存入变量 | `${__time(,ts)}` | 将时间戳存为 `${ts}`，后续可复用 |

> 常用格式：`yyyy-MM-dd`（日期）、`yyyyMMddHHmmss`（紧凑格式）、`HH:mm:ss`（时间）
>

#### ② __timeShift（时间偏移）
对当前时间做加减，生成过去或未来的时间。

| 表达式 | 含义 |
| --- | --- |
| `${__timeShift(yyyy-MM-dd,,P1D,,)}` | 明天（+1天） |
| `${__timeShift(yyyy-MM-dd,,P-7D,,)}` | 7天前 |
| `${__timeShift(,,PT1H,,)}` | 1小时后的时间戳 |

> P1D = +1天，P-1M = -1月，PT30M = +30分钟，PT2H = +2小时
>

### 4.2 数据生成

#### ③ __Random（随机数）

| 表达式 | 含义 |
| --- | --- |
| `${__Random(1,100,)}` | 生成 1~100 的随机整数 |
| `${__Random(1000,9999,orderId)}` | 生成4位随机数并存入 `${orderId}` |

#### ④ __UUID（唯一标识）

| 表达式 | 输出示例 |
| --- | --- |
| `${__UUID()}` | `3b241101-e2bb-4255-8c36-26c2b2c60b36` |

> 用于生成唯一请求ID、订单号等

#### ⑤ __counter（计数器）

| 表达式 | 含义 |
| --- | --- |
| `${__counter(FALSE,)}` | 全局递增（所有线程共享） |
| `${__counter(TRUE,cnt)}` | 每个线程独立计数，结果存为 `${cnt}` |

> 与配置元件"计数器"功能相同，但函数方式更轻量，直接在输入框中引用即可

#### ⑥ __RandomString（随机字符串）

| 表达式 | 含义 |
| --- | --- |
| `${__RandomString(8,abcdef1234567890,)}` | 生成 8 位随机字符串（字符集可选） |
| `${__RandomString(6,,)}` | 生成 6 位随机字符串（默认字符集） |

### 4.3 加密与编码

#### ⑦ __digest（哈希加密）

| 表达式 | 含义 |
| --- | --- |
| `${__digest(MD5,123456,,,)}` | 对 `123456` 做 MD5 |
| `${__digest(SHA-256,${password},,,)}` | 对变量 `${password}` 做 SHA-256 |
| `${__digest(MD5,${password}${ts},,,)}` | 密码 + 时间戳拼接后做 MD5（常见加盐方式） |

> 标准格式：`${__digest(算法, 内容, 字符集, 盐值, 变量名)}`，算法支持 MD5 / SHA-1 / SHA-256 / SHA-512

#### ⑧ __urlencode / __urldecode（URL 编解码）

| 表达式 | 含义 |
| --- | --- |
| `${__urlencode(华为)}` | URL 编码 → `%E5%8D%8E%E4%B8%BA` |
| `${__urldecode(%E5%8D%8E%E4%B8%BA)}` | URL 解码 → `华为` |

> 处理中文搜索关键词、特殊字符参数时必用

### 4.4 系统与环境

#### ⑨ __P（读取属性）

| 表达式 | 含义 |
| --- | --- |
| `${__P(host,localhost)}` | 读取属性 `host`，不存在则用默认值 `localhost` |
| `${__P(threads,100)}` | 常用在命令行 `jmeter -Jthreads=200` 动态传入并发数 |

> 用于命令行动态传参，实现同一脚本不同压测强度

#### ⑩ __threadNum / __machineIP

| 表达式 | 输出示例 | 用途 |
| --- | --- | --- |
| `${__threadNum}` | `1` `2` `3`... | 当前线程编号，用于制造"不同用户"的假象 |
| `${__machineIP()}` | `192.168.1.5` | 获取本机IP，多机压测时区分来源 |
| `${__machineName()}` | `DESKTOP-ABC` | 获取本机主机名 |

### 4.5 变量嵌套

#### ⑪ __V（嵌套变量引用）

| 场景 | 表达式 | 说明 |
| --- | --- | --- |
| 引用 `变量名_${n}` | `${__V(id_${n})}` | 比如 `${n}=2` 时，实际引用 `${id_2}` |

> 常用于配合 ForEach 控制器或循环中动态引用变量数组

---

## 五、⚠️ 元件执行顺序（核心重点）
### 标准执行顺序
1️⃣ 配置元件

2️⃣ 前置处理器

3️⃣ 定时器

4️⃣ 取样器  ← 核心请求

5️⃣ 后置处理器

6️⃣ 断言

7️⃣ 监听器

> 📌 同类型元件按**从上到下**顺序执行  
📌 逻辑控制器只控制"哪些取样器执行"，**不改变元件类型的优先级**
>

### 🚫 常见误区
| 误区 | ❌ 错误认知 | ✅ 正确理解 |
| --- | --- | --- |
| **误区1** | 把定时器放在取样器后面就不生效了 | 定时器优先级高于取样器，无论放哪里都在**取样器执行前**生效。想只对某个取样器生效，就放在该取样器的**子层级** |
| **误区2** | 后置处理器放在取样器前面就提取不到数据 | 后置处理器优先级低于取样器，哪怕拖到前面，也会**等取样器执行完**再运行 |
| **误区3** | 逻辑控制器会改变元件的执行优先级 | 逻辑控制器只决定"哪些取样器执行、执行几次"，**不改变7类元件的固定优先级** |


### 🔬 作用域执行案例
```plain
线程组
├── 父级后置处理器
├── 简单控制器
│   ├── 子级前置处理器1
│   └── HTTP请求1
│       └── 子级后置处理器1
├── 同级前置处理器1
└── HTTP请求2
```

#### 处理 HTTP请求1（简单控制器内）
| 顺序 | 执行内容 | 原因 |
| :---: | --- | --- |
| 1 | 同级前置处理器1 | 作用域覆盖"同级后的所有取样器" |
| 2 | 子级前置处理器1 | 深度优先，先处理父节点下的前置处理器 |
| 3 | 父级后置处理器 | 取样器执行完后，立即执行父级范围内的后置处理器 |
| 4 | 子级后置处理器1 | 再执行当前取样器专属的后置处理器 |


#### 处理 HTTP请求2（同级）
| 顺序 | 执行内容 | 原因 |
| :---: | --- | --- |
| 5 | 同级前置处理器1 | 第二个取样器开始，重复执行作用域覆盖它的前置处理器 |
| 6 | 父级后置处理器 | 再次触发（对范围内所有取样器均生效） |


## 六、🏗️ 实战脚本结构示例
> 场景：100用户并发登录 → 查询商品 → 下单
>

```plain
测试计划
├── ⚙️ 用户定义的变量（全局：服务器地址、端口）
├── 👥 线程组（100线程 / 10s启动 / 循环10次）
│   ├── ⚙️ CSV 数据文件设置（参数化：账号密码）
│   ├── ⚙️ HTTP 请求默认值（统一配置请求）
│   ├── 🔧 前置处理器（加密登录密码）
│   ├── ⚡ 登录接口（HTTP请求取样器）
│   │   ├── 📥 JSON 提取器（提取登录 token）
│   │   └── ✅ 响应断言（校验登录成功）
│   ├── ⏱️ 固定定时器（思考时间：3秒）
│   ├── 🔀 事务控制器（封装下单业务流程）
│   │   ├── ⚡ 查询商品接口
│   │   ├── ⚡ 下单接口（携带 token）
│   │   └── ✅ 响应断言（校验下单成功）
│   └── ⏱️ 同步定时器（集合点：100用户同时下单）
├── 📊 聚合报告（压测结果汇总）
└── 📊 察看结果树（脚本调试）
```

## 🔌 实战：接口测试全流程
> 场景：登录->查询
>

### Step 1：接口分析请求
&emsp;&emsp;在正式写 JMeter 脚本之前，先用F12抓包（或者查看接口文档），搞清楚每个接口的请求结构。

<img src="https://cdn.nlark.com/yuque/0/2026/png/36048946/1779538157847-6eb699de-7535-47e6-8118-61951cf56244.png" width="700" />

```plain
curl.exe ^"http://localhost:8080/api/user/login^" ^
  -X POST ^
  -H ^"User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:149.0) Gecko/20100101 Firefox/149.0^" ^
  -H ^"Accept: */*^" ^
  -H ^"Accept-Language: zh-CN,zh;q=0.9,zh-TW;q=0.8,zh-HK;q=0.7,en-US;q=0.6,en;q=0.5^" ^
  -H ^"Accept-Encoding: gzip, deflate, br, zstd^" ^
  -H ^"Referer: http://localhost:8080/login.html^" ^
  -H ^"Content-Type: application/json^" ^
  -H ^"ts: 1779541089610^" ^
  -H ^"Origin: http://localhost:8080^" ^
  -H ^"Connection: keep-alive^" ^
  -H ^"Sec-Fetch-Dest: empty^" ^
  -H ^"Sec-Fetch-Mode: cors^" ^
  -H ^"Sec-Fetch-Site: same-origin^" ^
  -H ^"Priority: u=0^" ^
  -H ^"Pragma: no-cache^" ^
  -H ^"Cache-Control: no-cache^" ^
  --data-raw ^"^{^\^"username^\^":^\^"test001^\^",^\^"password^\^":^\^"748890faa50f2d0820b066294b7fb46f^\^"^}^"
```

<img src="https://cdn.nlark.com/yuque/0/2026/png/36048946/1779538201083-f8c87dda-baf8-4058-b718-e91df395136e.png" width="700" />

```plain
curl.exe ^"http://localhost:8080/api/goods/list?keyword=^%^E5^%^8D^%^8E^%^E4^%^B8^%^BA^" ^
  -H ^"User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:149.0) Gecko/20100101 Firefox/149.0^" ^
  -H ^"Accept: */*^" ^
  -H ^"Accept-Language: zh-CN,zh;q=0.9,zh-TW;q=0.8,zh-HK;q=0.7,en-US;q=0.6,en;q=0.5^" ^
  -H ^"Accept-Encoding: gzip, deflate, br, zstd^" ^
  -H ^"Referer: http://localhost:8080/index.html^" ^
  -H ^"token: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIyIiwidXNlcm5hbWUiOiJ0ZXN0MDAxIiwicm9sZSI6MCwiaWF0IjoxNzc5NTQxMDg5LCJleHAiOjE3Nzk1NDQ2ODl9._k6JIcPNxwUThGzB8Upj2bv0yI8j2i8o_HnaNH5yVvY^" ^
  -H ^"Connection: keep-alive^" ^
  -H ^"Sec-Fetch-Dest: empty^" ^
  -H ^"Sec-Fetch-Mode: cors^" ^
  -H ^"Sec-Fetch-Site: same-origin^" ^
  -H ^"Priority: u=0^" ^
  -H ^"Pragma: no-cache^" ^
  -H ^"Cache-Control: no-cache^"
```

<img src="https://cdn.nlark.com/yuque/0/2026/png/36048946/1779538222984-dba090b3-e9be-4fcc-abd8-37d4b0636b01.png" width="700" />

```plain
curl.exe ^"http://localhost:8080/api/order/buy^" ^
  -X POST ^
  -H ^"User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:149.0) Gecko/20100101 Firefox/149.0^" ^
  -H ^"Accept: */*^" ^
  -H ^"Accept-Language: zh-CN,zh;q=0.9,zh-TW;q=0.8,zh-HK;q=0.7,en-US;q=0.6,en;q=0.5^" ^
  -H ^"Accept-Encoding: gzip, deflate, br, zstd^" ^
  -H ^"Referer: http://localhost:8080/index.html^" ^
  -H ^"Content-Type: application/json^" ^
  -H ^"token: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIyIiwidXNlcm5hbWUiOiJ0ZXN0MDAxIiwicm9sZSI6MCwiaWF0IjoxNzc5NTQxMDg5LCJleHAiOjE3Nzk1NDQ2ODl9._k6JIcPNxwUThGzB8Upj2bv0yI8j2i8o_HnaNH5yVvY^" ^
  -H ^"Origin: http://localhost:8080^" ^
  -H ^"Connection: keep-alive^" ^
  -H ^"Sec-Fetch-Dest: empty^" ^
  -H ^"Sec-Fetch-Mode: cors^" ^
  -H ^"Sec-Fetch-Site: same-origin^" ^
  -H ^"Priority: u=0^" ^
  -H ^"Pragma: no-cache^" ^
  -H ^"Cache-Control: no-cache^" ^
  --data-raw ^"^{^\^"goodsId^\^":2^}^"
```

### Step 2：在 JMeter 中添加 HTTP 请求
&emsp;&emsp;按照接口文档，在线程组下添加 HTTP 请求取样器，将接口信息填入对应字段。（先放固定值看看接口能不能请求成功）

<img src="https://cdn.nlark.com/yuque/0/2026/png/36048946/1779542013172-b68d8b1f-4a30-415a-a0ae-578b7d2bb819.png" width="700" />

<img src="https://cdn.nlark.com/yuque/0/2026/png/36048946/1779542050749-1941e12f-8f56-49b3-9114-f5c5593abc50.png" width="700" />

<img src="https://cdn.nlark.com/yuque/0/2026/png/36048946/1779542319115-efc55e4a-85c4-4ef1-9c35-f6cc69732136.png" width="700" />

<img src="https://cdn.nlark.com/yuque/0/2026/png/36048946/1779542343292-c08014be-d947-4cf6-b8e7-c9425a74cab3.png" width="700" />



> 💡 **监听器放置技巧**：  
>
> + 放在**线程组同级** → 能看到所有请求的结果
> + 放在**某个取样器子级** → 只能看到该取样器的结果
>

<img src="https://cdn.nlark.com/yuque/0/2026/png/36048946/1779542440717-4c9f27ac-d0be-428a-a5c9-733b22c6a688.png" width="700" />

<img src="https://cdn.nlark.com/yuque/0/2026/png/36048946/1779542498588-61dfb746-d36b-4549-8733-274dfd9ff2d2.png" width="700" />

### Step 3：配置登录接口（时间戳与密码加密）
&emsp;&emsp;由于登录的时候使用了时间戳和加密手段，所以需要再加一点东西，输入 `${__time(,ts)}`，而不是直接使用 `${__time(,)}`，是因为要保留时间戳给后面加密的时候用。

<img src="https://cdn.nlark.com/yuque/0/2026/png/36048946/1779544197478-92f90cbd-e0dc-4b7e-b834-4ae0a79df6f1.png" width="700" />

<img src="https://cdn.nlark.com/yuque/0/2026/png/36048946/1779544950109-47e28337-726e-483e-8302-fa1292bee370.png" width="700" />

> **${__digest}** 是 JMeter 自带的加密函数，不用导包，直接用来做 MD5、SHA-1、SHA-256 等哈希计算。标准格式：`${__digest(算法, 要加密的内容, 字符集, 盐值, 变量名)}`，详细用法见「常用函数」章节。

&emsp;&emsp;运行一下就可以看到，登录接口成功响应了

<img src="https://cdn.nlark.com/yuque/0/2026/png/36048946/1779545018818-41f1047e-0055-4bb4-b313-a135c14e28ab.png" width="700" />

### Step 4：提取Token在新增接口中使用
**① 使用 JSON 提取器提取 Token**

&emsp;&emsp;这里可以用很多方法，比如左右边界、json、正则，都可以，这个章节是基础篇，那么就使用比较常规的方法来获取，这里分析一下，返回的是json，那么当然是json提取器更好一点

<img src="https://cdn.nlark.com/yuque/0/2026/png/36048946/1779545492358-9d7c8340-c65e-4174-9d3d-759d14cfc042.png" width="700" />

**② 验证 Token 是否提取成功**

&emsp;&emsp;添加一个 Debug 取样器（或 BeanShell 取样器打印变量），运行后在察看结果树中确认 `${token}` 已有值。

<img src="https://cdn.nlark.com/yuque/0/2026/png/36048946/1779545719315-c517be86-3c0b-44b2-9257-4b9a585d031a.png" alt="新建 Debug PostProcessor" width="700" />

<img src="https://cdn.nlark.com/yuque/0/2026/png/36048946/1779545759844-e9b0ce68-1b8c-46c5-bb1b-a131532a1e5d.png" alt="成功在查看结果树中看到了Token值" width="700" />

&emsp;&emsp;在查询接口的请求头中，将 token 字段的值改为 `${Token}`，JMeter 运行时会自动替换为提取到的实际值。

<img src="https://cdn.nlark.com/yuque/0/2026/png/36048946/1779545878750-cb71a442-05f7-4a05-84fa-2ea830c71d8e.png" alt="在查询接口管理器中使用 ${token}" width="700" />

<img src="https://cdn.nlark.com/yuque/0/2026/png/36048946/1779546206951-dfc61e21-ff9e-4013-9d57-341e72fc990e.png" alt="商品查询成功" width="700" />

### Step 5：添加检查点（断言）

&emsp;&emsp;前面已经确认接口能正常请求和返回数据了，但每次都要手动去察看结果树里看响应内容太麻烦了。**断言（检查点）** 可以让 JMeter 自动校验响应是否符合预期——断言失败则请求标红，计入错误率。

**① 给登录接口添加响应断言**

&emsp;&emsp;右键点击登录接口取样器 → 添加 → 断言 → 响应断言。

| 配置项 | 值 | 说明 |
| --- | --- | --- |
| Apply to | `Main sample only` | 只对主请求生效 |
| Field to Test | `响应代码` | 校验 HTTP 状态码 |
| Pattern Matching Rules | `相等` | 完全匹配 |
| Patterns to Test | `200` | 期望返回 200 |

&emsp;&emsp;如果要校验业务状态码（推荐），将 Field to Test 改为 `响应体`，Patterns to Test 填入 `"code":0`，这样能同时覆盖 HTTP 状态码和业务返回码的校验。

**② 给查询接口添加 JSON 断言**

&emsp;&emsp;对于 JSON 格式的响应，用 **JSON 断言** 比响应断言更精准。右键点击查询接口取样器 → 添加 → 断言 → JSON 断言。

| 配置项 | 值 | 说明 |
| --- | --- | --- |
| Assert JSON Path exists | `$.code` | 校验根节点下 code 字段存在 |
| Additionally assert value | ✅ 勾选 | 同时校验字段的具体值 |
| Expected Value | `0` | 期望 code = 0 |

&emsp;&emsp;再次运行脚本，断言通过则请求保持绿色，断言失败（比如返回了错误的 code）则请求变红，在察看结果树中可以直观看到。

> 💡 **断言的作用**：没有断言时，只要服务器返回了响应（哪怕返回的是错误信息），JMeter 都算请求成功。加上断言后，只有响应内容符合预期才算通过。正式压测时务必添加断言，否则聚合报告中的错误率指标没有参考意义。

### Step 6：并发查询
&emsp;&emsp;前面已经跑通了单次请求，接下来配置线程组实现多用户并发访问。

#### 6.1 线程组配置

| 配置项 | 值 | 说明 |
| --- | --- | --- |
| 线程数 | `200` | 模拟 200 个并发用户 |
| Ramp-Up 时间（秒） | `10` | 10秒内逐步启动完 200 个线程（每秒启动 20 个），避免瞬间冲击 |
| 循环次数 | `10` | 每个线程执行 10 轮（总请求量 = 200 × 10 = 2000 次） |

> 💡 **Ramp-Up 时间的选择**：值太小 → 瞬间压力过大可能把服务器打崩；值太大 → 前期并发量不足，压测结果不准确。一般100线程以内填 5~10 秒即可。
>

#### 6.2 添加监听器观察结果
正式压测前，建议同时添加以下监听器：

| 监听器 | 用途 |
| --- | --- |
| **察看结果树** | 调试阶段保留，查看每条请求的详细信息，确认请求/响应正确 |
| **聚合报告** | 压测核心报告，**正式压测时保留这一个即可**（察看结果树消耗大量资源应禁用） |
| **汇总报告** | 比聚合报告更精简，快速查看整体吞吐量和平均响应时间 |

> ⚠️ **重要**：正式压测时务必**禁用或删除察看结果树**，否则会严重拖慢 JMeter 自身性能，导致压测数据失真。
>

#### 6.3 添加集合点（同步定时器）

&emsp;&emsp;**集合点**的作用是让所有线程在指定位置等待，凑够一定数量后同时放行，制造瞬间并发冲击——这是模拟"秒杀""抢购"等高并发场景的关键操作。

&emsp;&emsp;右键点击线程组 → 添加 → 定时器 → Synchronizing Timer（同步定时器），将其拖到查询接口取样器的**同级上方**。

| 配置项 | 值 | 说明 |
| --- | --- | --- |
| Number of Simultaneous Users to Group by | `100` | 凑够 100 个线程后同时释放 |
| Timeout in milliseconds | `5000` | 超时 5 秒还没凑够也强制放行，避免死等 |

&emsp;&emsp;放置位置说明：同步定时器放在线程组层级下时，**对该组内所有取样器生效**。如果只想对某个请求做集合点，就把它拖到该取样器的子层级。

> 💡 **集合点工作原理**：100 个线程各自执行到集合点时被阻塞，等第 100 个到达后全部瞬间释放，同时发出请求，形成峰值并发。
>
> ⚠️ **注意**：集合点数量不要超过线程总数，否则永远凑不够会一直等到超时。建议集合点数 ≤ 线程数 × 0.8，留一些余量。

#### 6.4 执行压测
点击工具栏的 **绿色启动按钮** ▶ 开始压测。右上角可实时看到 **活跃线程数** 和 **已完成请求数**。

运行过程中注意观察：
- 是否有大量红色报错（请求失败）
- 右上角的计数器是否正常递增
- 如果错误率突然飙升，立即点击 **停止按钮** ■ 终止压测，排查问题后再重新执行

> 💡 **命令行执行（推荐正式压测时使用）**：GUI 模式本身消耗内存，高并发场景下建议用命令行执行 `.jmx` 脚本并导出报告：
>
> ```plain
> jmeter -n -t test_plan.jmx -l result.jtl -e -o ./report
> ```
>
> | 参数 | 说明 |
> | --- | --- |
> | `-n` | 非 GUI 模式 |
> | `-t` | 指定 JMX 脚本文件 |
> | `-l` | 结果输出文件（.jtl） |
> | `-e -o` | 生成 HTML 报告到指定目录 |
>

### Step 7：阅读结果
压测结束后，重点看**聚合报告**中的以下核心指标。

#### 7.1 聚合报告关键指标解读

| 指标 | 含义 | 判断标准 |
| --- | --- | --- |
| **Samples** | 总请求次数 | 确认是否达到预期的请求量 |
| **Average** | 平均响应时间（ms） | 越低越好，一般接口要求 < 500ms |
| **Median** | 中位数响应时间 | 50% 用户的体验上限，比 Average 更能反映"大多数人"的感受 |
| **90% Line** | 90% 请求的响应时间上限 | **核心指标**，表示 90% 用户的等待时间不超过此值 |
| **95% Line** | 95% 请求的响应时间上限 | 更严格的服务质量指标 |
| **99% Line** | 99% 请求的响应时间上限 | 反映长尾问题，如果和 95% Line 差距大说明存在少量极慢请求 |
| **Min / Max** | 最小 / 最大响应时间 | Max 异常大说明存在偶发性慢请求，需排查 |
| **Error%** | 错误率 | 生产环境要求 < 0.1%，压测中超过 1% 需要停下来排查原因 |
| **Throughput** | 吞吐量（TPS / QPS） | 衡量系统的处理能力上限，值越高越好 |
| **Received KB/sec** | 每秒接收数据量 | 结合带宽评估，瓶颈可能在网络 |

#### 7.2 结果分析套路

**① 先看 Error%**
如果错误率不为 0%，优先排查错误原因：
- 打开察看结果树，查看红色标记的失败请求
- 查看响应体中的错误信息（是超时？业务逻辑报错？还是参数错误？）
- 常见原因：Token 过期、参数未正确关联、服务器连接数耗尽

**② 再看响应时间分布**

```plain
90% Line ≈ 95% Line ≈ Average  → 性能稳定，响应时间分布均匀
90% Line << 99% Line            → 有长尾请求，少量请求特别慢
Average << Median               → 少数极快请求拉低了平均值
Max >> 99% Line                 → 存在偶发性严重卡顿，通常和 GC 停顿、网络抖动有关
```

**③ 看吞吐量趋势**
- 逐渐增加线程数，观察 Throughput 是否线性增长
- **吞吐量到达瓶颈点**：增加线程数但 TPS 不再增长 → 找到了系统的最大处理能力
- 瓶颈点之后响应时间会急剧上升，这个拐点就是系统的性能极限

**④ 看服务器资源**
压测时同时观察服务器 CPU、内存、网络 IO：
- CPU 先到 100% → CPU 密集型瓶颈（代码计算、加解密等）
- 内存持续上涨不释放 → 可能存在内存泄漏
- 网络 IO 先打满 → 带宽瓶颈或传输数据量过大
- 数据库连接池满 → 查看数据库慢查询和连接数

> 💡 **常见性能拐点示意图**：
>
> ```plain
> TPS │
>     │      ╭────── 拐点（性能极限）
>     │     ╱
>     │    ╱
>     │   ╱
>     │  ╱
>     │ ╱
>     └──────────────────→ 并发数
>
> 响应时间 │
>          │         ╱
>          │       ╱
>          │     ╱
>          │   ╱
>          │ ╱
>          └──────────────────→ 并发数
> ```
>
> 拐点之前：增加并发，TPS 线性增长，响应时间稳定  
> 拐点之后：增加并发，TPS 不再增长甚至下降，响应时间急剧恶化

#### 7.3 输出压测结论
每次压测完成后，整理一份简洁的结论：

```plain
【压测结论模板】
- 测试场景：100并发登录 → 查询商品 → 下单
- 持续时间：10分钟
- 总请求数：50000
- 平均响应时间：235ms
- 99% Line：890ms
- 最大 TPS：420
- 错误率：0%
- 结论：系统在当前配置下可稳定支撑 400 TPS，建议生产环境按 70% 容量（280 TPS）设置限流阈值
```

#### 7.4 HTML 报告（Dashboard Report）
命令行加 `-e -o` 参数可生成一份精美的 HTML 可视化报告，比聚合报告更直观。

```plain
jmeter -n -t test.jmx -l result.jtl -e -o ./report
```

报告包含以下核心页面：

| 页面 | 内容 |
| --- | --- |
| **APDEX** | 应用性能指数（0~1），基于可容忍阈值评分，> 0.9 = 优秀 |
| **Requests Summary** | 请求摘要饼图（成功/失败比例） |
| **Statistics** | 与聚合报告相同的数据表格，多了「TPS / 响应时间」折线图 |
| **Response Times** | 响应时间随时间变化曲线，能看出压测过程中是否出现性能衰退 |
| **Response Time Percentiles** | 各分位数的柱状图对比 |
| **Over Time** | TPS、响应时间、线程数、带宽等指标随时间变化的叠加图 |
| **Top 5 Errors** | Top5 错误类型统计 |

> 💡 HTML 报告最适合发给领导/客户看，聚合报告更适合自己分析

