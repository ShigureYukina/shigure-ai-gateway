#!/usr/bin/env bash
# 单 Key 故障隔离验证：只打坏池子里的一把 Key，看网关是「换一把继续」还是「把整条通道判死」。
# 结果写到 docs/load/data/key-isolation-*.json，供 docs/load/key-isolation.md 引用。
#
# 为什么要单独一组，而不是复用 run_fault_injection.sh：
#   那边的注入是「全局比例」（200 个请求里 25% 随机 429），坏的是整条上游，不是某一把 Key。
#   它证明的是「通道在限流下会被摘掉」，但完全没走到 ProviderKeyPoolService 的单 Key 冷却路径。
#   本脚本用 --fail-auth-key 只打坏一把 Key，才能把「单 Key 隔离」和「通道熔断」两层分开测：
#     - 单 Key 层：ProviderKeyPoolService，连败 2 次、冷却 60s；
#     - 通道层：Resilience4j provider-openai，窗口 20 / 最少 10 次 / 失败率 50%。
#   429 同时喂给两层（isProviderChannelFault 把 PROVIDER_RATE_LIMITED 记为通道故障），
#   所以「只坏一把 Key 会不会把整条通道带走」取决于并发下哪一层先反应。
#
# 前置：Docker Desktop 在跑，且已打包出 target/shortlink-ai-gateway.jar。
#
# 用法（Git Bash / MSYS）：
#   export MSYS_NO_PATHCONV=1
#   bash scripts/run_key_isolation.sh

set -euo pipefail

export MSYS_NO_PATHCONV=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if command -v cygpath >/dev/null 2>&1; then
  CTX="$(cygpath -m "$SCRIPT_DIR/..")"
else
  CTX="$SCRIPT_DIR/.."
fi
IMG_PY="${IMG_PY:-docker.m.daocloud.io/library/python:3.12-slim}"
IMG_JRE="${IMG_JRE:-docker.m.daocloud.io/library/eclipse-temurin:17-jre}"
OUT="$CTX/docs/load/data"

REQUESTS="${REQUESTS:-200}"
CONC_SERIAL="${CONC_SERIAL:-2}"
CONC_CONCURRENT="${CONC_CONCURRENT:-20}"
SEED="${SEED:-42}"

# 池子里坏掉的那把 Key 的标识子串；mock 只对携带它的请求返回 429。
BAD_KEY="${BAD_KEY:-sk-pool-bad}"
GOOD_KEY="${GOOD_KEY:-sk-pool-good}"

log() { printf '\n=== %s ===\n' "$1"; }

ensure_network() {
  docker network inspect aigw-net >/dev/null 2>&1 || docker network create aigw-net >/dev/null
}

ensure_redis() {
  if ! docker inspect aigw-redis >/dev/null 2>&1; then
    docker run -d --name aigw-redis --network aigw-net redis:7.2-alpine >/dev/null
    log "redis 已启动，等待就绪"
    sleep 3
  fi
}

# restart_mock <额外参数...>
restart_mock() {
  docker rm -f aigw-mock >/dev/null 2>&1 || true
  docker run -d --name aigw-mock --network aigw-net \
    -v "$CTX/scripts:/scripts" $IMG_PY \
    python /scripts/mock_openai.py --host 0.0.0.0 --port 18080 \
    --ttft-ms 20 --latency-ms 15 --seed "$SEED" "$@" >/dev/null
  sleep 2
}

# restart_gateway <额外 JVM 参数...>
restart_gateway() {
  docker rm -f aigw-gateway >/dev/null 2>&1 || true
  docker run -d --name aigw-gateway --network aigw-net \
    -v "$CTX/target/shortlink-ai-gateway.jar:/app/app.jar" $IMG_JRE \
    java -jar /app/app.jar --server.port=8010 \
    --spring.cloud.nacos.discovery.enabled=false \
    --spring.cloud.service-registry.auto-registration.enabled=false \
    --spring.cloud.nacos.config.enabled=false \
    --spring.data.redis.host=aigw-redis --spring.data.redis.port=6379 \
    --short-link.ai-gateway.upstream.provider-base-url.openai=http://aigw-mock:18080 \
    --short-link.ai-gateway.tenant.enabled=true \
    --management.health.r2dbc.enabled=false "$@" >/dev/null

  log "等待网关就绪"
  for _ in $(seq 1 60); do
    if docker run --rm --network aigw-net $IMG_PY python -c "
import urllib.request, sys
try:
    urllib.request.urlopen('http://aigw-gateway:8010/actuator/health', timeout=2)
except Exception:
    sys.exit(1)
" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "网关在 120s 内没有就绪，放弃本轮" >&2
  docker logs --tail 50 aigw-gateway || true
  exit 1
}

# warm_up <名称> —— 用健康 mock 预热 JVM，结果丢弃。
warm_up() {
  local name="$1"
  docker run --rm --network aigw-net -v "$CTX/scripts:/scripts" -v "$OUT:/out" $IMG_PY \
    python /scripts/load_test.py --base-url http://aigw-gateway:8010 \
    --requests "$REQUESTS" --concurrency 8 --only non_stream_success \
    --output "/out/key-isolation-$name-warmup.json" >/dev/null
}

# measure <名称> <并发>
measure() {
  local name="$1"; local conc="$2"
  log "压测 $name（并发 $conc）"
  docker run --rm --network aigw-net -v "$CTX/scripts:/scripts" -v "$OUT:/out" $IMG_PY \
    python /scripts/load_test.py --base-url http://aigw-gateway:8010 \
    --requests "$REQUESTS" --concurrency "$conc" --only non_stream_success \
    --output "/out/key-isolation-$name.json"
}

# snapshot <名称> —— 抓两类证据：网关侧（Key 冷却日志 / 熔断 OPEN 计数）与 mock 侧（每把 Key 命中数）。
# 只看成功率无法区分「隔离生效」和「隔离没生效但恰好没崩」；命中数与冷却日志才说明机制真的走了。
snapshot() {
  local name="$1"
  docker logs aigw-gateway > "$OUT/key-isolation-$name-gateway.log" 2>&1 || true
  local cooling open
  cooling="$(grep -c 'cooling down' "$OUT/key-isolation-$name-gateway.log" || true)"
  open="$(grep -c 'CircuitBreaker is OPEN\|CallNotPermittedException' "$OUT/key-isolation-$name-gateway.log" || true)"
  docker run --rm --network aigw-net $IMG_PY python -c "
import json, urllib.request
raw = urllib.request.urlopen('http://aigw-mock:18080/_stats', timeout=3).read().decode()
print(raw)
" > "$OUT/key-isolation-$name-stats.json" 2>/dev/null || echo '{"requests_by_auth":{}}' > "$OUT/key-isolation-$name-stats.json"
  echo "  Key 冷却日志条数 = $cooling | 熔断 OPEN/拒绝日志条数 = $open"
  echo "  mock 收到的按 Key 命中数：$(cat "$OUT/key-isolation-$name-stats.json")"
}

ensure_network
ensure_redis

# 每组都重启网关：Key 池冷却状态与熔断器状态都活在网关进程内存里，不重启会跨组继承。

# 组 1：池子 2 把 Key，只打坏 key#1 —— 低并发（近串行）。
#    预期：坏 Key 连败 2 次后冷却，好 Key 承接，整条通道存活。
log "组 1：池 2 把 Key + 只坏 key#1（并发 $CONC_SERIAL，验证冷却路径本身）"
restart_mock
restart_gateway \
  "--short-link.ai-gateway.upstream.provider-credentials.openai.api-keys[0].key-id=openai-bad" \
  "--short-link.ai-gateway.upstream.provider-credentials.openai.api-keys[0].api-key=$BAD_KEY" \
  "--short-link.ai-gateway.upstream.provider-credentials.openai.api-keys[1].key-id=openai-good" \
  "--short-link.ai-gateway.upstream.provider-credentials.openai.api-keys[1].api-key=$GOOD_KEY"
warm_up pool-serial
restart_mock --fail-auth-key "$BAD_KEY"
measure pool-serial "$CONC_SERIAL"
snapshot pool-serial

# 组 2：同样 2 把 Key，但高并发。
#    预期：首个并发波次里多个坏 Key 请求同时在中，冷却还没落地，通道层可能先被顶开——
#    这一组就是「单 Key 隔离在高并发下够不够」的实测答案。
log "组 2：池 2 把 Key + 只坏 key#1（并发 $CONC_CONCURRENT，测隔离在高并发下的边界）"
restart_mock
restart_gateway \
  "--short-link.ai-gateway.upstream.provider-credentials.openai.api-keys[0].key-id=openai-bad" \
  "--short-link.ai-gateway.upstream.provider-credentials.openai.api-keys[0].api-key=$BAD_KEY" \
  "--short-link.ai-gateway.upstream.provider-credentials.openai.api-keys[1].key-id=openai-good" \
  "--short-link.ai-gateway.upstream.provider-credentials.openai.api-keys[1].api-key=$GOOD_KEY"
warm_up pool-concurrent
restart_mock --fail-auth-key "$BAD_KEY"
measure pool-concurrent "$CONC_CONCURRENT"
snapshot pool-concurrent

# 组 3：单 Key（就是那把坏的）—— 对照组，复现 run_fault_injection.sh 里「通道被摘」的现象，
#    但这次是 100% 打坏同一把 Key（而非全局 25%），用来对照「有池 vs 无池」。
log "组 3：单 Key（=坏 Key）+ 并发 $CONC_CONCURRENT，对照组"
restart_mock
restart_gateway \
  "--short-link.ai-gateway.upstream.provider-credentials.openai.api-key=$BAD_KEY"
warm_up single-key
restart_mock --fail-auth-key "$BAD_KEY"
measure single-key "$CONC_CONCURRENT"
snapshot single-key

log "完成，结果在 $OUT/key-isolation-*.json"
echo "只看成功率不够：每组同时留了 gateway 日志（冷却/熔断）与 mock 的按 Key 命中数。"
