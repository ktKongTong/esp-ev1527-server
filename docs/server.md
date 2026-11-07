该文档定义 server 的能力、技术栈与实现状态。

## 功能需求

server 作为客户端和网关沟通的中间节点，需要完成以下内容：

1. 负责与 MQTT server 通信，维护网关状态与系统状态。
2. 基本设备的生命周期管理：新增、删除、控制、编辑。

## 技术栈

1. 持久化使用 Turso Cloud（SQLite 兼容），ORM 使用 GORM。
2. MQTT 客户端使用 `eclipse/paho.mqtt.golang`。
3. HTTP 使用 Echo。

## 实现状态

- 服务端：`go-server/`，Go 1.26+。GORM 的 sqlite dialector 导入
  `mattn/go-sqlite3`，因此构建需要 `CGO_ENABLED=1`；生产数据库访问走纯 Go 的
  Turso serverless 驱动，不打开本地 SQLite 文件。
- 数据库：`turso.tech/database/tursogo-serverless`，通过
  `TURSO_DATABASE_URL` + `TURSO_AUTH_TOKEN` 连接 Turso Cloud。
  `DATABASE_URL` 是 `TURSO_DATABASE_URL` 的别名。
- 迁移：`github.com/pressly/goose/v3` 执行
  `go-server/internal/repository/migrations/` 下的版本化 SQL，启动时自动应用
  未执行的版本；已应用版本记录在 `goose_db_version` 表。不使用 GORM
  `AutoMigrate`。`00001_baseline.sql` 用 `CREATE TABLE IF NOT EXISTS` 兼容
  旧 `AutoMigrate` 创建的数据库，升级时只记录版本，不改动已有数据。
- 表：`devices`、`device_tombstones`、`idempotency`，以及迁移版本表
  `goose_db_version`。业务列名保持 camelCase，与上一版服务端的表结构兼容。
- MQTT：paho 提供 MQTT 3.1.1、QoS 1、keepalive 与自动重连。MQTT 是必需配置：
  未设置 `MQTT_HOST` 时启动失败，没有本地降级模式。
- 配置只读取工作目录中的 `.env`（存在则加载，缺失则跳过），环境变量优先；
  不支持 `CONFIG_FILE` 或目录搜索。
- 鉴权：`AUTH_TOKEN` 必填，未设置时启动失败。除 `/` 与
  `GET /api/v1/health` 外，所有接口要求 `Authorization: Bearer <token>`；
  校验由 Echo `middleware.KeyAuth` 完成，比较使用 `crypto/subtle` 常量时间。
  失败返回 `401 unauthorized` 与统一错误信封。健康检查保持公开，供容器
  `HEALTHCHECK` 与外部探针使用。
  v1 仍是单一共享令牌，没有多用户、角色、审计或限流。
- TLS：网关契约未定义裸 TCP TLS。托管 Broker 只开放 TLS 时，使用 MQTT over
  WebSocket Secure：`MQTT_TRANSPORT=websocket`、`MQTT_TLS=true`，端口与路径
  按 Broker 的 WSS 监听器配置（EMQX Cloud 为 8084 + `/mqtt`）。
  `MQTT_TLS=true` 不能与 `MQTT_TRANSPORT=tcp` 组合，启动时直接报错。
- 结构：`main.go` 为组合根；HTTP 在 `http.go`，领域逻辑在 `devices.go` /
  `commands.go` / `learning.go`，持久化在 `repositories.go` + `store.go`，
  迁移在 `migrate.go` + `migrations/`，MQTT 在 `mqtt.go` / `gateway.go`。
- 状态同步：v1.2.0 不提供 SSE；客户端轮询快照。

接口、状态机与数据流的完整定义见
[`docs/api-contract-v1.md`](api-contract-v1.md) 第 3.4、3.5 节。

## 运行时状态

- 网关在线状态与接收器证据不持久化；服务端重启后先按 unknown 处理，
  由 retained LWT/version 恢复。
- 学习会话与候选只存在于服务端内存。重启后未保存的会话全部丢失，客户端轮询
  得到 `404 learning_session_not_found`，需要重新开始；已确认的设备不受影响。
- 幂等记录在发布前预留；发布成功后写入 `dispatchedAtEpochMs`，发布失败则删除，
  允许客户端重试。重启不重发任何命令。

## 历史

Ktor/Kotlin Native 服务端（原 `server/`）已于 2026-11-07 移除。本文件原先的
Room3、ktor-mqtt、Kotlin/Native 部署说明仅适用于该实现，不再维护。
