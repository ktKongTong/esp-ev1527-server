# AGENTS.md

本文件约束本仓库内的所有代码改动。核心原则：**默认能力已经存在，先找，再写。**
只有走完下面的复用顺序且都不满足时，才允许新增实现。

## 1. 先复用，再实现

按以下顺序搜索并记录结论，全部走完才能写新代码：

1. **项目内已有实现**：同包/同层的函数、类型、模块、工具、配置封装。
2. **项目内已有依赖**：`gradle/libs.versions.toml`、各模块 `build.gradle.kts`、
   `mock/go.mod` 及 lockfile 中已经引入的库是否具备该能力。
3. **标准库与运行时内置能力**：Kotlin 标准库、kotlinx.coroutines、
   kotlinx.serialization、Ktor、Compose、Go 标准库等。
4. **成熟第三方库**：通过第 2.1 节的验收条件后再引入。

### 1.1 本项目的搜索入口

```bash
# 项目内先搜能力关键词、函数名、类型名
rg -n "关键词|capability" contract server shared androidApp webApp mock

# 检查已有依赖，不要先改 build 文件
rg -n "所需能力|库名" gradle/libs.versions.toml
rg -n "implementation|api|alias" --glob '*.gradle.kts' .
rg -n "require|replace" mock/go.mod
```

### 1.2 各模块的既有能力

| 能力 | 既有位置 | 复用要求 |
| --- | --- | --- |
| 跨端 DTO、枚举、SSE 事件、错误信封 | `contract/` | 新增协议类型只放这里，禁止在 `server`、`shared` 各写一份 |
| JSON 配置 | `ContractJson` | 统一使用，不新建第二套 `Json {}` |
| 设备/命令/学习状态机 | `server/.../ControlPlane.kt` | 扩展现有领域逻辑，不在路由或客户端复制状态机 |
| MQTT 连接与发布 | `server/.../MqttClient.kt`（KMQTT 1.0.0 薄适配层） | 扩展适配层；MQTT 协议、keepalive、QoS 1、订阅由 KMQTT 负责，禁止再写第二套 MQTT 客户端 |
| SSE 传输 | Ktor 3.6.1 `server-sse` + `server/.../SseHub.kt` | 用 `sse()` / `heartbeat()` / `ServerSentEvent`；`SseHub` 只保留重放与 `stream.reset` 领域策略 |
| HTTP/SSE 客户端 | `shared/.../api/Esp32Api.kt` | 扩展现有 API 适配层，UI 不直接拼 URL |
| 网关/设备/学习 UI | `shared/.../App.kt` | 复用现有页面、轮询与错误文案 |
| OpenMQTTGateway 仿真 | `mock/` | 复用现有 CLI/HTTP 控制与 paho 客户端 |

### 1.3 已确定的复用决策

- **MQTT**：使用 MIT 许可的 `io.github.davidepianca98:kmqtt-client:1.0.0`，
  支持 Kotlin/Native `linuxArm64`、MQTT 3.1.1、QoS 1、订阅与 keepalive。
  `MqttClient.kt` 只保留 KMQTT 1.0.0 未提供的行为：断线重连、Broker 断开时
  异常隔离、主题分类和 `ByteArray` ↔ `UByteArray` 边界转换。
- **未采用 DitchOoM MQTT**：其 Linux 实现依赖 io_uring；Docker 默认 seccomp
  返回 `Operation not permitted`，运行即崩溃。放宽容器 seccomp 的代价高于收益。
- **未采用 Meshtastic MQTT**：许可证为 GPL-3.0，与仓库分发约束不匹配。
- **未采用 ukemp/ktor-mqtt**：没有 `linuxArm64` 目标。
- **SSE**：传输使用 Ktor `server-sse`；`SseHub` 仅保留 256 条重放缓存、
  `Last-Event-ID` 补发和 `stream.reset` 这些契约要求。
- **时间**：使用 Kotlin 标准库 `kotlin.time.Clock`，不直接调用 POSIX 时间 API。
- **JSON / HTTP / CORS / 错误映射**：分别复用 `ContractJson`、Ktor 客户端、
  Ktor CORS 与 StatusPages；不手写协议帧或第二套 JSON 配置。

完整逐项审阅记录见 [`docs/reuse-audit.md`](docs/reuse-audit.md)。

### 1.4 复用判定清单

写新代码前逐条回答；任一为“是”则必须复用，或先重构再复用：

- [ ] 同名/近义能力是否已在项目内存在（包括只是位置不对、命名不对）？
- [ ] 现有实现是否只是缺少一层封装或参数，而不是缺少能力？
- [ ] 现有依赖能否直接满足，且不改变其语义？
- [ ] 是否只是“我更熟悉另一种写法”？
- [ ] 跨端能力是否已经在 `contract` 中定义，只是客户端/服务端没有接上？

## 2. 依赖与平台约束

- 新依赖统一登记在 `gradle/libs.versions.toml`，模块中通过 `libs.*` 引用。
- `commonMain` 依赖必须同时支持 Android、`linuxArm64`、`wasmJs`；
  仅 Android/Web 可用的库放到对应 source set。
- 不把 Ktor、Compose、MQTT 或 UI 依赖放进 `contract`。
- `contract` 的 `commonMain` 只依赖序列化库，保持可被服务端和客户端共同消费。
- Web 端注意 WasmGC、包体积与浏览器兼容性；不要引入 JVM-only 依赖。
- Android 端保持 minSdk 26 / compileSdk 35 的现有约束。
- `mock` 是独立 Go 模块，优先使用标准库和 `go.mod` 中已有依赖。

### 2.1 引入/复用库的验收条件

- 维护活跃：近期有提交或发布，issue 有响应。
- 运行时代价可接受：Wasm 包体积、启动开销、原生依赖、平台限制。
- 有类型定义或可靠类型来源（Kotlin 类型、KMP 支持）。
- 与现有技术栈和目标平台匹配，不与既有抽象冲突。
- 可替换性：通过薄适配层隔离，核心逻辑不直接绑死库的工作 API。
- 不需要 fork 后才能用；若必须 fork，视为自研，并按第 4 节记录维护责任。

## 3. 不为复用而引入依赖

- 单函数级能力（几行可读代码）不引入依赖。
- 不为“少写胶水代码”引入大框架。
- 不引入与项目边界不匹配的库；依赖成本高于实现成本时，自研或内联实现。
- 不允许用“复制粘贴第三方实现”代替依赖引入或自研判断。

## 4. 自研的正当理由与偏离记录

以下理由必须显式写下，否则视为违规：

- 依赖成本、许可证、运行时负担或平台限制不合适。
- 现有实现语义不匹配，且改造它会破坏既有调用方。
- 需要可控的错误处理、性能或安全边界，外部库无法提供。
- 领域逻辑本身是产品差异点（例如本项目的学习状态机）。

偏离记录写在 PR 描述或改动附近的注释中；注释解释“为什么不复用”，不写工作日志：

```text
Reused: <existing module / dependency> (<path or version>)
Not reused because: <concrete constraint: bundle size / license / platform / semantics>
New implementation scope: <what is genuinely new>
```

## 5. 禁止的替代行为

- **平行实现**：同一能力出现第二份实现而不合并、不删除旧的。
- **隐式 fork**：复制代码进仓库但不标注来源与维护归属。
- **为复用而滥用抽象**：新增不必要的间接层来“统一”两个本不该统一的实现。
- 在 UI 中重复服务端状态机、在客户端重复校验协议、在 `shared` 重复
  `contract` 类型。

## 6. Linear 同步

Linear 项目是仓库文档与任务状态的对外镜像；本地文件是内容源。

- 团队：`ktlab`
- 项目：`ESP32 Control · 接口、状态机与数据流契约`
- 项目 URL：<https://linear.app/ktlab/project/esp32-control-接口状态机与数据流契约-2a9c6ad00148>
- 文档映射（本地文件 ↔ Linear 文档；尚未创建时按标题创建）：
  - `docs/api-contract-v1.md` ↔ [ESP32 Control 接口、状态机与数据流契约 v1](https://linear.app/ktlab/document/esp32-control-接口状态机与数据流契约-v1-267e7f96d14d)
  - `docs/reuse-audit.md` ↔ [ESP32 Control 复用审计（2026-11-03）](https://linear.app/ktlab/document/esp32-control-复用审计2026-11-03-8273fdddb293)

规则：

- 新增或修改 `docs/` 下的契约、状态机、数据流、架构决策、复用审计等文档时，
  必须在同一改动中创建或更新对应的 Linear 文档。
- 新增、关闭本地 issue/task/待办，或完成一个可交付变更时，必须同步创建或
  更新 Linear issue，写清状态、验收结果和对应 commit。
- Linear 文档或 issue 中应包含本地文件路径或 commit hash，便于双向定位。
- 本地内容与 Linear 冲突时以本地仓库为准：先修正本地，再同步 Linear。
- 不得把 secrets、token、`.env`、`local.properties`、密钥、抓码主题等敏感
  内容写入 Linear。
- Linear 暂时不可用时，在 PR/commit 说明中记录阻塞，恢复后补同步；不能用
  “本地已完成”代替 Linear 更新。

## 7. 提交前检查

- [ ] 已按第 1 节顺序搜索完，并记录结论。
- [ ] 未新增与既有实现重复的能力；如有重复，已合并或已删除旧实现。
- [ ] 新增依赖已通过第 2.1 节验收，并记录为何不自研。
- [ ] 自研部分已通过第 4 节校验，理由已写入。
- [ ] 复用处的适配层存在，核心逻辑未直接耦合库内部 API。
- [ ] `contract` 仍是协议的唯一来源，服务端与客户端没有重复 DTO。
- [ ] 本次涉及的文档与 issue 已同步到 Linear，并附上 Linear 链接。
- [ ] 构建与测试通过：

```bash
./gradlew :contract:testAndroidHostTest \
  :server:linkDebugExecutableLinuxArm64 \
  :shared:compileKotlinWasmJs \
  :androidApp:assembleDebug \
  :webApp:wasmJsBrowserDistribution

cd mock && go test ./...
```
