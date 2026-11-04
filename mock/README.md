# OpenMQTTGateway 315MHz 高保真模拟器（Go）

在 ESP32 + 315MHz 硬件到货前，用纯软件模拟一个真实的 OpenMQTTGateway v1.7+ 节点，
让后端、Compose/前端、自动化测试都可以按最终 MQTT 契约联调。

- 固定的 ClientID、LWT、版本、命令订阅、抓码上报主题
- 双向射频仿真：RX 支持单帧/多帧按键，TX 模拟 ESP32 发射且不回显
- 控制台 CLI：`on 1` / `off 1` / `rand` / `press` / `burst` / `send` / `kill` / `boot`
- 轻量 HTTP 控制接口：`POST /mock/press-button`（RX）、`POST /mock/transmit`（TX）
- `kill` 不发送 MQTT `DISCONNECT`，直接切断 TCP，真实触发 Broker 的遗嘱消息
- Broker 断线自动重连；`kill` 后保持“断电”状态，`boot` 后重新上线
- 单文件二进制，无 CGO，附带 Dockerfile / Compose / 单元测试 / 冒烟测试

---

## 1. 协议契约

默认主题前缀：`home/OpenMQTTGateway`（可用 `-topic-prefix` / `OMG_TOPIC_PREFIX` 覆盖）。

| 方向 | Topic | Payload | QoS / Retain |
|---|---|---|---|
| Broker → 网关 | `home/OpenMQTTGateway/commands/MQTTto315` | `{"value":1714739,"protocol":1,"length":24,"repeat":5}` | 订阅 QoS 1 |
| 网关注册遗嘱 | `home/OpenMQTTGateway/LWT` | `offline` | QoS 1 / retain |
| 网关上线上广播 | `home/OpenMQTTGateway/LWT` | `online` | QoS 1 / retain |
| 网关版本 | `home/OpenMQTTGateway/version` | `v1.7.0` | QoS 1 / retain |
| 网关 → Broker | `home/OpenMQTTGateway/315toMQTT` | `{"value":1714739,"protocol":1,"length":24,"delay":330}` | QoS 1 / 不 retain |

- **ClientID**：`OpenMQTTGateway_ESP32_315M`
- 默认 Broker：`tcp://localhost:1883`
- MQTT 协议：3.1.1（paho 默认，支持 3.1 回退）
- 启动顺序：连接 → 订阅命令主题 → 发布 retained `version` → 发布 retained `online`
- 收到发射指令时只打印控制台日志，**不会**把该指令回显到 `315toMQTT`（符合真实网关行为）
- 只有 `on 1` / `off 1` / `rand` / `press` / `burst` / HTTP 按键才会产生
  `315toMQTT` 抓码事件；`send` 只模拟 ESP32 发射

### ⚠️ 关于示例中的 HEX

任务描述里 `Value=1714739` 对应的 `HEX: 0x1A2B33` 与十进制不一致：

- `1714739 = 0x1A2A33`
- `0x1A2B33 = 1714995`

模拟器按数学正确值输出 `0x1A2A33`，其余格式与要求完全一致：

```text
[315M TX] 模拟天线发射: Value=1714739 (HEX: 0x1A2A33), 重放=5次
```

如果你的验收脚本硬编码了 `0x1A2B33`，请同步修正脚本；或自行修改 `payload.go` 中的 `formatHex`。

---

## 2. 快速启动

### 方案 A：Docker，连接本机已有的 EMQX（推荐）

要求本机已有 EMQX/Mosquitto 监听 `1883`。

```bash
cd mock
docker compose up --build -d
docker compose logs -f mock
```

在另一个终端打开 CLI：

```bash
docker attach omg315-mock
# 退出 attach 但不停止容器：Ctrl-P Ctrl-Q
```

默认通过 `host.docker.internal:1883` 连接宿主机 Broker。

### 方案 B：Docker 一键拉起独立 EMQX + Mock

```bash
cd mock
docker compose -f docker-compose.yml -f docker-compose.emqx.yml up --build -d
docker compose -f docker-compose.yml -f docker-compose.emqx.yml logs -f mock
```

EMQX Dashboard：<http://localhost:18083>（默认账号 `admin` / `public`，首次登录可能要求改密）。
> 该方式会占用宿主机 `1883` / `18083`；若已被本机 EMQX 占用，请改用方案 A。

### 方案 C：本机直接运行 Go

需要 Go **1.24+**（paho v1.5.1 的最低要求）。

```bash
cd mock
go mod download
go run .
```

或构建二进制：

```bash
go build -trimpath -o omg315-mock .
./omg315-mock -mqtt tcp://localhost:1883
```

### 配置项

优先级：命令行参数 > 环境变量 > 默认值。

| 参数 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `-mqtt` | `MQTT_URL` | `tcp://localhost:1883` | 支持 `tcp://` / `ssl://`（自签 CA 请自行扩展 TLSConfig） |
| `-client-id` | `MQTT_CLIENT_ID` | `OpenMQTTGateway_ESP32_315M` | 同一 Broker 上保持唯一 |
| `-user` | `MQTT_USERNAME` | 空 | MQTT 用户名 |
| `-pass` | `MQTT_PASSWORD` | 空 | MQTT 密码 |
| `-http` | `HTTP_ADDR` | `:8099` | 设为空字符串可禁用 HTTP |
| `-topic-prefix` | `OMG_TOPIC_PREFIX` | `home/OpenMQTTGateway` | 并行测试时可加前缀 |
| `-connect-timeout` | — | `5s` | 单次连接超时 |
| `-keepalive` | — | `30s` | MQTT KeepAlive |

示例：

```bash
MQTT_URL=tcp://127.0.0.1:1883 \
HTTP_ADDR=:18099 \
OMG_TOPIC_PREFIX=test/a \
go run .
```

---

## 3. 控制台 CLI

启动后直接输入：

| 指令 | 行为 |
|---|---|
| `on 1` | 模拟按下 1 号设备“开”，发布 `value=1714739` |
| `off 1` | 模拟按下 1 号设备“关”，发布 `value=1714740` |
| `on` / `off` | 省略设备号时默认 1 号 |
| `rand` | 生成随机 24 位新码，模拟未知遥控器配对 |
| `press <code>` | 单帧 RX：发送任意 24 位码（支持 `0x` 前缀） |
| `burst <code> [frames]` | 多帧 RX：模拟一次物理按键产生的重复帧，默认 3 帧、间隔 80ms |
| `send <code> [repeat]` | 模拟 ESP32 发射 315M 码，默认重放 5 次；只打印 TX，不发布 MQTT |
| `kill` | 模拟 ESP32 意外掉电：不发送 `DISCONNECT`，强制关闭 TCP，触发 LWT=`offline` |
| `boot` | 扩展指令：`kill` 之后重新上电并连接 Broker |
| `status` | 查看电源、MQTT 连接、主题、运行时长 |
| `help` | 显示帮助 |
| `quit` | 正常退出：发布 retained `offline` 后断开 |

### 预期输出示例

下发发射指令（后端/Compose 点击按钮）：

```text
[MQTT] <- home/OpenMQTTGateway/commands/MQTTto315 {"value":1714739,"protocol":1,"length":24,"repeat":5}
[315M TX] 模拟天线发射: Value=1714739 (HEX: 0x1A2A33), 重放=5次
```

CLI 输入 `on 1`：

```text
[315M RX] 模拟按下 1号设备 开: Value=1714739 (HEX: 0x1A2A33), delay=330ms
[MQTT] -> home/OpenMQTTGateway/315toMQTT {"value":1714739,"protocol":1,"length":24,"delay":330}
```

CLI 输入 `burst 1714739 3`：

```text
[315M RX] 模拟接收遥控器码 0x1A2A33 的连发帧: Value=1714739 (HEX: 0x1A2A33), frames=3, interval=80ms, delay=330ms
[MQTT] -> home/OpenMQTTGateway/315toMQTT {"value":1714739,"protocol":1,"length":24,"delay":330}
```

CLI 输入 `send 1714739 5`：

```text
[315M TX] 模拟天线发射: Value=1714739 (HEX: 0x1A2A33), 重放=5次
[315M TX] 来源: CLI send
```

CLI 输入 `kill`：

```text
[POWER] 模拟 ESP32 意外掉电：不发送 MQTT DISCONNECT，直接切断 TCP；等待 Broker 发布遗嘱 offline
[POWER] TCP 连接已断开，Broker 应已发布遗嘱 home/OpenMQTTGateway/LWT=offline；输入 boot 重新上电
```

---

## 4. HTTP 控制接口（自动化测试）

默认监听 `:8099`，已开启宽松 CORS（方便 Compose 前端直接调用）。

### `POST /mock/press-button`（核心接口）

请求：

```bash
curl -X POST http://localhost:8099/mock/press-button \
  -H 'Content-Type: application/json' \
  -d '{"code":1714739}'
```

响应：

```json
{
  "ok": true,
  "topic": "home/OpenMQTTGateway/315toMQTT",
  "payload": {
    "value": 1714739,
    "protocol": 1,
    "length": 24,
    "delay": 330
  },
  "hex": "0x1A2A33",
  "frames": 1,
  "interval_ms": 0
}
```

- `code` 必须是 24 位整数：`0..16777215`
- `frames`（可选，`1..20`）模拟一次物理按键产生的多帧 RX；默认 `1`
- `interval_ms`（可选，`0..1000`）是帧间隔；多帧且省略时默认 `80`
- 多帧总时长不能超过 `3000ms`
- 网关断电（`kill` 后）返回 `503`，MQTT 未连接同样返回 `503`
- 请求体非法/缺字段/超范围返回 `400`

示例：模拟一次按键产生 3 个重复帧（服务端学习时应计为 `1/3`，不是 `3/3`）：

```bash
curl -X POST http://localhost:8099/mock/press-button \
  -H 'Content-Type: application/json' \
  -d '{"code":1714739,"frames":3,"interval_ms":80}'
```

### `POST /mock/transmit`（模拟 ESP32 发射）

不经过 MQTT，直接触发模拟器的“天线发射”路径，用于测试 ESP32 发送侧和自动化脚本。

```bash
curl -X POST http://localhost:8099/mock/transmit \
  -H 'Content-Type: application/json' \
  -d '{"code":1714739,"protocol":1,"length":24,"repeat":5}'
```

响应：

```json
{
  "ok": true,
  "payload": {
    "value": 1714739,
    "protocol": 1,
    "length": 24,
    "repeat": 5
  },
  "hex": "0x1A2A33"
}
```

- `protocol` / `length` 省略时默认 `1` / `24`；`repeat` 省略时默认 `5`
- `repeat` 范围 `1..50`
- 发射**不会**发布 `315toMQTT`；命令只有 MQTT 发布结果，没有 RF 确认
- 网关断电时返回 `503`

### 扩展接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/mock/health` | 存活与 MQTT 连接状态 |
| `GET` | `/mock/status` | 完整状态（ClientID、Broker、主题、运行时长、RX/TX 帧计数、最近码） |
| `POST` | `/mock/press-button` | 模拟 RX（支持 `frames` / `interval_ms`） |
| `POST` | `/mock/transmit` | 模拟 TX（不发布 MQTT） |
| `POST` | `/mock/kill` | 等价 CLI `kill`，用于自动化验证 LWT |
| `POST` | `/mock/boot` | 等价 CLI `boot` |

```bash
curl http://localhost:8099/mock/health
curl -X POST http://localhost:8099/mock/kill
curl -X POST http://localhost:8099/mock/boot
```

`kill` / `boot` 是幂等的：重复调用返回 `200` 并带 `"already": true`。

---

## 5. 前后端联调流程

1. 启动本地 EMQX，再启动本 Mock（见第 2 节）。
2. 后端发布到 `home/OpenMQTTGateway/commands/MQTTto315` 时，Mock 终端立即打印：
   ```text
   [315M TX] 模拟天线发射: Value=1714739 (HEX: 0x1A2A33), 重放=5次
   ```
3. 在 Compose 界面点“打开增氧机” → 后端下发指令 → 上述日志出现。
4. 在 Mock 终端输入 `on 1`（或调用 `POST /mock/press-button`）→ Mock 发布
   `home/OpenMQTTGateway/315toMQTT`；服务端把抓码计入学习会话，
   不改变设备或命令状态。

> 注意：`MQTTto315` 只表示“网关收到发射请求”，Mock 不会自动把它伪装成一次物理按键。
> 命令发布成功即 `dispatched`（终态），前端以轮询到的设备快照为准；
> `315toMQTT` 抓码只用于学习，不用于确认命令执行结果。

### 验证 MQTT 报文

```bash
# 订阅全部相关主题
mosquitto_sub -h localhost -p 1883 -v -t 'home/OpenMQTTGateway/#'

# 下发发射指令
mosquitto_pub -h localhost -p 1883 \
  -t 'home/OpenMQTTGateway/commands/MQTTto315' \
  -m '{"value":1714739,"protocol":1,"length":24,"repeat":5}'

# 读取 retained LWT / version
mosquitto_sub -h localhost -p 1883 -C 1 -v -t 'home/OpenMQTTGateway/LWT'
mosquitto_sub -h localhost -p 1883 -C 1 -v -t 'home/OpenMQTTGateway/version'
```

---

## 6. 测试

```bash
cd mock
go test ./...
```

覆盖：HEX 格式化、RX JSON 精确字段顺序、发射指令解析、随机码范围、HTTP 参数校验、
CORS、kill/boot 幂等、health/status 等。

可选 Docker 冒烟测试（会自行拉起隔离的 EMQX + Mock，并验证 MQTT/HTTP/LWT）：

```bash
cd mock
./scripts/smoke-test.sh
```

---

## 7. 目录结构

```text
mock/
├── main.go              # 启动、信号处理、HTTP 监听、Banner
├── config.go            # 配置/环境变量/主题派生
├── gateway.go           # MQTT 生命周期、命令处理、kill/boot、LWT
├── http.go              # HTTP 控制接口
├── cli.go               # 交互式 CLI
├── payload.go           # 协议结构体、解析、随机码、设备映射
├── logger.go            # 并发安全控制台输出
├── *_test.go            # 单元测试
├── Dockerfile
├── docker-compose.yml       # Mock 连接本机 EMQX
├── docker-compose.emqx.yml  # 可选：连同 EMQX 一起启动
└── scripts/smoke-test.sh    # 隔离式端到端冒烟测试
```

---

## 8. 行为细节与注意事项

- **掉电语义**：`kill` 关闭底层 TCP，不发送 MQTT `DISCONNECT`，Broker 会发布 retained
  LWT=`offline`。Mock 进程保持运行但处于断电状态；`boot` 会创建全新 MQTT 会话并重新
  订阅/上线，等价于 ESP32 重启。
- **正常退出**：`quit` / `Ctrl-C` / `SIGTERM` 会先发布 retained `offline`，再发送
  `DISCONNECT`，不会留下错误的 retained `online`。
- **Broker 掉线**：Mock 用 1s→2s→4s→…→10s 退避自动重连；重连成功后重新订阅并发布
  retained `online`。
- **单实例**：同一个 ClientID 只应运行一个实例，否则 EMQX 会把旧连接踢下线。并行测试
  时请同时修改 `MQTT_CLIENT_ID` 和 `OMG_TOPIC_PREFIX`。
- **设备映射**：仅预置 1 号设备的两个码。需要更多物理按键时，在 `payload.go` 的
  `knownDevices` 中追加即可；临时测试任意码用 `press <code>` 或 HTTP 接口。
- **安全**：默认匿名连接、无鉴权、监听所有网卡的 8099。仅建议在本机/隔离测试网络使用。

---

## 9. 常见问题

| 现象 | 排查 |
|---|---|
| `connection refused` | EMQX 未启动/端口不对；Docker 内不要用 `localhost` 连宿主机，改用 `host.docker.internal` |
| Docker 里连不上本机 EMQX | 使用本仓库 Compose 的默认 `host.docker.internal`，或 `-e MQTT_URL=tcp://host.docker.internal:1883` |
| `8099` 被占用 | `-http :18099` / `HTTP_ADDR=:18099` |
| 看不到 LWT | 用新订阅者读取 retained `home/OpenMQTTGateway/LWT`；确认 `kill` 不是正常退出 |
| `docker attach` 后输入没有反应 | 容器需以 `stdin_open: true`（本项目 Compose 已配置）或 `docker run -i` 启动；也可直接用 HTTP 接口 |
| 两个实例互相踢下线 | 修改 `MQTT_CLIENT_ID` |
| 主题对不上 | 检查是否设置了 `OMG_TOPIC_PREFIX`，默认应为 `home/OpenMQTTGateway` |
