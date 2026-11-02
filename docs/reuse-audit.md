# 复用审计记录

审计日期：2026-11-03。范围：仓库内所有运行时实现与依赖。
本地文件：`docs/reuse-audit.md`；实现 commit：`8a7d1d6`。
判定顺序遵循仓库根目录 `AGENTS.md` 第 1 节：项目内实现 → 已有依赖 →
标准库/运行时 → 成熟第三方库。

## 1. 结论总览

| 现有实现 | 能力 | 判定 | 处理 |
| --- | --- | --- | --- |
| `server/.../MqttClient.kt` | MQTT 3.1.1 连接、订阅、QoS 1 发布 | 成熟库已覆盖 | 改为 KMQTT 1.0.0 薄适配层；删除原手写 MQTT 协议实现 |
| `server/.../Main.kt` SSE 路由 | SSE 分帧、心跳、断线保持 | Ktor 已覆盖 | 改用 Ktor `server-sse` 的 `sse()` / `heartbeat()` / `ServerSentEvent` |
| `server/.../SseHub.kt` | 事件扇出、256 条重放、`Last-Event-ID`、`stream.reset` | 领域策略 | 保留；底层使用 `MutableSharedFlow` |
| `server/.../ControlPlane.kt` | 设备/命令/学习状态机、码归属 | 产品领域逻辑 | 保留 |
| `server/.../ControlPlane.kt` 的抓码解析 | 网关 RF payload 解码 | `ContractJson` + DTO 已覆盖 | 保留现有 DTO 解码，不手写 JSON |
| `server/.../Main.kt` `RequestContextPlugin` | 请求 ID、`Cache-Control` | 应用策略；无等价内置组合 | 保留（约 12 行），见第 3 节 |
| `server/.../ServerConfig.kt` | 环境变量读取 | 平台 API | 保留 POSIX `getenv` |
| `server/.../Util.kt` `nowEpochMs` | 当前时间 | 标准库 | 使用 `kotlin.time.Clock` |
| `server/.../Util.kt` `newId` | 随机 ID | 标准库 | 使用 `kotlin.random.Random` |
| `shared/.../api/Esp32Api.kt` | HTTP/JSON 客户端 | Ktor + `ContractJson` | 保留适配层 |
| `shared/.../api/Esp32Api.kt` `newIdempotencyKey` | 客户端幂等键 | 标准库 | 保留；与服务端 ID 命名空间不同 |
| `shared/.../App.kt` 轮询 | 状态刷新 | 应用策略 | 保留；SSE 为可选增强，不是协议重复实现 |
| `shared/...` Compose UI | Material 3 界面 | Compose Multiplatform | 保留 |
| `contract/...` | DTO、SSE 事件、错误信封、JSON 配置 | kotlinx.serialization | 保留，服务端与客户端唯一协议来源 |
| `mock/` | OpenMQTTGateway 仿真 | Go 标准库 + paho.mqtt.golang | 保留 |
| 错误映射 | HTTP 错误信封 | Ktor StatusPages | 保留 |
| CORS | 跨域预检与响应头 | Ktor CORS | 保留 |

## 2. MQTT 库选择

### 2.1 采用：KMQTT 1.0.0

- 坐标：`io.github.davidepianca98:kmqtt-client:1.0.0`
- 许可证：MIT（Gradle 模块 POM 中声明）
- 目标：Kotlin Multiplatform，包含 Kotlin/Native `linuxArm64`
- 覆盖：MQTT 3.1.1、QoS 1、订阅、keepalive、PUBACK
- 复用方式：`MqttClient.kt` 仅保留库未提供的策略
  - Broker 断开后的有界指数退避重连（KMQTT 1.0.0 不会自动重连）
  - 隔离构造函数同步连接失败与 `run()` 在 socket 关闭时抛出的异常，
    避免 Kotlin/Native 进程退出，也避免 Broker 未启动时重连循环中断
  - 主题前缀分类、命令主题过滤
  - `ByteArray`（服务端内部）与 `UByteArray`（KMQTT API）边界转换
  - 连接/订阅超时与回调桥接

适配层不复制 MQTT 帧编码、CONNECT/SUBSCRIBE/PUBLISH 状态机或 QoS 确认逻辑。

### 2.2 未采用

| 库 | 原因 |
| --- | --- |
| `com.ditchoom:mqtt-client:2.1.1` | Linux 实现依赖 io_uring；Docker 默认 seccomp 下初始化失败：`Failed to initialize io_uring: Operation not permitted`。放宽容器安全策略不可接受。 |
| `org.meshtastic:mqtt-client:0.3.8` | GPL-3.0，与仓库分发约束不匹配。 |
| `ukemp/ktor-mqtt` | 没有 `linuxArm64` 目标，无法部署到当前服务端。 |

## 3. 保留的自定义代码

### `RequestContextPlugin`

Ktor 的 `CallId` 只解决请求 ID；本插件还需要：

- 接受并校验 1–128 字符的入站 `X-Request-Id`；
- 对所有非 SSE 响应设置 `Cache-Control: no-store`；
- 在错误信封中复用同一个请求 ID。

为这三点引入 `ktor-server-call-id` 仍需保留自定义缓存策略，收益不足。
插件只做头部策略，不实现 HTTP 协议能力。

### `SseHub`

`MutableSharedFlow` 已提供扇出与重放缓存；`SseHub` 只实现契约要求的
`Last-Event-ID` 补发、缓冲区未命中时的 `stream.reset`，以及把事件转交给
Ktor SSE 写出。分帧与心跳由 Ktor 负责。

### `newId` / `newIdempotencyKey`

两处都是标准库 `Random` 的几行封装，格式与命名空间不同：

- 服务端实体 ID：`prefix_` + 24 位大写十六进制；
- 客户端幂等键：36 位小写十六进制（契约允许客户端自定义）。

不为单函数能力引入 UUID 依赖或新增公共抽象。

### 轮询刷新

客户端当前用 HTTP 轮询刷新快照。SSE 是服务端的可选推送通道，轮询是契约中
明确保留的降级路径；UI 没有重复解析 SSE 帧，也没有第二套事件类型。

## 4. 依赖验收

| 依赖 | 维护/平台 | 许可证 | 运行时代价 | 结论 |
| --- | --- | --- | --- | --- |
| Ktor 3.6.1 | 活跃；server/client 均支持 `linuxArm64` 与 Wasm | Apache-2.0 | 已在技术栈内 | 采用 |
| KMQTT 1.0.0 | 支持 `linuxArm64` 与 Kotlin/Native | MIT | 纯 Kotlin socket 实现，无原生 io_uring 依赖 | 采用 |
| kotlinx.serialization 1.11.0 | Kotlin 官方 | Apache-2.0 | 已有 | 采用 |
| paho.mqtt.golang 1.5.1 | 活跃 | EPL-2.0 / EDL-1.0 | 仅 mock | 采用 |
| DitchOoM MQTT | `linuxArm64` 需 io_uring | MIT | Docker 默认策略下不可运行 | 拒绝 |
| Meshtastic MQTT | `linuxArm64` | GPL-3.0 | 许可证冲突 | 拒绝 |

## 5. 验证

- `:server:compileKotlinLinuxArm64` / `:server:linkDebugExecutableLinuxArm64` 通过。
- Docker + EMQX 5.8.7 + `mock/`：
  - 网关 LWT 上线/离线；
  - 学习开码 3 次、接受、关码 3 次、接受、试发、保存；
  - 控制命令 `202`、RF 回显确认、幂等重放；
  - 设备删除 `204`、重复删除 `204`、删除后 `GET 410`；
  - 非法 JSON `400`、错误媒体类型 `415`；
  - SSE 事件字段、`Last-Event-ID` 补发、未知 ID 的 `stream.reset`、心跳。
- 重启 EMQX 后，服务端进程保持存活，网关状态自动恢复为 `online`。
- Broker 未启动时启动服务端，HTTP 接口保持可用；Broker 上线后自动连接，
  网关状态恢复为 `online`。
