该文档定义 server 的一些能力和实现思路

## 功能需求
server 作为客户端和网关沟通的中间节点，需要完成以下内容，。
1. 负责与 mqtt server 进行沟通，将网关状态、系统状态进行
2. 基本设备的生命周期管理。设备的新增、删除、控制等

## 技术栈
1. 数据应该做好持久化，采用 sqlite 存储，ORM 框架采用 Room3。
2. mqtt-client 使用 ktor-mqtt

## 实现状态

- SQLite：`androidx.sqlite:sqlite-bundled:2.7.1` + `BundledSQLiteDriver`。
- ORM：`androidx.room3` 3.0.3，KSP 生成 DAO，schema 位于 `server/schemas/`。
- MQTT：`dev.koft.ktor-mqtt:mqtt-client:1.2.2`（MQTT 5），支持
  `linuxArm64` / `linuxX64` / `macosArm64`。重连由 `mqtt/MqttTransport.kt` 负责。
  MQTT 是必需配置：未设置 `MQTT_HOST` 时启动失败，没有本地降级模式。
  配置读取顺序为环境变量 → `CONFIG_FILE` → `.env` → `server/.env`。
- 平台：`linuxArm64` 用于部署；`macosArm64` 用于本地运行与 IDE 调试。
  MQTT 依赖声明在 `nativeMain`，共享源集与 IDE 才能解析其类型。
- TLS：Ktor Native 未实现裸 TLS socket。托管 Broker 只开放 TLS 时，使用
  MQTT over WebSocket Secure：`MQTT_TRANSPORT=websocket`、`MQTT_TLS=true`，
  端口与路径按 Broker 的 WSS 监听器配置（EMQX Cloud 为 8084 + `/mqtt`）。
  传输由 Ktor Curl 引擎完成，其 klib 自带静态 libcurl + OpenSSL；
  `WebSocketMqttEngine` 只搬运字节，MQTT 分帧仍由 ktor-mqtt 负责。
- 裸 TCP 不支持 `MQTT_TLS=true`：启动时直接报错，避免出现 TLS 会话异步失败
  导致的 CONNACK 超时。
- 结构：`Main.kt` 为组合根；领域逻辑在 `domain/`，持久化在 `db/` + `data/`，
  HTTP 在 `routes/`。不再有 `ControlPlane`。
- 状态同步：v1.2.0 不提供 SSE；客户端轮询快照。

实现细节与表结构见
[`docs/api-contract-v1.md`](api-contract-v1.md) 第 3.4、3.5 节。

DB
1. gateway instance(device id)。
2. server。

// 内存中维护一些设备 list