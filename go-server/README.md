# ESP32 Control Server（Go 实现）

`go-server` 是本仓库唯一的服务端实现，面向 `docs/api-contract-v1.md` 的
HTTP 契约与 MQTT 网关协议。Ktor/Kotlin Native 服务端已移除；本模块保留原有
接口、状态机与表结构，数据持久化改为 Turso Cloud。

## 技术栈

| 能力 | 选型 | 说明 |
| --- | --- | --- |
| 语言 | Go 1.26+ | 需要 CGO（GORM sqlite dialector 导入 `mattn/go-sqlite3`） |
| HTTP | Echo v4.16.3 | 路由、CORS、错误映射 |
| ORM | GORM v1.31.2 + `gorm.io/driver/sqlite` v1.6.0 | 通过 `Config{Conn}` 复用 SQLite SQL 与迁移能力 |
| 数据库 | Turso Cloud | 纯 Go 驱动 `turso.tech/database/tursogo-serverless`，不落容器磁盘 |
| MQTT | `eclipse/paho.mqtt.golang` v1.5.1 | MQTT 3.1.1、QoS 1、自动重连 |
| 日志 | Go 标准库 `log/slog` | 分级、结构化输出到 stdout |

生产数据库访问使用 Turso serverless 驱动；`gorm.io/driver/sqlite` 只提供
SQLite 方言与 `AutoMigrate`，不会打开本地文件。测试使用同一 dialector 的
`:memory:` 分支。

## 目录

```text
contract.go       跨端 DTO、枚举、版本常量
config.go         环境变量 / CONFIG_FILE / .env 配置加载
store.go          GORM row 模型、Turso 连接、AutoMigrate
repositories.go   device / idempotency / tombstone 仓储
devices.go        设备查询与删除（tombstone）
commands.go       命令下发与幂等键
learning.go       学习状态机
gateway.go        网关在线状态、LWT/version、RF 采集解析
mqtt.go           paho 连接、订阅、发布
http.go           Echo 路由、请求 ID、错误信封
main.go           组装、后台 tick、优雅退出
```

## 配置

配置优先级：**环境变量 → `CONFIG_FILE` → `.env` → `go-server/.env` →
`server/.env`（legacy）**。环境变量优先；空值视为未设置。`server/.env` 仅用于
兼容旧的本地配置，Ktor 服务端删除后不再需要该目录。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_HOST` | `0.0.0.0` | HTTP 监听地址 |
| `SERVER_PORT` | `8080` | HTTP 端口 |
| `AUTH_TOKEN` | 必填 | API 访问令牌；客户端发送 `Authorization: Bearer <token>`。未设置时拒绝启动 |
| `TURSO_DATABASE_URL` | 必填 | Turso Cloud URL，例如 `libsql://<db>-<org>.turso.io` |
| `DATABASE_URL` | 空 | `TURSO_DATABASE_URL` 的别名；两者都设置时以前者为准 |
| `TURSO_AUTH_TOKEN` | 空 | Turso Cloud 数据库 token；本地 sqld 可留空 |
| `MQTT_HOST` | 必填 | 未设置时拒绝启动 |
| `MQTT_PORT` | `1883` | WebSocket 常用 `8084` |
| `MQTT_USERNAME` | 空 | |
| `MQTT_PASSWORD` | 空 | 不会出现在日志或 API 响应中 |
| `MQTT_CLIENT_ID` | 随机 `esp32-control-server_<hex>` | |
| `MQTT_TOPIC_PREFIX` | `home/OpenMQTTGateway` | 命令主题为 `${prefix}/commands/MQTTto433` |
| `MQTT_TRANSPORT` | `tcp` | `tcp` 或 `websocket` |
| `MQTT_TLS` | `false` | 仅允许与 `websocket` 组合 |
| `MQTT_WS_PATH` | `/mqtt` | WebSocket 路径 |
| `GATEWAY_CLIENT_ID` | `OpenMQTTGateway_ESP32_315M` | 网关客户端 ID |
| `LOG_LEVEL` | `debug` | `debug` / `info` / `warn` / `error` |
| `CONFIG_FILE` | 空 | 显式指定配置文件 |

TLS 约束与网关契约一致：未定义裸 TCP TLS，`MQTT_TLS=true` 必须配合
`MQTT_TRANSPORT=websocket`。

## 构建与运行

```bash
cd go-server
CGO_ENABLED=1 go build -o esp32-control-server .

TURSO_DATABASE_URL='libsql://<db>-<org>.turso.io' \
TURSO_AUTH_TOKEN='<token>' \
MQTT_HOST=broker.example.com \
./esp32-control-server
```

也可以直接运行：

```bash
cd go-server
TURSO_DATABASE_URL='libsql://<db>-<org>.turso.io' \
TURSO_AUTH_TOKEN='<token>' \
MQTT_HOST=broker.example.com \
AUTH_TOKEN='<long-random-token>' \
go run .
```

测试与静态检查：

```bash
cd go-server
gofmt -w *.go
go vet ./...
CGO_ENABLED=1 go test ./...
```

测试使用 `:memory:` SQLite，不访问 Turso，也不需要 token。

## 容器镜像

镜像发布到 GitHub Container Registry：

```bash
docker pull ghcr.io/ktkongtong/esp-ev1527-server:latest
docker run -d --name esp32-control-go \
  -p 8080:8080 \
  -e MQTT_HOST=broker.example.com \
  -e TURSO_DATABASE_URL='libsql://<db>-<org>.turso.io' \
  -e TURSO_AUTH_TOKEN='<token>' \
  -e AUTH_TOKEN='<long-random-token>' \
  ghcr.io/ktkongtong/esp-ev1527-server:latest
```

`linux/amd64` 与 `linux/arm64` 多架构构建。GORM 的 sqlite dialector 导入
`mattn/go-sqlite3`，因此构建阶段需要 CGO；arm64 阶段在 buildx/QEMU 下原生
编译。数据库在 Turso Cloud，容器不需要挂载卷。

每次构建推送两个 tag：

- `latest`：跟随 `main` 的浮动 tag。
- `<version>`：固定到 `go-server/contract.go` 中的 `serverVersion`，
  与 `/api/v1/health` 返回的 `version` 一致，例如 `0.2.0`。
  生产部署建议使用该 tag。

本地构建（在仓库根目录执行）：

```bash
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -f go-server/Dockerfile \
  -t ghcr.io/ktkongtong/esp-ev1527-server:latest --push .
```

GitHub Actions 工作流：`.github/workflows/server-image.yml`。`main` 分支的
`go-server/**` 变更会构建并推送 `latest` 与 `<version>` 两个 tag；
Pull Request 只构建不推送。

## HTTP 接口

与 `docs/api-contract-v1.md` 一致：

```text
GET    /api/v1/health
GET    /api/v1/devices
GET    /api/v1/devices/:deviceId
DELETE /api/v1/devices/:deviceId
POST   /api/v1/devices/:deviceId/commands

POST   /api/v1/learning/sessions
GET    /api/v1/learning/sessions/:sessionId
POST   /api/v1/learning/sessions/:sessionId/candidates/:candidateId/accept
POST   /api/v1/learning/sessions/:sessionId/rescan
POST   /api/v1/learning/sessions/:sessionId/test
POST   /api/v1/learning/sessions/:sessionId/confirm
DELETE /api/v1/learning/sessions/:sessionId
```

所有 JSON 字段为 lowerCamelCase；枚举值为 lower_snake_case；数组字段编码为
`[]` 而不是 `null`。错误统一为 `{"error":{code,message,requestId,details}}`。

除 `GET /api/v1/health` 与根路径 `/` 外，所有接口都要求
`Authorization: Bearer <AUTH_TOKEN>`。缺失或错误时返回
`401 {"error":{"code":"unauthorized"}}`，并带 `X-Request-Id`：

```bash
curl -H "Authorization: Bearer $AUTH_TOKEN" \
  http://127.0.0.1:8080/api/v1/devices
```

健康检查保持公开：容器 `HEALTHCHECK` 与外部探针不需要凭据，响应中也不含设备数据。

## 数据库

- 连接：`turso.tech/database/tursogo-serverless`，通过 `TURSO_DATABASE_URL` 与
  `TURSO_AUTH_TOKEN` 访问 Turso Cloud。`libsql://` 与 `turso://` 由驱动统一为
  HTTPS；本地 sqld 可直接使用 `http://127.0.0.1:8080`。
- 表：`devices`、`device_tombstones`、`idempotency`，列名保持 camelCase，
  与上一版服务端的表结构一致。
- 迁移：启动时执行 GORM `AutoMigrate`。表已存在且列兼容时不会删改数据。
- 本地旧数据库：本模块不再打开 SQLite 文件。若要保留旧的
  `esp32-control.db` 数据，需要先用 Turso CLI 或 SQL dump 导入 Turso 数据库，
  再切换 `TURSO_DATABASE_URL`；仓库不自动执行该迁移。
- 运行时状态：网关在线状态、学习会话、命令幂等记录的生命周期与
  `docs/api-contract-v1.md` 第 3.5 节一致；学习会话不持久化。

## 与上一版 Kotlin 服务端的差异

Ktor/Kotlin Native 服务端已于 2026-11-07 移除，以下是仍然适用的差异：

- 运行时字段为 `"Go"`；其余 health 字段保持一致。
- MQTT 库为 paho（MQTT 3.1.1），上一版使用 ktor-mqtt（MQTT 5）。QoS 1、
  clean session、keepalive 和自动重连语义保持等价。
- 重连由 paho 的 `AutoReconnect` / `ConnectRetry` 负责，不再手写重连循环。
- 配置文件搜索额外包含 `go-server/.env`，便于在模块目录内直接启动。
- 学习窗口以契约实现为准：600ms 按键间隔、3 次命中、60s 采集、
  30s 候选、5min ready、10min 会话。

## 复用与偏离记录

```text
Reused: gorm.io/gorm v1.31.2 + gorm.io/driver/sqlite v1.6.0
        (existing GORM persistence and migration path)
Reused: github.com/labstack/echo/v4 v4.16.3 (existing HTTP layer),
        including middleware.KeyAuth for bearer-token auth
Reused: github.com/eclipse/paho.mqtt.golang v1.5.1 (existing MQTT client)
New dependency: turso.tech/database/tursogo-serverless
        v0.0.0-20261107095519-8e7eac038f1f
Not reused because: go-libsql is CGO-based and links a second C SQLite, which
        collides with mattn/go-sqlite3 imported by the GORM sqlite dialector
        (duplicate C symbols). tursogo is embedded/local only and does not
        provide the requested Turso Cloud remote connection.
New implementation scope: connection construction and config fields only;
        table models, repositories, state machines, and HTTP handlers are
        unchanged.
```

`tursogo-serverless` is the official Turso Cloud serverless driver but has no
tagged release yet. The module pins a pseudo-version; check for a tagged
release before upgrading.
