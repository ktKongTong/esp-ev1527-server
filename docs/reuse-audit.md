# 复用审计记录

> 历史说明（2026-11-07）：本审计记录的是已移除的 Ktor/Kotlin Native 服务端。
> 该服务端已于 2026-11-07 删除；当前服务端为 `go-server`（Go + Echo + GORM +
> Turso Cloud）。下文所有 `server/...` 行仅作历史保留，新的迁移选型见第 7、9 节。

审计日期：2026-11-03。范围：仓库内所有运行时实现与依赖。
本地文件：`docs/reuse-audit.md`；本记录涵盖 contract v1.2.0 迁移与
2026-11-07 的 Go/Turso 迁移。
判定顺序遵循仓库根目录 `AGENTS.md` 第 1 节：项目内实现 → 已有依赖 →
标准库/运行时 → 成熟第三方库。

## 1. 结论总览

| 现有实现 | 能力 | 判定 | 处理 |
| --- | --- | --- | --- |
| `server/.../ControlPlane.kt` | 设备/命令/学习状态机、码归属 | 产品领域逻辑；单对象职责过载 | 删除 God object；状态机原样拆到 `DeviceService` / `CommandService` / `LearningService` / `GatewayIngest` |
| `server/.../MqttClient.kt` | 手写 MQTT 3.1.1 连接、订阅、发布 | 成熟库已覆盖 | 删除；改用 ktor-mqtt 1.2.2 薄适配层 `mqtt/MqttTransport.kt` |
| `server/.../Main.kt` SSE 路由 | SSE 分帧、心跳、重放 | 需求已移除 | 删除 SSE 插件、`/events` 路由与事件 DTO |
| `server/.../SseHub.kt` | 事件扇出、256 条重放、`Last-Event-ID` | 需求已移除 | 删除 |
| `server/.../ControlPlane.kt` 的抓码解析 | 网关 RF payload 解码 | `ContractJson` + DTO 已覆盖 | 保留在 `mqtt/GatewayIngest.kt`，不手写 JSON |
| 服务端内存状态 | 设备/命令/幂等持久化；学习会话为运行时状态 | 需求要求 SQLite + Room3；学习会话短生命周期，重启即失效 | 新增 `db/`（entity/DAO/Room3）与 `data/`（仓储/映射）；学习会话留在 `LearningService` 内存中，不引入第二套 ORM |
| `server/.../Main.kt` `RequestContextPlugin` | 请求 ID、`Cache-Control` | 应用策略；无等价内置组合 | 保留（约 12 行），见第 4 节 |
| `server/.../ServerConfig.kt` | 环境变量读取 | 平台 API | 保留 POSIX `getenv` |
| `server/.../Util.kt` `nowEpochMs` | 当前时间 | 标准库 | 使用 `kotlin.time.Clock` |
| `server/.../Util.kt` `newId` | 随机 ID | 标准库 | 使用 `kotlin.random.Random` |
| `shared/.../api/Esp32Api.kt` | HTTP/JSON 客户端 | Ktor + `ContractJson` | 保留适配层 |
| `shared/.../api/Esp32Api.kt` `newIdempotencyKey` | 客户端幂等键 | 标准库 | 保留；与服务端 ID 命名空间不同 |
| `shared/.../App.kt` 轮询 | 状态刷新 | 应用策略 | 保留；v1.2.0 的唯一同步机制 |
| `shared/...` Compose UI | Material 3 界面 | Compose Multiplatform | 保留 |
| `contract/...` | DTO、错误信封、JSON 配置 | kotlinx.serialization | 保留，服务端与客户端唯一协议来源 |
| `mock/` | OpenMQTTGateway 仿真 | Go 标准库 + paho.mqtt.golang | 保留 |
| 错误映射 | HTTP 错误信封 | Ktor StatusPages | 保留 |
| CORS | 跨域预检与响应头 | Ktor CORS | 保留 |

## 2. MQTT 库选择

### 2.1 采用：ktor-mqtt 1.2.2

- 坐标：`dev.koft.ktor-mqtt:mqtt-client:1.2.2` +
  `dev.koft.ktor-mqtt:mqtt-core:1.2.2`
- 来源：`https://github.com/ktKongTong/ktor-mqtt/` 的 `v1.2.2` 构建，
  发布到 GitHub Packages。
- 许可证：Apache-2.0（POM 中声明）。
- 目标：`linuxArm64` + `linuxX64` + `macosArm64`。Linux 与 macOS
  变体都由 fork 发布。
- 依赖声明：放在 `nativeMain`。`nativeMain` 源码直接导入 ktor-mqtt 类型；
  声明在叶子 source set 时编译器可解析，但共享源集与 IDE 分析看不到。
- 覆盖：MQTT 5 连接、订阅、QoS 1 发布、keepalive、`publishedPackets` 流、
  `connectionState` 流。
- 复用方式：`mqtt/MqttTransport.kt` 只保留库未提供的行为：
  - 断线后的有界指数退避重连（ktor-mqtt 不自动重连）；
  - 连接/订阅失败隔离，避免服务端进程退出；
  - 主题过滤与 `ByteArray` ↔ `ByteString` 边界转换；
  - 连接/断开回调桥接到 `GatewayIngest`。

适配层不复制 MQTT 帧编码、CONNECT/SUBSCRIBE/PUBLISH 状态机或 QoS 确认逻辑。
MQTT 是服务端必需配置：未设置 `MQTT_HOST` 时启动失败，不保留无网关的
`UnavailableRfTransmitter` 之类的降级实现。

TLS 走 MQTT over WebSocket Secure（`MQTT_TRANSPORT=websocket` +
`MQTT_TLS=true`）。Ktor Native 的 `ktor-network-tls` 至今没有裸 TLS 实现
（`openTLSSession` 直接 `error`），而 Ktor Curl 引擎的 klib 自带静态
libcurl + OpenSSL，并支持 WebSocket 与 TLS，Linux/macOS 目标均可链接。
fork 未发布 `mqtt-client-ws`，因此服务端保留一个薄 WebSocket 传输层，
见第 4 节。

### 2.2 未采用

| 库 | 原因 |
| --- | --- |
| KMQTT 1.0.0 | 只支持 MQTT 3.1.1；本版要求改用 ktor-mqtt。作为历史实现已删除，不保留平行适配层 |
| `com.ditchoom:mqtt-client:2.1.1` | Linux 实现依赖 io_uring；Docker 默认 seccomp 下初始化失败：`Failed to initialize io_uring: Operation not permitted` |
| `org.meshtastic:mqtt-client:0.3.8` | GPL-3.0，与仓库分发约束不匹配 |
| 上游 `de.kempmobil.ktor-mqtt` 1.2.2 | 不发布 `linuxArm64` 原生目标；fork 已同时发布 Linux 与 macOS 变体，无需再混用上游坐标 |

## 3. 持久化选型

`docs/server.md` 明确要求 SQLite + Room3，因此不另做 ORM 对比选型。

- 驱动：`androidx.sqlite:sqlite-bundled:2.7.1`，`BundledSQLiteDriver`
  自带 SQLite，不依赖宿主机的 `libsqlite3`。
- ORM：`androidx.room3:room3-runtime:3.0.3` + KSP 生成 DAO；
  schema 导出到 `server/schemas/`。
- 数据库访问只通过 `data/` 仓储；领域服务不直接拼 SQL。
- 事务使用 Room 的 `withWriteTransaction`；MQTT 发布放在事务外。
- 未引入 SQLDelight/Exposed：与指定技术栈冲突，且会造成第二套数据访问层。

## 4. 保留的自定义代码

### `RequestContextPlugin`

Ktor 的 `CallId` 只解决请求 ID；本插件还需要：

- 接受并校验 1–128 字符的入站 `X-Request-Id`；
- 对所有 API 响应设置 `Cache-Control: no-store`；
- 在错误信封中复用同一个请求 ID。

为这三点引入 `ktor-server-call-id` 仍需保留自定义缓存策略，收益不足。
插件只做头部策略，不实现 HTTP 协议能力。

### `MqttTransport` 重连监督

ktor-mqtt 暴露连接状态和收发原语，但不自动重连。重连策略是部署需求
（Broker 重启时进程必须存活），不是协议实现，因此保留在薄适配层。

### `WebSocketMqttEngine`

```
Reused: ktor-mqtt `mqtt-client` packet codec (`ByteReadChannel.readPacket`,
        `Sink.write`) + Ktor Curl engine (`ktor-client-curl` 3.6.1)
Not reused because: ktor-mqtt's `mqtt-client-ws` is not published for
        linuxArm64/linuxX64/macosArm64, and Ktor Native has no raw TLS
New implementation scope: WebSocket frame loop adapted from the Apache-2.0
        `WebSocketEngine` in ktor-mqtt's `mqtt-client-ws`
```

它不解析或生成 MQTT 报文，只负责 WebSocket 连接、二进制帧与字节流转换。
上游 `WebSocketEngine` 是 `internal` 且没有对应制品，无法直接复用。

### `newId` / `newIdempotencyKey`

两处都是标准库 `Random` 的几行封装，格式与命名空间不同：

- 服务端实体 ID：`prefix_` + 24 位大写十六进制；
- 客户端幂等键：36 位小写十六进制（契约允许客户端自定义）。

不为单函数能力引入 UUID 依赖或新增公共抽象。

### 轮询刷新

v1.2.0 不提供事件流，客户端用 HTTP 轮询刷新快照。
轮询调度属于 UI 策略，不引入额外实时传输库；DTO 与 revision 语义
仍由 `contract` 唯一定义。

## 5. 依赖验收

| 依赖 | 维护/平台 | 许可证 | 运行时代价 | 结论 |
| --- | --- | --- | --- | --- |
| Ktor 3.6.1 | 活跃；server/client 均支持 `linuxArm64` 与 Wasm | Apache-2.0 | 已在技术栈内 | 采用 |
| ktor-mqtt 1.2.2 | 活跃；`linuxArm64` / `linuxX64` / `macosArm64` | Apache-2.0 | 纯 Kotlin MQTT 5 客户端 | 采用 |
| ktor-client-curl 3.6.1 | 活跃；Linux/macOS 原生目标 | Apache-2.0 | klib 自带静态 libcurl + OpenSSL；提供 TLS 与 WebSocket | 采用（仅为 MQTT over WSS） |
| Room3 3.0.3 | AndroidX；KMP | Apache-2.0 | KSP 生成代码 + 运行时 | 采用 |
| sqlite-bundled 2.7.1 | AndroidX；KMP | Apache-2.0 | 自带 SQLite 原生库 | 采用 |
| kotlinx.serialization 1.11.0 | Kotlin 官方 | Apache-2.0 | 已有 | 采用 |
| paho.mqtt.golang 1.5.1 | 活跃 | EPL-2.0 / EDL-1.0 | 仅 mock | 采用 |
| DitchOoM MQTT | `linuxArm64` 需 io_uring | MIT | Docker 默认策略下不可运行 | 拒绝 |
| Meshtastic MQTT | `linuxArm64` | GPL-3.0 | 许可证冲突 | 拒绝 |

## 6. 验证

本次迁移后实际执行：

- `:server:linkDebugExecutableLinuxArm64` 通过（Room3 KSP + ktor-mqtt + Curl 引擎链接）。
- `:server:linkDebugExecutableMacosArm64` 通过；以 `wss://…:8084/mqtt`
  连接 EMQX Cloud，CONNACK 成功并完成订阅，凭据未出现在日志中。
- `:contract:testAndroidHostTest`、`:shared:compileKotlinWasmJs` 通过。
- `:androidApp:assembleDebug`、`:webApp:wasmJsBrowserDistribution` 通过。
- `mock/` 的 `go test ./...` 通过。

端到端（Docker `linuxArm64` + Mosquitto 2.1.2 + `mock/`）：

- ktor-mqtt（MQTT 5）与 mock（MQTT 3.1.1）经 Broker 互通；
  retained LWT/version、`kill`/`boot` 正常。
- 学习开/关码各 3 次、候选接受、confirm 保存设备成功。
- 学习会话仅存内存：保存设备后重启服务端，会话返回
  `404 learning_session_not_found`，设备仍可读取。
- 命令 `202`、发布后保持 `dispatched`、幂等重放；抓码不再改变命令状态。
- SQLite 跨服务端重启保留设备；删除后 tombstone `410`，码可复用。
- Room v1 → v2 迁移：`REPORTED`→`COMMANDED`、`CONFIRMED`→`DISPATCHED`，
  删除 `confirmedAtEpochMs`；v2 → v3 删除 `learning_sessions` /
  `learning_candidates`。
- Broker 停止时进程存活，恢复后自动重连。

未执行：EMQX 特定行为、多网关隔离、Android/Web UI 与新服务端的联调。

## 7. 2026-11-07 服务端迁移：Go + Turso Cloud

Ktor/Kotlin Native 服务端被移除，`go-server` 成为唯一服务端实现。持久化从
本地 SQLite 文件（GORM + `gorm.io/driver/sqlite`）切换到 Turso Cloud。
HTTP 契约、MQTT 协议、状态机与表结构不变。

### 7.1 复用

| 能力 | 复用 | 说明 |
| --- | --- | --- |
| HTTP | `github.com/labstack/echo/v4` v4.16.3 | 已在 `go-server` 中使用，不新增路由框架 |
| ORM / 迁移 | `gorm.io/gorm` v1.31.2 + `gorm.io/driver/sqlite` v1.6.0 | 通过 `Config{Conn}` 复用 SQLite 方言与 `AutoMigrate`（迁移部分见第 9 节） |
| MQTT | `github.com/eclipse/paho.mqtt.golang` v1.5.1 | 已在 `go-server` 中使用 |
| JSON / 时间 / ID | Go 标准库 + 现有 `contract.go` | 不新增协议层或 DTO 生成器 |
| 测试数据库 | mattn `:memory:` | 与生产共用同一 GORM dialector |

### 7.2 新增依赖

`turso.tech/database/tursogo-serverless`，固定到
`v0.0.0-20261107095519-8e7eac038f1f`（2026-11-07）。

- 官方 Turso Cloud serverless 驱动，纯 Go，无 CGO。
- 支持 `libsql://` / `turso://` / `https://` 与本地 sqld 的 `http://`。
- 通过 `Authorization: Bearer <token>` 携带 `TURSO_AUTH_TOKEN`。
- 尚未发布 tagged release，因此使用伪版本并在 `go-server/README.md`
  记录升级检查点。

### 7.3 未采用

| 方案 | 原因 |
| --- | --- |
| `go-libsql` + `gorm.io/driver/sqlite` | 两者各自链接一份 C SQLite，链接期出现大量 duplicate symbols；不能在同一二进制中混用 |
| `bryanvaz/gorm-libsql` + `go-libsql` | 可用，但 dialector 自 2024-10 起无更新，不满足维护活跃要求 |
| `morelj/gorm-sqlite-libsql` + `libsql-client-go` | 底层 `libsql-client-go` 已标记 deprecated；不再作为新选型 |
| `tursogo`（embedded） | 仅支持本地/同步数据库，不满足“Turso Cloud 远程”的选择 |
| 保留本地 SQLite 文件 | 用户明确要求远程 Turso；容器不再挂载数据库卷 |
| 自研 Turso HTTP 协议客户端 | 驱动是官方维护的薄封装，自研会重复协议与认证逻辑 |

`gorm.io/driver/sqlite` 仍导入 `mattn/go-sqlite3`，所以 Docker 构建保留 CGO；
生产运行时实际使用纯 Go 的 Turso 驱动，mattn 只服务于测试与 dialector 导入。

### 7.4 验证

- `gofmt -w *.go`、`go vet ./...`、`CGO_ENABLED=1 go test ./...`：
  65 个测试通过（使用 `:memory:`，不访问 Turso）。
- 本地 sqld（`http://127.0.0.1:18080`）联调：`AutoMigrate` 成功，
  `GET /api/v1/health` 返回 `status=ok`，`GET /api/v1/devices` 返回空列表；
  `TURSO_AUTH_TOKEN` 在日志中显示为 `***`。
- Ktor 模块移除后重新执行 Gradle：
  `:contract:testAndroidHostTest`、`:shared:compileKotlinWasmJs`、
  `:androidApp:assembleDebug`、`:webApp:wasmJsBrowserDistribution` 均通过。
- `mock/`：21 个测试通过。
- `go-server/Dockerfile` 构建成功；容器以非 root 用户启动，通过
  `host.docker.internal` 连接本地 sqld，健康检查返回 `status=ok`。
- 未执行：真实 Turso Cloud 实例、真实网关端到端、Android/Web UI 联调。
  旧 Ktor 服务端的 `server/esp32-control.db` 未自动导入，因为当前环境没有
  Turso Cloud 凭据；需要时用 Turso CLI 或 SQL 手工迁移。

## 8. 2026-11-07 分层重组与 Koanf 配置加载

`go-server` 从扁平 `package main` 拆分为 `internal/` 分层结构：
`config`、`contract`、`apierror`、`logging`、`idgen`、`repository`、
`service`、`mqtt`、`server`。同时用 Koanf 替换手写配置解析。配置只读取
工作目录 `.env`（存在则加载，缺失则跳过），环境变量优先；不提供
`CONFIG_FILE` 或目录搜索。HTTP 契约、MQTT 协议、状态机与表结构不变。

### 8.1 复用

| 能力 | 复用 | 说明 |
| --- | --- | --- |
| 分层骨架 | 原 `package main` 的职责划分 | 只移动与导出，不重写业务逻辑 |
| 测试数据库 | `repository.Open(":memory:")` | 仍使用同一 GORM sqlite dialector |
| JSON | `encoding/json` | 不新增序列化层 |
| 设备/命令/学习状态机 | `internal/service` | 常量、TTL、校验规则逐项保留 |
| HTTP 中间件 | 现有 Echo CORS / KeyAuth / 自研 request-id、recover | 只改包名与导出名 |

### 8.2 新增依赖

`github.com/knadh/koanf/v2` v2.4.1，配套
`github.com/knadh/koanf/providers/file` v1.2.1、
`github.com/knadh/koanf/providers/env` v1.1.0、
`github.com/knadh/koanf/parsers/dotenv` v1.1.3（2026-11-07）。

验收：

- 维护活跃：Koanf 为广泛使用的 Go 配置库，近期持续发布；子模块均有 tag。
- 运行时代价：纯 Go、无 CGO、无额外运行时；只在启动阶段构建一次。
- 类型与语义：`String` / `Int` 读取与现有 `Config` 字段一一对应；无需 fork。
- 可替换性：仅 `internal/config/loader.go` 依赖 Koanf，其余包只使用 `Config`。
- 平台匹配：与 Go 1.26 标准库兼容，不影响 Wasm/Android 客户端。

行为差异：dotenv 文件改由 godotenv 解析。旧手写解析器会静默跳过非法行；
新实现遇到语法错误会启动失败。仓库内没有依赖“忽略非法行”的 `.env`，因此
不构成兼容性问题。

### 8.3 未采用

| 方案 | 原因 |
| --- | --- |
| 继续手写 `KEY=VALUE` 解析 | 已支持注释、`export`、引号与转义，继续自研会重复 dotenv 语义 |
| `viper` | 功能覆盖更广但依赖树与运行时代价明显更大；本项目只需要 dotenv + env 两层 |
| `envconfig` 一类仅环境变量的库 | 需要保留工作目录 `.env` 作为低优先级来源 |
| 引入第二套配置结构 | `Config` 已覆盖全部字段；只替换加载方式 |

### 8.4 验证

- `gofmt -w .`、`go vet ./...`、`CGO_ENABLED=1 go test ./...`：
  81 个测试通过，覆盖 config、logging、repository、service、mqtt、server。
- 配置优先级、必填项、TLS 约束、`DATABASE_URL` 别名与 redacting `String()`
  均由测试覆盖。
- `:contract:testAndroidHostTest`、`:shared:compileKotlinWasmJs`、
  `:androidApp:assembleDebug`、`:webApp:wasmJsBrowserDistribution` 通过
  （Kotlin 模块未改动，任务多为 UP-TO-DATE）。
- `mock/`：21 个测试通过。
- 未执行：真实 Turso Cloud、真实网关端到端、容器构建复测。

## 9. 2026-11-07 版本化迁移（goose）

`AutoMigrate` 只保证“当前模型能建表”，没有迁移历史，无法表达列改名、数据
回填等有序变更。本节把 schema 管理改为版本化 SQL 迁移；表结构与仓储行为不变。

### 9.1 复用

| 能力 | 复用 | 说明 |
| --- | --- | --- |
| 数据库连接 | `internal/repository/store.go` 的 `*sql.DB` | 迁移直接运行在 Turso / `:memory:` 同一连接上，不新建第二套连接 |
| SQLite 方言 | `gorm.io/driver/sqlite` v1.6.0 | 迁移 SQL 与 GORM 使用同一方言与测试数据库 |
| GORM row 模型 | `deviceRow`、`deviceTombstoneRow`、`idempotencyRow` | 模型只服务查询与写入，不再承担建表 |
| 日志 | `internal/logging` | 启动时输出已应用的迁移版本 |

### 9.2 新增依赖

`github.com/pressly/goose/v3` v3.29.0。

- 维护活跃，支持 SQLite 与 `database/sql` 连接；本项目只使用 Provider API。
- 只新增 `mfridman/interpolate`、`sethvargo/go-retry`、`go.uber.org/multierr`
  三个间接依赖，纯 Go、无 CGO。
- 版本表为 `goose_db_version`；迁移文件通过 `embed.FS` 编入二进制。

### 9.3 未采用

| 方案 | 原因 |
| --- | --- |
| 保留 `AutoMigrate` | 无版本历史，无法审查或回放 schema 变更 |
| `go-gormigrate/gormigrate` | 迁移内容是 Go 代码，schema 变更不可直接用 SQL 审阅；与现有 SQL 方言复用点重复 |
| `golang-migrate/migrate` | 需要额外的 source/driver 装配，依赖与配置面更大；本项目只需要“嵌入式 SQL + 现有连接” |
| 自研迁移执行器 | 版本表、事务、文件排序与校验属于成熟工具的职责，自研收益低 |
| 独立迁移 CLI / Job | 当前为单实例容器部署；保持启动时迁移，避免新增部署步骤。多副本滚动前应先启动一个实例完成升级 |

### 9.4 兼容与验证

- 既有数据库由 `AutoMigrate` 创建，没有版本表。`00001_baseline.sql` 使用
  `CREATE TABLE IF NOT EXISTS`：升级时补建缺失表并把版本记为 1，不修改已有行。
- 测试覆盖：全新库建表、重复迁移幂等、旧 `AutoMigrate` schema 升级后数据保留。
- `gofmt -l .` 无输出，`go vet ./...` 通过，`CGO_ENABLED=1 go test ./...`：
  84 个测试通过（`:memory:`，不访问 Turso）。
- 未执行：真实 Turso Cloud 上的迁移、并发多实例启动。
