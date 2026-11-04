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
- MQTT：`dev.koft.ktor-mqtt:mqtt-client:1.2.0`（MQTT 5），
  仅支持 `linuxArm64`；重连由 `mqtt/MqttTransport.kt` 负责。
  MQTT 是必需配置：未设置 `MQTT_HOST` 时启动失败，没有本地降级模式。
- 结构：`Main.kt` 为组合根；领域逻辑在 `domain/`，持久化在 `db/` + `data/`，
  HTTP 在 `routes/`。不再有 `ControlPlane`。
- 状态同步：v1.2.0 不提供 SSE；客户端轮询快照。

实现细节与表结构见
[`docs/api-contract-v1.md`](api-contract-v1.md) 第 3.4、3.5 节。

DB
1. gateway instance(device id)。
2. server。

// 内存中维护一些设备 list