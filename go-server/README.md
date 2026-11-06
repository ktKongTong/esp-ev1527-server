# ESP32 Control Server（Go 实现）

`go-server` 是 `server/`（Kotlin Native / Ktor）的 Go 等价实现，面向同一份
HTTP 契约与 MQTT 网关协议。它保持与 Kotlin 服务端一致的接口、状态机和持久化
结构，可单独运行，也可用于对拍验证。

## 技术栈

| 能力 | 选型 | 说明 |
| --- | --- | --- |
| 语言 | Go 1.26+ | 需要 CGO（SQLite 驱动） |
| HTTP | Echo v4.16.3 | 路由、CORS、错误映射 |
| ORM | GORM v1.31.2 + `gorm.io/driver/sqlite` v1.6.0 | 底层 `mattn/go-sqlite3`，需 `CGO_ENABLED=1` |
| MQTT | `eclipse/paho.mqtt.golang` v1.5.1 | MQTT 3.1.1、QoS 1、自动重连 |
| 日志 | Go 标准库 `log/slog` | 分级、结构化输出到 stdout |
| 数据库 | SQLite | 表名与列名与 Room3 实现兼容 |

## 目录

```text
contract.go       跨端 DTO、枚举、版本常量
config.go         环境变量 / CONFIG_FILE / .env 配置加载
store.go          GORM row 模型、AutoMigrate、连接初始化
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
`server/.env`**。环境变量优先；空值视为未设置。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_HOST` | `0.0.0.0` | HTTP 监听地址 |
| `SERVER_PORT` | `8080` | HTTP 端口 |
| `MQTT_HOST` | 必填 | 未设置时拒绝启动 |
| `MQTT_PORT` | `1883` | WebSocket 常用 `8084` |
| `MQTT_USERNAME` | 空 | |
| `MQTT_PASSWORD` | 空 | 不会出现在日志或 API 响应中 |
| `MQTT_CLIENT_ID` | 随机 `esp32-control-server_<hex>` | |
| `MQTT_TOPIC_PREFIX` | `home/OpenMQTTGateway` | 命令主题为 `${prefix}/commands/MQTTto433` |
| `MQTT_TRANSPORT` | `tcp` | `tcp` 或 `websocket` |
| `MQTT_TLS` | `false` | 仅允许与 `websocket` 组合 |
| `MQTT_WS_PATH` | `/mqtt` | WebSocket 路径 |
| `DATABASE_PATH` | `esp32-control.db` | SQLite 文件路径 |
| `GATEWAY_CLIENT_ID` | `OpenMQTTGateway_ESP32_315M` | 网关客户端 ID |
| `LOG_LEVEL` | `debug` | `debug` / `info` / `warn` / `error` |
| `CONFIG_FILE` | 空 | 显式指定配置文件 |

TLS 约束与 Kotlin 服务端一致：网关契约未定义裸 TLS，`MQTT_TLS=true` 必须配合
`MQTT_TRANSPORT=websocket`。

## 构建与运行

```bash
cd go-server
CGO_ENABLED=1 go build -o esp32-control-server .
MQTT_HOST=broker.example.com ./esp32-control-server
```

也可以直接运行：

```bash
cd go-server
MQTT_HOST=broker.example.com go run .
```

测试与静态检查：

```bash
cd go-server
gofmt -w *.go
go vet ./...
CGO_ENABLED=1 go test ./...
```

## 容器镜像

镜像发布到 GitHub Container Registry：

```bash
docker pull ghcr.io/ktkongtong/esp-ev1527-server:go-server
docker run --rm -p 8080:8080 \
  -e MQTT_HOST=broker.example.com \
  -v "$PWD/data:/data" \
  ghcr.io/ktkongtong/esp-ev1527-server:go-server
```

`linux/amd64` 与 `linux/arm64` 多架构构建。SQLite 驱动需要 CGO，因此 arm64
阶段在 buildx/QEMU 下原生编译。数据库默认写入 `/data/esp32-control.db`，
容器以 uid 10001 的非 root 用户运行。

本地构建（在仓库根目录执行）：

```bash
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -f go-server/Dockerfile \
  -t ghcr.io/ktkongtong/esp-ev1527-server:go-server --push .
```

GitHub Actions 工作流：`.github/workflows/go-server-image.yml`。`main` 分支的
`go-server/**` 变更会构建并推送 `go-server` tag；Pull Request 只构建不推送。

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

## 数据库兼容性

`store.go` 使用与 Room3 相同的表名和 camelCase 列名（`devices`、
`device_tombstones`、`idempotency`），因此可以打开已有 `esp32-control.db`。
不要同时运行 Kotlin 与 Go 服务端并共享同一个文件；SQLite 写入会互相竞争。

## 与 Kotlin 实现的差异

- 运行时字段为 `"Go"`；其余 health 字段保持一致。
- MQTT 库为 paho（MQTT 3.1.1），Kotlin 使用 ktor-mqtt（MQTT 5）。QoS 1、
  clean session、keepalive 和自动重连语义保持等价。
- 重连由 paho 的 `AutoReconnect` / `ConnectRetry` 负责，不再手写重连循环。
- 配置文件搜索额外包含 `go-server/.env`，便于在模块目录内直接启动。
- 学习窗口按 Kotlin 实现为准：600ms 按键间隔、3 次命中、60s 采集、
  30s 候选、5min ready、10min 会话。

## 复用与偏离记录

```text
Reused: paho.mqtt.golang v1.5.1 (mock/go.mod, already in the workspace)
New dependencies (requested for this implementation):
  github.com/labstack/echo/v4 v4.16.3
  gorm.io/gorm v1.31.2
  gorm.io/driver/sqlite v1.6.0
Not reused because: the task is an alternative Go server; the Kotlin/Ktor
implementation cannot run on the Go runtime.
New implementation scope: HTTP routing, GORM repositories, and paho wiring
behind the same contract and state machine.
```
