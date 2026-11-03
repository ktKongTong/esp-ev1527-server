# ESP32 智能控制台 · Kotlin 全栈

Android + Web 共用一套 Compose UI，Kotlin/Native 服务端通过 MQTT 驱动
OpenMQTTGateway 315MHz 网关，实现**控制开关、学习新设备、移除旧设备**。
服务端、Android、Web 三端共享 `contract` 模块中的 HTTP/SSE 协议类型，
避免各端维护同名模型。

当前仓库已实现契约 v1.1.0；协议与状态机的完整定义见
[`docs/api-contract-v1.md`](docs/api-contract-v1.md)。仓库内的
[`mock/`](mock/README.md) 是可直接联调的 OpenMQTTGateway v1.7+ 仿真器。

## 已实现能力

- **控制开关**：显式 `turn_on` / `turn_off` 命令，`Idempotency-Key` 保证重试只发布一次；
  命令依次经历 `dispatched → confirmed / failed / cancelled`。
- **学习新设备**：客户端发起会话 → 服务端关联 60s 采集窗口 → 消费网关持续上报的
  315MHz 抓码 → 按按键间隔聚类 → 同一码被独立按 3 次生成候选 → 客户端确认开/关键
  → 再学另一个键 → 试发 → 命名保存。没有网关抓码就不会产生设备。
- **移除设备**：立即拒绝新命令、取消未下发命令、释放开/关码、写入 tombstone；
  重复删除保持幂等。
- **实时同步**：SSE 推送网关、设备、命令、学习会话变化，支持 `Last-Event-ID`
  重放；客户端同时保留轮询降级。
- **完整错误契约**：统一错误信封、请求 ID、`410` tombstone、`409` 状态冲突、
  `422` 码对不合法、`415` 媒体类型错误、`400` 非法 JSON。

## 架构

```
┌──────────┐   HTTP/JSON/SSE   ┌──────────────┐   MQTT 3.1.1   ┌───────────┐  315MHz  ┌────────┐
│ Android  │ ◄───────────────► │    Server    │ ◄────────────► │  Gateway  │ ◄──────► │ Remote │
│   Web    │                   │ Kotlin/Native│                │   ESP32   │          │ Button │
└──────────┘                   └──────────────┘                └───────────┘          └────────┘
      │                               │
      └──────── contract ─────────────┘
```

- 服务端是设备、命令、学习会话状态的唯一权威。
- 网关负责 MQTT 桥接与 315MHz 收发；服务端订阅主题前缀下的全部消息，
  按 payload 分类处理 LWT、版本与 RF 抓码，不依赖硬编码的抓码子主题。
- 315MHz 是单向链路：命令 `202` 只代表已发布；只有网关回显匹配的 RF 码，
  命令才会变成 `confirmed`。

## 目录结构

```
esp32/
├── contract/        KMP 共享协议：DTO、SSE 事件、ContractJson、枚举
├── server/          Ktor 3 + Kotlin/Native（linuxArm64）+ KMQTT 适配层
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
| Kotlin | 2.4.21 | 服务端、Android、Wasm 共用 |
| Compose Multiplatform | 1.10.0 | 1.12.x 要求 compileSdk 37 与 AGP 9.1+ |
| Compose Material3 | 1.9.0 | 对应 AndroidX Material3 1.4.0 |
| Android Gradle Plugin | 9.4.1 | 要求 Gradle 9.6.0+、Build Tools 36.0.0；compileSdk / targetSdk 35，minSdk 26 |
| Gradle | 9.6.1 | Wrapper 已包含 |
| JDK | 17 | 编译与 Gradle 运行 |
| Ktor | 3.6.1 | 服务端 CIO；客户端 Android 用 CIO，Web 用 Js/fetch |
| MQTT | KMQTT 1.0.0 | MIT；MQTT 3.1.1 / QoS 1 / Kotlin-Native `linuxArm64`，适配层负责重连 |
| SSE | Ktor 3.6.1 `server-sse` | 服务端事件流、心跳与 `Last-Event-ID` 补发 |
| Kotlin/Wasm | 2.4.21 | 浏览器端使用 WasmGC |
| EMQX | 5.8.7 | 仅测试栈使用，可替换为任意 MQTT 3.1.1 Broker |

## 环境要求

- JDK 17
- Android SDK Platform 35 与 **Build Tools 36.0.0**
- Docker（运行 Linux/arm64 服务端与 mock/EMQX 测试栈）
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

```bash
export JAVA_HOME="/path/to/jdk-17"  # 指向 JDK 17 安装目录
./gradlew :server:linkDebugExecutableLinuxArm64

docker run --rm --name esp32-server \
  --platform linux/arm64 \
  --network mock_default \
  -p 8080:8080 \
  -e MQTT_HOST=emqx -e MQTT_PORT=1883 \
  -e SERVER_HOST=0.0.0.0 \
  -v "$PWD/server/build/bin/linuxArm64/debugExecutable/esp32-control-server.kexe:/app/server:ro" \
  debian:bookworm-slim /app/server
```

健康检查：

```bash
curl -s http://127.0.0.1:8080/api/v1/health | jq
# dataSource 应为 "mqtt"，gateway.state 应为 "online"
```

不传 `MQTT_HOST` 时服务端进入 `in-memory-demo` 模式：接口和 UI 可用，但没有
网关与 RF 捕获，因此无法完成学习。

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
```

```bash
./scripts/run-server-docker.sh   # 不带 MQTT 的 in-memory-demo 服务端
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
6. 保存成功返回 `201`，SSE 推送 `device.added`、`learning.updated`。

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
| `POST` | `/api/v1/devices/{id}/commands` | `202 CommandAccepted` | 控制开关 |
| `GET` | `/api/v1/commands/{id}` | `200 CommandRecord` | 查询命令状态 |
| `DELETE` | `/api/v1/devices/{id}` | `204` | 移除设备（幂等） |
| `POST` | `/api/v1/learning/sessions` | `201 LearningSession` | 开始学习 |
| `GET` | `/api/v1/learning/sessions/{id}` | `200 LearningSession` | 轮询学习状态 |
| `POST` | `.../candidates/{candidateId}/accept` | `200 LearningSession` | 确认当前槽位候选码 |
| `POST` | `.../rescan` | `200 LearningSession` | 放弃当前候选并重新采集 |
| `POST` | `.../test` | `202 LearningTestResult` | 试发已确认的码 |
| `POST` | `.../confirm` | `201 LearningConfirmation` | 命名并保存新设备 |
| `DELETE` | `/api/v1/learning/sessions/{id}` | `204` | 取消学习 |
| `GET` | `/api/v1/events` | `200 text/event-stream` | SSE 事件流 |

控制示例：

```bash
curl -sS -X POST http://127.0.0.1:8080/api/v1/devices/$DEVICE_ID/commands \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 6f1d9c2a' \
  -d '{"action":"turn_on"}'
```

同一 `Idempotency-Key` 在 24h 内重试会返回同一 `commandId`，响应头包含
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
非 SSE 响应带 `Cache-Control: no-store`。

SSE 事件类型：`gateway.updated`、`device.added`、`device.updated`、
`device.removed`、`command.updated`、`learning.updated`、`stream.reset`。
断线重连时携带 `Last-Event-ID` 可补发缓冲区内的事件；缓冲不足会收到
`stream.reset`，客户端应重新拉取快照。

## 服务端环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_HOST` | `0.0.0.0` | HTTP 监听地址 |
| `SERVER_PORT` | `8080` | HTTP 监听端口 |
| `MQTT_HOST` | 未设置 | 设置后启用 MQTT 模式；否则为 `in-memory-demo` |
| `MQTT_PORT` | `1883` | Broker 端口 |
| `MQTT_USERNAME` | 空 | MQTT 用户名 |
| `MQTT_PASSWORD` | 空 | MQTT 密码 |
| `MQTT_TOPIC_PREFIX` | `home/OpenMQTTGateway` | 命令、LWT、版本、抓码的公共前缀 |
| `GATEWAY_CLIENT_ID` | `OpenMQTTGateway_ESP32_315M` | UI/诊断展示用网关 ClientID |

服务端内部为内存存储：重启会丢失学习中的会话与已创建设备。生产部署需要
持久化设备/码归属/幂等记录，并增加认证与多网关隔离。

## mock 控制接口

```bash
curl -s http://127.0.0.1:8099/mock/health
curl -s -X POST http://127.0.0.1:8099/mock/press-button \
  -H 'Content-Type: application/json' -d '{"code":1714739,"frames":3}'
curl -s -X POST http://127.0.0.1:8099/mock/transmit \
  -H 'Content-Type: application/json' -d '{"code":1714739,"repeat":5}'
curl -s -X POST http://127.0.0.1:8099/mock/kill   # 触发 LWT offline
curl -s -X POST http://127.0.0.1:8099/mock/boot   # 重新上线
```

CLI：`on 1`、`off 1`、`rand`、`press <code>`、`burst <code> [frames]`、
`send <code> [repeat]`、`kill`、`boot`、`status`。
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

## 已验证内容（2026-11-03）

- `:contract:testAndroidHostTest` 通过，覆盖设备/命令/学习会话/错误信封的
  JSON 形状与 SSE `type` 判别字段。
- `:server:linkDebugExecutableLinuxArm64`、`:androidApp:assembleDebug`、
  `:webApp:wasmJsBrowserDistribution` 构建成功。
- mock + EMQX + Linux 服务端端到端：
  - 学习 3 次开码 → 接受 → 3 次关码 → 接受 → `ready` → 试发 → 保存设备；
  - 控制命令 `202`、幂等重放不重复发布、RF 回显 2s 内确认；
  - 删除返回 `204`，重复删除 `204`，之后 `GET` 返回 `410`；
  - 码冲突返回 `409 code_already_registered`；
  - 零捕获/命中不足/候选超时分别以 `no_signal`、`insufficient_hits`、
    `candidate_timeout` 过期；
  - 网关 `kill` 后控制失败为 `gateway_offline`，`boot` 后恢复。
- 错误映射：非法 JSON → `400 malformed_json`，错误 `Content-Type` →
  `415 unsupported_media_type`，且 `X-Request-Id` / `Cache-Control` 不重复。
- 无头 Chrome（SwiftShader）中 Wasm UI 可渲染中文、读取 API、开始学习、
  显示采集进度与候选码。
- Broker 未启动或重启 EMQX 时服务端进程不退出，KMQTT 适配层按退避自动
  重连，Broker 上线后网关状态恢复为 `online`；SSE 的心跳、事件 `id`/`event` 字段、`Last-Event-ID` 补发和未知
  ID 的 `stream.reset` 均已验证。
- 复用审计与 MQTT 库取舍见 [`docs/reuse-audit.md`](docs/reuse-audit.md)。

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

**Kotlin/Native macOS 目标构建失败**
本机只有 Command Line Tools 时无法链接 macOS 目标，请使用 linuxArm64 +
Docker 流程，或安装完整 Xcode 后加 `-PenableMacosServer=true`。

**Gradle 报 `minCompileSdk 37`**
Compose Multiplatform 被升级到了 1.12.x。保持 1.10.0，或同步升级
compileSdk 37 与 AGP 9.1+。

**服务端 `dataSource` 一直是 `in-memory-demo`**
启动容器时没有设置 `MQTT_HOST`。设置后容器必须能解析 Broker 主机名；
使用本仓库 compose 栈时加入 `--network mock_default` 并设 `MQTT_HOST=emqx`。

**网关显示 offline**
检查 mock 容器日志与 EMQX 连接；mock 使用固定 ClientID
`OpenMQTTGateway_ESP32_315M`，同时启动两个实例会互相踢下线。

**学习一直 0/3**
315MHz 接收器是学习唯一信号来源。确认 mock `/mock/health` 正常、
服务端 `dataSource=mqtt`、按键间隔 ≥600ms；同一物理按键的连续帧不会被
算作多次按键。

**Web 白屏 / 中文方框**
先确认浏览器支持 WasmGC、通过 HTTP 访问、`.wasm` / `.otf` 未被 404；
中文不要放进 `FontFamily.Monospace`。

**端口冲突**
服务端固定 8080，Web 静态服务器 8081，EMQX 1883/18083，mock 8099；
可在启动命令中调整，但客户端 API 地址默认仍指向 8080。
