# ESP32 智能控制台 · Kotlin 客户端 + Go 服务端

Android + Web 共用一套 Compose UI，Go 服务端通过 MQTT 驱动
OpenMQTTGateway 315MHz 网关，实现**控制开关、学习新设备、编辑设备信息、移除旧设备**。
Android/Web 共享 `contract` 模块中的 HTTP 协议类型；Go 服务端在
`go-server/internal/contract/contract.go` 中镜像同一协议，避免客户端各自维护同名模型。

当前仓库已实现契约 v2.1.0；协议与状态机的完整定义见
[`docs/api-contract-v1.md`](docs/api-contract-v1.md)。仓库内的
[`mock/`](mock/README.md) 是可直接联调的 OpenMQTTGateway v1.7+ 仿真器。

## 已实现能力

- **控制开关**：显式 `turn_on` / `turn_off` 命令，`Idempotency-Key` 保证重试只发布一次；
  发布成功即返回 `202 CommandDispatched`，不等待也不推断执行结果。
- **学习新设备**：客户端发起会话 → 服务端关联 60s 采集窗口 → 消费网关持续上报的
  315MHz 抓码 → 按按键间隔聚类 → 同一码被独立按 3 次生成候选 → 客户端确认开/关键
  → 再学另一个键 → 试发 → 命名保存。没有网关抓码就不会产生设备。
- **编辑设备信息**：`PATCH /devices/{id}` 修改名称或位置；码与 RF 参数不可变，
  编辑不发送 MQTT，也不改变设备生命周期。
- **移除设备**：立即拒绝新命令、阻止未发布的发布动作、释放开/关码、写入 tombstone；
  重复删除保持幂等。
- **持久化**：设备、幂等 key 和 tombstone 保存在 Turso Cloud（GORM），
  服务端重启不丢失；学习会话只存在于内存，重启后需重新开始。
- **无状态设备**：315MHz 链路没有回执，服务端只保存遥控器的开/关码，
  不推断设备状态，也不保存命令历史。
- **状态同步**：客户端按场景轮询快照（学习 1s、网关 30s），
  学习会话以 `revision` 校正；v1 不提供 SSE。
- **完整错误契约**：统一错误信封、请求 ID、`410` tombstone、`409` 状态冲突、
  `422` 码对不合法、`415` 媒体类型错误、`400` 非法 JSON。

## 架构

```
┌──────────┐    HTTP/JSON      ┌──────────────┐  MQTT 3.1.1  ┌───────────┐  315MHz  ┌────────┐
│ Android  │ ◄───────────────► │    Server    │ ◄───────────► │  Gateway  │ ◄──────► │ Remote │
│   Web    │                   │  Go + Echo   │               │   ESP32   │          │ Button │
└──────────┘                   └──────┬───────┘               └───────────┘          └────────┘
      │                               │
      └──────── contract ─────────────┘
                                      ▼
                                Turso Cloud
```

服务端内部：Echo 路由 → 领域服务（设备/命令/学习）→ 仓储 → GORM → Turso；
MQTT 摄入由 `gatewayIngest` 分发给学习状态机。`main.go` 只负责组装。

- 服务端是设备、命令、学习会话状态的唯一权威。
- 网关负责 MQTT 桥接与 315MHz 收发；服务端订阅主题前缀下的全部消息，
  按 payload 分类处理 LWT、版本与 RF 抓码，不依赖硬编码的抓码子主题。
- 315MHz 是单向链路：MQTT PUBACK 只代表 Broker 接收，命令 `202` 只代表已发布。
  网关抓码只用于学习，不用于确认命令执行结果。

## 目录结构

```
esp32/
├── contract/        KMP 共享协议：DTO、ContractJson、枚举
├── go-server/       Go + Echo + GORM + Turso Cloud + paho MQTT
├── shared/          Compose Multiplatform UI 与 Ktor 客户端（Android + Wasm）
├── androidApp/      Android 应用壳（Material You 动态取色）
├── webApp/          Kotlin/Wasm Web 应用与 index.html
├── mock/            OpenMQTTGateway v1.7+ Go 仿真器
├── docs/            接口、状态机与数据流契约
├── licenses/        第三方字体许可证
├── scripts/         构建 / 运行辅助脚本
└── gradle/          版本目录与 Gradle Wrapper
```

模块布局遵循 AGP 9 之后的 KMP 规范：`contract` 与 `shared` 使用
`com.android.kotlin.multiplatform.library` 插件提供 Android 目标，
`androidApp` 是独立的纯 Android 应用模块，`webApp` 只包含 `wasmJs` 目标。

## 技术栈与版本

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Kotlin | 2.4.21 | Android、Wasm 共用 |
| Compose Multiplatform | 1.10.0 | 1.12.x 要求 compileSdk 37 与 AGP 9.1+ |
| Compose Material3 | 1.9.0 | 对应 AndroidX Material3 1.4.0 |
| Android Gradle Plugin | 9.4.1 | 要求 Gradle 9.6.0+、Build Tools 36.0.0；compileSdk / targetSdk 35，minSdk 26 |
| Gradle | 9.6.1 | Wrapper 已包含 |
| JDK | 17 | 编译与 Gradle 运行 |
| Ktor | 3.6.1 | 仅客户端：Android 用 CIO，Web 用 Js/fetch |
| Go | 1.26+ | 服务端；GORM sqlite dialector 需要 CGO |
| Echo | 4.16.3 | Go 服务端 HTTP 路由、CORS、错误映射 |
| MQTT | `eclipse/paho.mqtt.golang` 1.5.1 | MQTT 3.1.1 客户端，QoS 1 与自动重连 |
| 持久化 | GORM 1.31.2 + Turso Cloud | `gorm.io/driver/sqlite` 通过 `Config{Conn}` 跑在纯 Go 的 Turso 驱动上 |
| 迁移 | goose 3.29.0 | 版本化 SQL 迁移，服务端启动时执行 |
| Kotlin/Wasm | 2.4.21 | 浏览器端使用 WasmGC |
| EMQX | 5.8.7 | 仅测试栈使用，可替换为任意 MQTT 3.1.1 Broker |

## 环境要求

- JDK 17
- Android SDK Platform 35 与 **Build Tools 36.0.0**
- Go 1.26+ 与 C 工具链（构建 `go-server` 时 CGO 需要）
- Docker（运行 Go 服务端镜像与 mock/EMQX 测试栈）
- 支持 WasmGC 的浏览器：Chrome/Edge 119+、Firefox 120+、Safari 18.2+
- Node 仅在构建/测试 Web 产物时需要，Gradle 会按需下载

```bash
cp local.properties.example local.properties
# 按实际 SDK 路径修改 local.properties

export JAVA_HOME="/path/to/jdk-17"  # 指向 JDK 17 安装目录
```

## 快速开始

### 1. 一条命令拉起 mock + EMQX

```bash
cd mock
docker compose -f docker-compose.yml -f docker-compose.emqx.yml up --build -d
docker compose -f docker-compose.yml -f docker-compose.emqx.yml logs -f mock
```

该栈包括：

- `omg315-emqx`：MQTT Broker，宿主机 `1883`，Dashboard `http://localhost:18083`
- `omg315-mock`：OpenMQTTGateway 仿真器，HTTP 控制端口 `8099`

### 2. 启动服务端（MQTT 模式）

服务端需要 Turso Cloud 数据库和 MQTT Broker：

```bash
cd go-server
TURSO_DATABASE_URL='libsql://<db>-<org>.turso.io' \
TURSO_AUTH_TOKEN='<token>' \
MQTT_HOST=127.0.0.1 MQTT_PORT=1883 \
CGO_ENABLED=1 go run .
```

健康检查：

```bash
curl -s http://127.0.0.1:8080/api/v1/health | jq
# gateway.state 应为 "online"
```

MQTT 与 Turso 都是必需配置：未设置 `MQTT_HOST` 或
`TURSO_DATABASE_URL` 时服务端拒绝启动，不提供无网关或无数据库的降级模式。
`DATABASE_URL` 可作为 `TURSO_DATABASE_URL` 的别名。

本地可用工作目录中的 `.env` 文件（在 `go-server/` 下运行时即 `go-server/.env`）：

```bash
cp go-server/.env.example go-server/.env
# 填入 MQTT_HOST、TURSO_DATABASE_URL、TURSO_AUTH_TOKEN、MQTT_USERNAME、MQTT_PASSWORD
(cd go-server && CGO_ENABLED=1 go run .)
```

服务端只读取工作目录中的 `.env`：存在则加载，缺失则跳过；进程环境变量
优先于文件值。不搜索父目录或 `server/.env`，也不支持 `CONFIG_FILE`。
`.env` 已被 gitignore，不要提交凭据。

EMQX Cloud 等托管 Broker 通常只开放 TLS 端口。需要 TLS 时使用 MQTT over
WebSocket Secure（`wss`），由 paho 的 WebSocket 客户端完成 TLS，不需要外部
脚本或本地转发进程。

```bash
# go-server/.env（凭据不要提交）
MQTT_HOST=<broker-host>
MQTT_PORT=8084
MQTT_TRANSPORT=websocket
MQTT_TLS=true
MQTT_WS_PATH=/mqtt
TURSO_DATABASE_URL=libsql://<db>-<org>.turso.io
TURSO_AUTH_TOKEN=<token>
```

EMQX 的 WSS 监听器默认使用 8084 端口和 `/mqtt` 路径。`MQTT_TLS=true`
只能与 `MQTT_TRANSPORT=websocket` 搭配；裸 TCP + TLS 会在启动时直接报错，
避免再次出现“连接成功但收不到 CONNACK”的情况。

#### 构建服务端镜像（Debian）

`go-server/Dockerfile` 使用多阶段构建：构建阶段为 `golang:1.26-bookworm`，
运行阶段基于 `debian:bookworm-slim`。GORM 的 sqlite dialector 导入
`mattn/go-sqlite3`，所以构建需要 CGO；生产数据库访问仍走纯 Go 的 Turso
驱动。

```bash
# 多架构镜像（linux/amd64 + linux/arm64）。多架构结果必须推送到 registry。
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -f go-server/Dockerfile \
  -t <registry>/esp32-control-server:latest \
  --push .

# 本地验证时可只构建当前架构并加载到 docker（--load 只支持单一平台）。
# amd64 主机把 linux/arm64 换成 linux/amd64。
docker buildx build \
  --platform linux/arm64 \
  -f go-server/Dockerfile \
  -t esp32-control-server:local \
  --load .

docker run --rm --name esp32-server \
  --network mock_default \
  -p 8080:8080 \
  -e MQTT_HOST=emqx -e MQTT_PORT=1883 \
  -e TURSO_DATABASE_URL='libsql://<db>-<org>.turso.io' \
  -e TURSO_AUTH_TOKEN='<token>' \
  esp32-control-server:local
```

- `docker buildx build --platform ...` 构建 `linux/amd64` 与 `linux/arm64`
  两个平台，最终合并为多架构 manifest。CGO 代码无法简单交叉编译，arm64
  阶段在 buildx/QEMU 下原生编译；多架构构建建议放在 amd64 CI 或远程 builder。
- 多架构 manifest 需要 `--push`；`--load` 只支持单一平台。若默认 builder
  使用 docker driver 且未启用 containerd image store，先创建容器 builder：

  ```bash
  docker buildx create --name esp32-builder --driver docker-container --use
  docker buildx inspect --bootstrap
  ```

- 构建需要网络：Go 模块在首次构建时下载。
- 数据库在 Turso Cloud，容器不需要挂载卷。容器以 UID 10001 的非 root 用户
  运行，工作目录 `/app` 只读即可。
- MQTT 与 Turso 凭据通过环境变量传入；不要把 `.env` 打进镜像。

#### 在 GitHub Actions 中发布

`.github/workflows/server-image.yml` 在 `go-server/**` 变更、手动触发时，
用同一份 Dockerfile 构建 `linux/amd64` + `linux/arm64` 镜像并推送到
GitHub Container Registry：

```
ghcr.io/ktkongtong/esp-ev1527-server:latest
ghcr.io/ktkongtong/esp-ev1527-server:<version>
```

PR 只构建、不推送。`<version>` 与 `/api/v1/health` 的 `version` 一致；
`latest` 只在默认分支（`main`）更新。工作流使用内置 `GITHUB_TOKEN` 登录
GHCR，不需要额外的 GitHub Packages 凭据。

### 3. 启动 Web 端

```bash
./scripts/serve-web.sh          # 构建 production 包并监听 8081
# 打开 http://127.0.0.1:8081/
```

或手动执行：

```bash
./gradlew :webApp:wasmJsBrowserDistribution
python3 -m http.server 8081 \
  --directory webApp/build/dist/wasmJs/productionExecutable
```

开发模式（Webpack dev server，热更新，端口 8081）：

```bash
./gradlew :webApp:wasmJsBrowserRun
```

Web 端默认请求 `http://<当前页面主机名>:8080`。从局域网访问时，服务端与
Web 需在同一台机器，或自行调整
[`Esp32Api.wasmJs.kt`](shared/src/wasmJsMain/kotlin/com/esp32/control/gui/api/Esp32Api.wasmJs.kt)。

### 4. 构建 / 安装 Android

```bash
./gradlew :androidApp:assembleDebug
# 产物：androidApp/build/outputs/apk/debug/androidApp-debug.apk

./gradlew :androidApp:installDebug   # 需要已连接的设备或模拟器
```

- 模拟器默认访问 `http://10.0.2.2:8080`（宿主机别名）。
- 真机调试请把
  [`Esp32Api.android.kt`](shared/src/androidMain/kotlin/com/esp32/control/gui/api/Esp32Api.android.kt)
  中的地址改成开发机局域网 IP。
- Manifest 已开启 `usesCleartextTraffic`，仅适合开发环境；正式发布请改为
  HTTPS 并收紧网络安全配置。

### 5. 一次构建全部产物

```bash
./scripts/build-all.sh
# 服务端二进制：go-server/build/esp32-control-server
```

运行已构建镜像时仍需传入 Turso 数据库与 MQTT 配置：

```bash
docker run --rm --name esp32-server \
  --network mock_default \
  -p 8080:8080 \
  -e MQTT_HOST=emqx -e MQTT_PORT=1883 \
  -e TURSO_DATABASE_URL='libsql://<db>-<org>.turso.io' \
  -e TURSO_AUTH_TOKEN='<token>' \
  esp32-control-server:local
```

## 学习新设备

1. 在 UI 点击“开始学习”，服务端创建会话并进入 `collecting_on`。
2. 用遥控器对网关按“开”键 3 次（每次间隔 ≥600ms）。也可以调用 mock：

   ```bash
   for i in 1 2 3; do
     curl -sS -X POST http://127.0.0.1:8099/mock/press-button \
       -H 'Content-Type: application/json' -d '{"code":1714739}'
     sleep 0.8
   done
   ```

   每次物理按键会连续产生多帧（间隔 ≤400ms），服务端把它们合并为一次
   `hit`；因此“按 3 次”不是“收到 3 帧”。
3. 候选达到 3 次后会话进入 `candidate_on_ready`，UI 显示候选码、帧数，
   点击“这是开键”确认。
4. 会话进入 `collecting_off`，重复步骤 2 并按关码：
   `{"code":1714740}`。
5. 接受关码候选后进入 `ready`。可先试发，再填写名称/位置并保存；
   服务端创建设备并登记两个码的全局归属。
6. 保存成功返回 `201`，客户端更新设备列表。

采集窗口为每个槽位 60s，候选确认 30s，`ready` 保存窗口 5min，
会话总时长 10min。超时、网关掉线、码冲突都会以明确的
`failureReason` / 错误码结束。

## HTTP API

Base URL：`/api/v1`。完整定义见
[`docs/api-contract-v1.md`](docs/api-contract-v1.md)。

| 方法 | 路径 | 成功 | 用途 |
| --- | --- | --- | --- |
| `GET` | `/` | `200` | 服务信息（纯文本） |
| `GET` | `/api/v1/health` | `200 HealthResponse` | 服务、网关、接收器状态 |
| `GET` | `/api/v1/devices` | `200 DeviceListResponse` | 设备列表 |
| `GET` | `/api/v1/devices/{id}` | `200 Device` | 单设备；已移除返回 `410` |
| `POST` | `/api/v1/devices/{id}/commands` | `202 CommandDispatched` | 发送开/关码 |
| `DELETE` | `/api/v1/devices/{id}` | `204` | 移除设备（幂等） |
| `POST` | `/api/v1/learning/sessions` | `201 LearningSession` | 开始学习 |
| `GET` | `/api/v1/learning/sessions/{id}` | `200 LearningSession` | 轮询学习状态 |
| `POST` | `.../candidates/{candidateId}/accept` | `200 LearningSession` | 确认当前槽位候选码 |
| `POST` | `.../rescan` | `200 LearningSession` | 放弃当前候选并重新采集 |
| `POST` | `.../test` | `202 LearningTestResult` | 试发已确认的码 |
| `POST` | `.../confirm` | `201 LearningConfirmation` | 命名并保存新设备 |
| `DELETE` | `/api/v1/learning/sessions/{id}` | `204` | 取消学习 |

控制示例：

```bash
curl -sS -X POST http://127.0.0.1:8080/api/v1/devices/$DEVICE_ID/commands \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 6f1d9c2a' \
  -d '{"action":"turn_on"}'
```

同一 `Idempotency-Key` 在 10 分钟内重试会返回同一 `commandId`，响应头包含
`Idempotent-Replayed: true`，且不会再次发布 MQTT。

错误统一为：

```json
{
  "error": {
    "code": "code_already_registered",
    "message": "RF code is already registered",
    "requestId": "req_...",
    "details": { "conflictDeviceId": "dev_..." }
  }
}
```

响应头 `X-Request-Id` 会回显客户端提供的值（1–128 字符），否则服务端生成；
所有 API 响应带 `Cache-Control: no-store`。

v1.2.0 不提供事件流。客户端按场景轮询快照：学习 1s、设备列表 30s、
健康状态 30s；写操作成功后立即刷新。命令发布后状态不再变化，无需轮询。

## 服务端环境变量

| 变量 | 默认值 / 要求 | 说明 |
| --- | --- | --- |
| `SERVER_HOST` | `0.0.0.0` | HTTP 监听地址 |
| `SERVER_PORT` | `8080` | HTTP 监听端口 |
| `MQTT_HOST` | 必需 | Broker 主机名；未设置时服务端拒绝启动 |
| `MQTT_PORT` | `1883` | Broker 端口 |
| `MQTT_USERNAME` | 空 | MQTT 用户名 |
| `MQTT_PASSWORD` | 空 | MQTT 密码 |
| `MQTT_CLIENT_ID` | `esp32-control-server_<random>` | 服务端 MQTT ClientID |
| `MQTT_TOPIC_PREFIX` | `home/OpenMQTTGateway` | 命令、LWT、版本、抓码的公共前缀 |
| `GATEWAY_CLIENT_ID` | `OpenMQTTGateway_ESP32_315M` | UI/诊断展示用网关 ClientID |
| `TURSO_DATABASE_URL` | 必需 | Turso Cloud 数据库 URL，例如 `libsql://<db>-<org>.turso.io` |
| `DATABASE_URL` | 空 | `TURSO_DATABASE_URL` 的别名；两者都设置时以前者为准 |
| `TURSO_AUTH_TOKEN` | 空 | Turso Cloud token；本地 sqld 可留空 |
| `LOG_LEVEL` | `debug` | 日志级别：`debug` / `info` / `warn` / `error`；`debug` 打印每条 MQTT 报文与 HTTP 请求 |

设备、命令、幂等 key 与 tombstone 持久化在 Turso Cloud（GORM）；schema 由
goose 版本化迁移维护，服务端启动时自动应用未执行的版本。网关在线
状态和学习会话是运行时状态：网关状态由 retained LWT 恢复，学习会话在重启
后丢失；命令发布后即终态，重启不会重发。生产部署仍需增加认证、多网关隔离
与备份策略。

## mock 控制接口

```bash
curl -s http://127.0.0.1:8099/mock/health
curl -s -X POST http://127.0.0.1:8099/mock/press-button \
  -H 'Content-Type: application/json' -d '{"code":1714739,"frames":3}'
curl -s -X POST http://127.0.0.1:8099/mock/kill   # 触发 LWT offline
curl -s -X POST http://127.0.0.1:8099/mock/boot   # 重新上线
```

RX 注入只有 `POST /mock/press-button`。CLI 仅保留 `kill`、`boot`、
`status`、`help`、`quit`；TX 只由 MQTT 下发指令触发。
详见 [`mock/README.md`](mock/README.md)。

## Material You 与字体

- **Android 12+（API 31+）**：通过 `dynamicLightColorScheme` /
  `dynamicDarkColorScheme` 从壁纸取色；API 26–30 使用内置品牌色板。
- **Web**：Compose/Wasm 无法读取 Android 动态色，固定使用内置绿色系
  Material 3 色板（浅色 / 深色跟随系统）。
- **中文字体**：Skiko 在 Wasm 环境下没有 CJK 回退字体，未嵌入字体会显示
  方框。`shared` 通过 Compose Resources 内置 `Noto Sans SC`，并在
  `Esp32Theme` 中应用。许可证见
  [`licenses/OFL-NotoSansSC.txt`](licenses/OFL-NotoSansSC.txt)。
- 使用 `FontFamily.Monospace` 的文本不要混入中文；设备卡片把“开码/关码”
  与数字拆成两个 `Text`。

## Web 兼容性说明

1. **需要 WasmGC**：不支持 WasmGC 的浏览器会白屏。最低建议 Chrome/Edge 119+、
   Firefox 120+、Safari 18.2+。
2. **必须通过 HTTP(S) 访问**，不能 `file://`。
3. **混合内容**：HTTPS 页面会拦截对 `http://host:8080` 的请求。生产环境应
   使用同域反向代理转发 `/api`，或为服务端配置 TLS。
4. **CORS**：开发期服务端使用 `anyHost()`；上线前请改为白名单域名。
5. **产物体积**：Web 产物约 19 MB（Wasm + Skiko + 字体），首次加载较慢。
   生产部署应启用 gzip/brotli 与长期缓存，字体可做子集化。

## 已验证内容（2026-11-07，Go + Turso 迁移）

本次迁移后实际执行：

- `gofmt -w .`、`go vet ./...`、`CGO_ENABLED=1 go test ./...`：
  `go-server` 65 个测试通过（`:memory:`，不访问 Turso）。
- 随后完成 `internal/` 分层重组与 Koanf 配置加载替换；同一命令复测
  81 个测试通过，配置优先级与校验行为不变。配置只读取工作目录 `.env`，
  缺失时跳过，环境变量优先。
- 本地 sqld（`http://127.0.0.1:18080`）联调：GORM `AutoMigrate` 成功，
  `GET /api/v1/health` 返回 `status=ok`，`GET /api/v1/devices` 返回空列表；
  `TURSO_AUTH_TOKEN` 在日志中显示为 `***`。
- `./gradlew :contract:testAndroidHostTest :shared:compileKotlinWasmJs
  :androidApp:assembleDebug :webApp:wasmJsBrowserDistribution` 构建成功。
- `go-server/Dockerfile` 本地构建成功；容器以非 root 用户运行，
  连接本地 sqld 后健康检查与设备列表正常。
- `mock/` 的 `go test ./...` 通过（21 个测试）。

未覆盖：真实 Turso Cloud 账号/网络、真实网关端到端、Android/Web UI 与
Go 服务端的联调。复用审计与库取舍见
[`docs/reuse-audit.md`](docs/reuse-audit.md)。

## 历史验证（2026-11-03，Ktor 服务端，已移除）

> 以下记录仅适用于已删除的 Ktor/Kotlin Native 服务端，保留用于追溯。

本次迁移后实际执行：

- `:contract:testAndroidHostTest` 通过。
- `:server:linkDebugExecutableLinuxArm64`、`:androidApp:assembleDebug`、
  `:webApp:wasmJsBrowserDistribution` 构建成功。
- `:server:linkDebugExecutableMacosArm64` 构建成功；`CONFIG_FILE` 配置加载
  与“环境变量优先”行为已验证。
- 通过 `wss://<broker>:8084/mqtt` 连接 EMQX Cloud（Curl 引擎，TLS）成功：
  MQTT 会话建立、订阅完成，凭据不出现在日志中。网关状态取决于真实网关
  是否发布 retained LWT。
- Room3 KSP 生成 `AppDatabase` 与 schema；ktor-mqtt 在 `linuxArm64` 完成链接。
- `mock/` 的 `go test ./...` 通过。

端到端（Docker `linuxArm64` 服务端 + Mosquitto 2.1.2 + `mock/`）：

- MQTT 5 服务端与 MQTT 3.1.1 网关经 Broker 互通；retained LWT/version
  使网关上线，`kill`/`boot` 正确切换 online/offline。
- 学习：开码 3 次 → `candidate_on_ready` → accept → 关码 3 次 → accept
  → `ready` → confirm 成功保存设备。
- 控制：`202 CommandDispatched`，不写设备状态；
  同一幂等 key 重放返回 `Idempotent-Replayed: true` 且不重复发布。
- 持久化：重启服务端后设备仍在；设备没有 online/state 字段，
  网关状态只由 retained LWT/version 决定。
- 学习会话：完成一轮学习并保存设备后重启服务端，会话 `GET` 返回
  `404 learning_session_not_found`，设备仍保留。
- Room 迁移：用 v1 数据库启动后升到 v2，`REPORTED`→`COMMANDED`、
  `CONFIRMED`→`DISPATCHED`，`confirmedAtEpochMs` 列删除；
  v2 → v3 删除 `learning_sessions` / `learning_candidates`；
  v3 → v4 删除设备状态/online/lastAction/revision 列与 `commands` 表，
  `user_version=4`。
- 删除：首次/重复 `204`，之后 `GET` 返回 `410 device_removed`；
  删除后的开/关码可被新学习会话重新捕获。
- Broker 停止时服务端进程存活并报告 `gateway.state=offline`；
  Broker 恢复后自动重连，网关回到 `online`。

以上为该历史实现的未覆盖项。当前实现的复用审计与库取舍见
[`docs/reuse-audit.md`](docs/reuse-audit.md)。

## 常见问题

**`JAVA_HOME is not set` / 找不到 Java**
设置 JDK 17 的 `JAVA_HOME`，或使用 Android Studio 自带 JDK。

**AGP 9 报 KMP 插件不兼容**
AGP 9 起不能再把 `com.android.application` / `com.android.library` 与
`org.jetbrains.kotlin.multiplatform` 应用到同一个模块。共享模块用
`com.android.kotlin.multiplatform.library`，Android 应用模块保持纯
`com.android.application`（内置 Kotlin）。

**找不到 Build Tools 36.0.0**
AGP 9.4 强制要求 Build Tools 36.0.0（即使 compileSdk 仍是 35）。
通过 SDK Manager 安装 `build-tools;36.0.0`。

**Gradle 报 `minCompileSdk 37`**
Compose Multiplatform 被升级到了 1.12.x。保持 1.10.0，或同步升级
compileSdk 37 与 AGP 9.1+。

**服务端启动即退出，提示 `MQTT_HOST is required` 或
`TURSO_DATABASE_URL is required`**
服务端没有无 MQTT 或无数据库的降级模式。设置 `MQTT_HOST`，并确保容器能
解析 Broker 主机名；使用本仓库 compose 栈时加入 `--network mock_default`
并设 `MQTT_HOST=emqx`。数据库必须设置 `TURSO_DATABASE_URL`（`DATABASE_URL`
是别名）；Turso Cloud 还需要 `TURSO_AUTH_TOKEN`。

**`go build` 报 `cgo: C compiler not found`**
GORM 的 `gorm.io/driver/sqlite` 导入 `mattn/go-sqlite3`，构建需要 CGO 与
C 工具链。本机安装 Xcode Command Line Tools 或 `build-essential`；构建时
设置 `CGO_ENABLED=1`。服务端镜像的 Dockerfile 已包含该配置。

**网关显示 offline**
检查 mock 容器日志与 EMQX 连接；mock 使用固定 ClientID
`OpenMQTTGateway_ESP32_315M`，同时启动两个实例会互相踢下线。

**学习一直 0/3**
315MHz 接收器是学习唯一信号来源。确认 mock `/mock/health` 正常、
服务端已连接 Broker、按键间隔 ≥600ms；同一物理按键的连续帧不会被
算作多次按键。

**Web 白屏 / 中文方框**
先确认浏览器支持 WasmGC、通过 HTTP 访问、`.wasm` / `.otf` 未被 404；
中文不要放进 `FontFamily.Monospace`。

**端口冲突**
服务端固定 8080，Web 静态服务器 8081，EMQX 1883/18083，mock 8099；
可在启动命令中调整，但客户端 API 地址默认仍指向 8080。
