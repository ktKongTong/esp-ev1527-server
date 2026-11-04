#!/usr/bin/env bash
#
# 隔离式端到端冒烟测试：
#   - 自建 Docker 网络，使用临时容器名，不占用宿主机 1883
#   - 拉起 EMQX + Mock + MQTT 订阅者
#   - 验证 retained online/version、MQTT RX/TX 日志、HTTP 抓码、kill/LWT、boot、CLI 生命周期
#
# 依赖：docker、curl、bash。首次运行会拉取 emqx/emqx:5.8.7 与 eclipse-mosquitto:2.0。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SUFFIX="$$-$RANDOM"
NET="omg315-smoke-net-$SUFFIX"
IMAGE="omg315-mock:smoke-$SUFFIX"
EMQX="omg315-smoke-emqx-$SUFFIX"
MOCK="omg315-smoke-mock-$SUFFIX"
SUB="omg315-smoke-sub-$SUFFIX"
TMP="$(mktemp -d)"
PORT=""
PREFIX="home/OpenMQTTGateway"

cleanup() {
  docker rm -f "$MOCK" "$SUB" "$EMQX" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  docker rmi "$IMAGE" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT

info() { printf '\n\033[1;36m[smoke]\033[0m %s\n' "$*"; }
fail() { printf '\n\033[1;31m[FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || fail "未找到 docker"
command -v curl >/dev/null 2>&1 || fail "未找到 curl"

wait_log() {
  local container="$1" pattern="$2"
  for _ in $(seq 1 20); do
    if docker logs "$container" 2>&1 | grep -qF "$pattern"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

retained() {
  docker run --rm --network "$NET" --entrypoint mosquitto_sub eclipse-mosquitto:2.0 \
    -h "$EMQX" -p 1883 -C 1 -W 3 -t "$1" 2>/dev/null || true
}

wait_retained() {
  local topic="$1" want="$2"
  for _ in $(seq 1 20); do
    if [ "$(retained "$topic")" = "$want" ]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

info "构建 Mock 镜像"
docker build -q -t "$IMAGE" "$ROOT" >/dev/null

info "创建隔离网络 $NET"
docker network create "$NET" >/dev/null

info "启动 EMQX"
docker run -d --name "$EMQX" --network "$NET" emqx/emqx:5.8.7 >/dev/null

info "等待 EMQX 就绪"
ready=0
for _ in $(seq 1 60); do
  if docker exec "$EMQX" emqx ctl status >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done
[ "$ready" -eq 1 ] || fail "EMQX 未在超时内就绪"

info "启动 MQTT 订阅者"
docker run -d --name "$SUB" --network "$NET" --entrypoint mosquitto_sub \
  eclipse-mosquitto:2.0 -h "$EMQX" -p 1883 -v -q 1 -t "$PREFIX/#" >/dev/null

info "启动 Mock（HTTP 使用随机宿主机端口）"
docker run -d --name "$MOCK" --network "$NET" -p "127.0.0.1::8099" \
  -e MQTT_URL="tcp://$EMQX:1883" "$IMAGE" >/dev/null
PORT="$(docker port "$MOCK" 8099/tcp | head -n1 | awk -F: '{print $NF}')"
[ -n "$PORT" ] || fail "无法获取 Mock 的 HTTP 映射端口"
BASE="http://127.0.0.1:$PORT"

info "等待 Mock 连接 MQTT"
connected=0
for _ in $(seq 1 30); do
  if curl -sf "$BASE/mock/health" | grep -q '"mqtt_connected":true'; then
    connected=1
    break
  fi
  sleep 1
done
[ "$connected" -eq 1 ] || fail "Mock 未在超时内连接 MQTT"

info "校验 retained online / version"
wait_retained "$PREFIX/LWT" online || fail "retained LWT 不是 online"
wait_retained "$PREFIX/version" v1.7.0 || fail "retained version 不是 v1.7.0"

info "1) 下发 MQTTto315 发射指令，校验 TX 日志"
docker run --rm --network "$NET" --entrypoint mosquitto_pub eclipse-mosquitto:2.0 \
  -h "$EMQX" -p 1883 -q 1 -t "$PREFIX/commands/MQTTto315" \
  -m '{"value":1714739,"protocol":1,"length":24,"repeat":5}'
wait_log "$MOCK" '[315M TX] 模拟天线发射: Value=1714739 (HEX: 0x1A2A33), 重放=5次' \
  || fail "未看到 [315M TX] 日志"
wait_log "$MOCK" "[MQTT RX] topic=$PREFIX/commands/" \
  || fail "未看到 [MQTT RX] 日志"

info "2) HTTP press-button，校验 315toMQTT"
response="$(curl -sf -X POST "$BASE/mock/press-button" \
  -H 'Content-Type: application/json' -d '{"code":1714739}')"
printf '%s\n' "$response"
printf '%s' "$response" | grep -q '"delay":330' || fail "press-button 响应缺少 delay=330"
wait_log "$SUB" "$PREFIX/315toMQTT {\"value\":1714739,\"protocol\":1,\"length\":24,\"delay\":330}" \
  || fail "订阅者未收到 315toMQTT"
wait_log "$MOCK" "[MQTT TX] topic=$PREFIX/315toMQTT" \
  || fail "未看到 [MQTT TX] 日志"
status="$(curl -sf "$BASE/mock/status")"
printf '%s\n' "$status"
printf '%s' "$status" | grep -q '"rx_frames":1' || fail "press-button 后 rx_frames 期望 1"

info "2b) HTTP 多帧 RX，校验一次按键的连发帧"
response="$(curl -sf -X POST "$BASE/mock/press-button" \
  -H 'Content-Type: application/json' -d '{"code":1714739,"frames":3,"interval_ms":80}')"
printf '%s\n' "$response"
printf '%s' "$response" | grep -q '"frames":3' || fail "press-button 响应缺少 frames=3"
burst_ok=0
for _ in $(seq 1 10); do
  if curl -sf "$BASE/mock/status" | grep -q '"rx_frames":4'; then
    burst_ok=1
    break
  fi
  sleep 0.3
done
[ "$burst_ok" -eq 1 ] || fail "多帧 RX 后 rx_frames 期望 4"

info "3) HTTP kill，校验 LWT=offline 与断电后 503"
curl -sf -X POST "$BASE/mock/kill" >/dev/null
wait_retained "$PREFIX/LWT" offline || fail "kill 后 retained LWT 不是 offline"
http_code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/mock/press-button" \
  -H 'Content-Type: application/json' -d '{"code":1714740}')"
[ "$http_code" = "503" ] || fail "断电后 press-button 应返回 503，实际 $http_code"

info "4) HTTP boot，校验重新上线"
curl -sf -X POST "$BASE/mock/boot" >/dev/null
booted=0
for _ in $(seq 1 20); do
  if curl -sf "$BASE/mock/health" | grep -q '"mqtt_connected":true'; then
    booted=1
    break
  fi
  sleep 1
done
[ "$booted" -eq 1 ] || fail "boot 后 MQTT 未恢复连接"
wait_retained "$PREFIX/LWT" online || fail "boot 后 retained LWT 不是 online"

info "5) CLI status / quit，校验生命周期日志与正常退出"
docker rm -f "$MOCK" >/dev/null 2>&1 || true
(
  sleep 3
  echo 'status'
  sleep 1
  echo 'quit'
) | docker run --rm -i --network "$NET" -e MQTT_URL="tcp://$EMQX:1883" "$IMAGE" >"$TMP/cli.log" 2>&1 || true

grep -qF '[STATUS] 电源=上电' "$TMP/cli.log" \
  || fail "CLI status 日志缺失"
grep -qF "[MQTT TX] topic=$PREFIX/LWT payload=offline" "$TMP/cli.log" \
  || fail "CLI quit 未发布 offline"
wait_retained "$PREFIX/LWT" offline || fail "CLI 正常退出后 retained LWT 不是 offline"

info "全部通过 ✅"
