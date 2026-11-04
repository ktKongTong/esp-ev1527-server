# 复用审计记录

审计日期：2026-11-03。范围：仓库内所有运行时实现与依赖。
本地文件：`docs/reuse-audit.md`；本版实现为 contract v1.2.0 迁移（未提交）。
判定顺序遵循仓库根目录 `AGENTS.md` 第 1 节：项目内实现 → 已有依赖 →
标准库/运行时 → 成熟第三方库。

## 1. 结论总览

| 现有实现 | 能力 | 判定 | 处理 |
| --- | --- | --- | --- |
| `server/.../ControlPlane.kt` | 设备/命令/学习状态机、码归属 | 产品领域逻辑；单对象职责过载 | 删除 God object；状态机原样拆到 `DeviceService` / `CommandService` / `LearningService` / `GatewayIngest` |
| `server/.../MqttClient.kt` | 手写 MQTT 3.1.1 连接、订阅、发布 | 成熟库已覆盖 | 删除；改用 ktor-mqtt 1.2.0 薄适配层 `mqtt/MqttTransport.kt` |
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

### 2.1 采用：ktor-mqtt 1.2.0

- 坐标：`dev.koft.ktor-mqtt:mqtt-client:1.2.0` +
  `dev.koft.ktor-mqtt:mqtt-core:1.2.0`
- 来源：`https://github.com/ktKongTong/ktor-mqtt/` 的 `1.2.0` 构建，
  发布到 GitHub Packages。
- 许可证：Apache-2.0（POM 中声明）。
- 目标：`linuxArm64`（另有 Apple 目标）；**不发布 `linuxX64`**。
  因此 `server` 只保留 `linuxArm64` 目标。
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

### 2.2 未采用

| 库 | 原因 |
| --- | --- |
| KMQTT 1.0.0 | 只支持 MQTT 3.1.1；本版要求改用 ktor-mqtt。作为历史实现已删除，不保留平行适配层 |
| `com.ditchoom:mqtt-client:2.1.1` | Linux 实现依赖 io_uring；Docker 默认 seccomp 下初始化失败：`Failed to initialize io_uring: Operation not permitted` |
| `org.meshtastic:mqtt-client:0.3.8` | GPL-3.0，与仓库分发约束不匹配 |
| 上游 `de.kempmobil.ktor-mqtt` | 不发布 `linuxArm64` 原生目标 |

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
| ktor-mqtt 1.2.0 | 活跃；`linuxArm64` | Apache-2.0 | 纯 Kotlin MQTT 5 客户端 | 采用 |
| Room3 3.0.3 | AndroidX；KMP | Apache-2.0 | KSP 生成代码 + 运行时 | 采用 |
| sqlite-bundled 2.7.1 | AndroidX；KMP | Apache-2.0 | 自带 SQLite 原生库 | 采用 |
| kotlinx.serialization 1.11.0 | Kotlin 官方 | Apache-2.0 | 已有 | 采用 |
| paho.mqtt.golang 1.5.1 | 活跃 | EPL-2.0 / EDL-1.0 | 仅 mock | 采用 |
| DitchOoM MQTT | `linuxArm64` 需 io_uring | MIT | Docker 默认策略下不可运行 | 拒绝 |
| Meshtastic MQTT | `linuxArm64` | GPL-3.0 | 许可证冲突 | 拒绝 |

## 6. 验证

本次迁移后实际执行：

- `:server:linkDebugExecutableLinuxArm64` 通过（Room3 KSP + ktor-mqtt 链接）。
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
