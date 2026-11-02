# ESP32 智能控制台 · Kotlin 全栈脚手架

一个 Android + Web 共用 UI 的控制台示例：Kotlin/Native 服务端（Ktor），
Compose Multiplatform 共享界面，Android 端使用 Material You 动态取色。
当前设备数据是服务端内存中的演示数据，MQTT 尚未接入；仓库中的
[`mock/`](mock/README.md) 已提供可对接的 OpenMQTTGateway 仿真器。

## 目录结构

```
esp32/
├── server/          Ktor 3 + Kotlin/Native（linuxArm64 可执行文件）
├── shared/          KMP 共享库：Compose UI、主题、Ktor 客户端（Android + Wasm）
├── androidApp/      Android 应用壳（AGP 9 内置 Kotlin，Material You 动态色）
├── webApp/          Kotlin/Wasm Web 应用与 index.html
├── mock/            OpenMQTTGateway v1.7+ Go 模拟器（已有，未接入）
├── licenses/        第三方字体许可证
├── scripts/         构建 / 运行辅助脚本
└── gradle/          版本目录与 Gradle Wrapper
```

模块布局遵循 AGP 9 之后的 KMP 规范：`shared` 使用
`com.android.kotlin.multiplatform.library` 插件提供 Android 目标，
`androidApp` 是独立的纯 Android 应用模块（AGP 9 内置 Kotlin，不再在
应用模块上应用 KMP 插件），`webApp` 是只包含 `wasmJs` 目标的 KMP 模块。

## 技术栈与版本

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Kotlin | 2.4.21 | 服务端、Android、Wasm 共用 |
| Compose Multiplatform | 1.10.0 | 固定该版本；1.12.x 要求 compileSdk 37 与 AGP 9.1+ |
| Compose Material3 | 1.9.0 | 对应 AndroidX Material3 1.4.0 |
| Android Gradle Plugin | 9.4.1 | 要求 Gradle 9.6.0+、Build Tools 36.0.0；compileSdk / targetSdk 35，minSdk 26 |
| Gradle | 9.6.1 | Wrapper 已包含（AGP 9.4 最低要求 9.6.0） |
| JDK | 17 | 编译与 Gradle 运行 |
| Ktor | 3.6.1 | 服务端 CIO；客户端 Android 用 CIO，Web 用 Js/fetch |
| Kotlin/Wasm | 2.4.21 | 浏览器端使用 WasmGC |

> AGP 9 起 `com.android.application` / `com.android.library` 不能再与 KMP 插件
> 应用到同一模块。因此共享代码位于 `shared`（Android KMP library 插件），
> Android 入口位于独立的 `androidApp`（内置 Kotlin），Web 入口位于
> `webApp`（纯 `wasmJs` 目标）。
>
> 升级 Compose Multiplatform 前请先确认其 Android AAR 的 `minCompileSdk`
> 与 `minAndroidGradlePluginVersion`。1.12.x 需要 compileSdk 37 / AGP 9.1.0，
> 会直接导致当前工程构建失败。

## 环境要求

- JDK 17（例如 Homebrew 的 `openjdk@17`）
- Android SDK Platform 35 与 **Build Tools 36.0.0**（AGP 9.4 的强制最低版本）
- Docker（可选，用于运行 Linux/arm64 服务端）
- 支持 WasmGC 的浏览器：Chrome/Edge 119+、Firefox 120+、Safari 18.2+
- 首次构建会自动下载 Gradle 9.6.1、AGP 9.4.1、Compose 1.10.0；
  构建服务端时还会下载 Kotlin/Native 工具链与 Node（Web 构建）

```bash
cp local.properties.example local.properties
# 按实际 SDK 路径修改 local.properties

export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
```

## 快速开始

### 1. 启动服务端

服务端目标是 **linuxArm64**，可在 Apple Silicon Mac 上交叉编译后放进
Linux 容器运行（无需完整 Xcode）。

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :server:linkDebugExecutableLinuxArm64

docker run --rm -p 8080:8080 \
  -v "$PWD/server/build/bin/linuxArm64/debugExecutable:/app:ro" \
  debian:trixie-slim /app/esp32-control-server.kexe
```

运行后：

```bash
curl http://127.0.0.1:8080/api/health
curl http://127.0.0.1:8080/api/devices
curl -X POST http://127.0.0.1:8080/api/devices/aerator-1/toggle
```

服务端环境变量：

- `SERVER_HOST`，默认 `0.0.0.0`
- `SERVER_PORT`，默认 `8080`

> 当前 `kexe` 是动态链接的 Linux 二进制，已在 `debian:trixie-slim` 上验证。
> 若部署机是 x86_64，需要在 `server/build.gradle.kts` 中增加 `linuxX64` 目标
> 并重新编译。macOS 本机运行需安装完整 Xcode，然后使用
> `./gradlew :server:linkDebugExecutableMacosArm64 -PenableMacosServer=true`。

也可以使用脚本：`./scripts/run-server-docker.sh`。

### 2. 启动 Web 端

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

注意：

- 不能直接双击 `index.html` 用 `file://` 打开，Wasm 与资源加载需要 HTTP。
- Web 端 API 地址为 `http://<当前页面主机名>:8080`，因此从局域网访问时
  服务端与 Web 需在同一台机器（或自行调整 `Esp32Api.wasmJs.kt`）。

### 3. 构建 / 安装 Android

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

### 4. 一次构建全部产物

```bash
./scripts/build-all.sh
```

## HTTP API

> 服务端与客户端的共享契约见
> [`docs/api-contract-v1.md`](docs/api-contract-v1.md)。该文档定义 `/api/v1`
> 目标协议、网关/设备/命令/学习会话四个状态机，以及“控制开关、学习新设备、
> 移除旧设备”三条端到端数据流（含 MQTT 抓码、SSE 事件与轮询降级），
> 并说明共享 `contract` 模块与迁移步骤；当前代码仍提供未版本化的
> `/api` 接口。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/` | 服务信息 |
| GET | `/api/health` | 健康状态、运行时、版本、运行时长 |
| GET | `/api/devices` | 设备列表 |
| GET | `/api/devices/{id}` | 单个设备 |
| POST | `/api/devices/{id}/toggle` | 切换开关并返回更新后的设备 |

演示设备：

- `aerator-1`：1 号增氧机，开码 1714739 / 关码 1714740
- `aerator-2`：2 号增氧机，开码 1714741 / 关码 1714742

## Material You 与字体

- **Android 12+（API 31+）**：通过 `dynamicLightColorScheme` /
  `dynamicDarkColorScheme` 从壁纸取色；API 26–30 使用内置品牌色板。
- **Web**：Compose/Wasm 无法读取 Android 动态色，固定使用内置的绿色系
  Material 3 色板（浅色 / 深色跟随系统）。
- 形状、字号、组件均来自 Material 3。
- **中文字体**：Skiko 在 Wasm 环境下没有 CJK 回退字体，未嵌入字体会显示
  方框（tofu）。因此 `shared` 通过 Compose Resources 内置了
  `Noto Sans SC`（约 7.9 MB），并在 `Esp32Theme` 中应用到全部
  Material Typography。许可证见
  [`licenses/OFL-NotoSansSC.txt`](licenses/OFL-NotoSansSC.txt)。
- 使用 `FontFamily.Monospace` 的文本不要混入中文：Web 端 Monospace 没有
  CJK 回退。设备卡片因此把“开码/关码”和数字拆成两个 `Text`。

## Web 兼容性说明

1. **需要 WasmGC**：不支持 WasmGC 的浏览器（旧版 Safari、部分 iOS 内嵌
   WebView）会白屏。最低建议 Chrome/Edge 119+、Firefox 120+、Safari 18.2+。
2. **必须通过 HTTP(S) 访问**，不能 `file://`。
3. **混合内容**：如果 Web 页面部署在 HTTPS 下，浏览器会拦截对
   `http://host:8080` 的请求。生产环境应使用同域反向代理将 `/api` 转发到
   Kotlin/Native 服务端，或为服务端配置 TLS。
4. **CORS**：开发期服务端使用 `anyHost()` 并允许 GET/POST/OPTIONS 与常用
   请求头。上线前请改为白名单域名。
5. **产物体积**：Web 产物约 19 MB（应用 Wasm 2.45 MB + Skiko 8.25 MB +
   JS 525 KB + 字体 7.9 MB），首次加载较慢。生产部署应启用 gzip/brotli 与
   长期缓存；字体可进一步做子集化。
6. **字体资源路径**：`composeResources/` 由 Compose Resources 运行时按相对
   路径加载，Web 服务器需正确提供 `.otf` / `.wasm` 文件（`python3 -m
   http.server` 已验证）。若部署在子路径下，请保持 `index.html` 与资源目录
   的相对结构。

## 与 MQTT / mock 的衔接

当前服务端与 UI 都是内存演示数据，页脚也明确标注了这一点。下一阶段的接线方式：

1. 服务端用 MQTT 客户端连接 Broker（例如 EMQX）。
2. 将 `POST /api/devices/{id}/toggle` 映射为发布
   `home/OpenMQTTGateway/commands/MQTTto315`，payload 形如
   `{"value":1714739,"protocol":1,"length":24,"repeat":5}`。
3. 订阅 `home/OpenMQTTGateway/315toMQTT` 与 LWT 主题，把真实状态回写给 UI。
4. 可先用 [`mock/`](mock/README.md) 中的 Go 仿真器联调：
   `on 1` 发射 1714739，`off 1` 发射 1714740。

## 已验证内容（2026-11-03）

- Gradle 9.6.1 + AGP 9.4.1 + Compose Multiplatform 1.10.0 组合可完成
  `shared`、`androidApp`、`webApp` 的完整构建。
- `:androidApp:assembleDebug` 构建成功（debug APK 约 20 MB，含内置字体），
  APK 内包含 `assets/composeResources/.../noto_sans_sc_regular.otf`。
- `:webApp:wasmJsBrowserDistribution` 构建成功；无头 Chrome（SwiftShader）
  渲染正常，中文显示正常，能读取 API 数据，并可在页面内点击开关完成
  `POST /api/devices/{id}/toggle`。
- 服务端源码未随本次升级改动；现有 13.6 MB linuxArm64 二进制在
  Docker `debian:trixie-slim` 中通过健康检查、设备列表、切换接口与 CORS
  预检。`:server` 在 Gradle 9.6.1 下可正常配置。

## 常见问题

**`JAVA_HOME is not set` / 找不到 Java**
设置 JDK 17 的 `JAVA_HOME`，或使用 Android Studio 自带 JDK。

**AGP 9 报 KMP 插件不兼容**
AGP 9 起不能再把 `com.android.application` / `com.android.library` 与
`org.jetbrains.kotlin.multiplatform` 应用到同一个模块。共享模块用
`com.android.kotlin.multiplatform.library`（`kotlin { androidLibrary {} }`），
Android 应用模块保持纯 `com.android.application`（内置 Kotlin）。

**找不到 Build Tools 36.0.0**
AGP 9.4 强制要求 Build Tools 36.0.0（即使 compileSdk 仍是 35）。
通过 SDK Manager 安装 `build-tools;36.0.0`。

**Kotlin/Native macOS 目标构建失败**
本机只有 Command Line Tools 时无法链接 macOS 目标，请使用 linuxArm64 +
Docker 流程，或安装完整 Xcode 后加 `-PenableMacosServer=true`。

**Gradle 报 `minCompileSdk 37`**
Compose Multiplatform 被升级到了 1.12.x。保持 1.10.0，或同步升级
compileSdk 37 与 AGP 9.1+。

**Web 白屏**
先用浏览器控制台确认是否支持 WasmGC、是否通过 HTTP 访问、是否 404 了
`.wasm` / `.otf`。旧版浏览器不会给出友好提示。

**Web 中文显示为方框**
确认 `shared/src/commonMain/composeResources/font/` 中字体存在、
`Esp32Theme` 已应用字体，且不要把中文放进 `FontFamily.Monospace`。

**端口冲突**
服务端固定 8080，Web 开发/静态服务器使用 8081；可在
`webApp/build.gradle.kts` 与启动命令中调整，但 `Esp32Api` 默认仍指向 8080。
