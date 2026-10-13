#!/usr/bin/env bash
# 故障注入测量：三组场景（上游 500 / 上游 429 / 上游不回包）各跑一轮，
# 结果写到 docs/load/fault-*.json，供 docs/load/fault-injection.md 引用。
#
# 为什么要单独一个脚本：docs/load/README.md 那套基线是「全链路健康」下测的，
# 网关自己的降级路径（错误归类、熔断计数、超时、fail-open）在健康上游下根本不会被走到。
# 熔断、缓存、限流、安全过滤这些路径的延迟在那份基线里是明确标注「未覆盖」的。
#
# 前置：Docker Desktop 在跑，且已按 docs/load/README.md 第六节打包出
#       target/shortlink-ai-gateway.jar。
#
# 用法（Git Bash / MSYS）：
#   export MSYS_NO_PATHCONV=1
#   bash scripts/run_fault_injection.sh

set -euo pipefail

# 必须放在任何 docker 命令之前：MSYS/Git Bash 会把 -v 后面看起来像路径的参数
# 自动转成 Windows 形式，"/app/app.jar" 会被转成 "C:/Program Files/Git/app/app.jar"，
# 表现为 "Unable to access jarfile"。docs/load/README.md 的复现步骤里让用户手动 export，
# 这里自己兜住，少一个「照文档跑却失败」的原因。
export MSYS_NO_PATHCONV=1

# CTX 要的是 Windows 形式（E:/...），因为 docker.exe 是 Windows 程序。
# pwd 给的是 POSIX 形式（/e/...），MSYS_NO_PATHCONV=1 又阻止了自动转换，所以显式用 cygpath -m。
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if command -v cygpath >/dev/null 2>&1; then
  CTX="$(cygpath -m "$SCRIPT_DIR/..")"
else
  CTX="$SCRIPT_DIR/.."
fi
IMG_PY="${IMG_PY:-docker.m.daocloud.io/library/python:3.12-slim}"
IMG_JRE="${IMG_JRE:-docker.m.daocloud.io/library/eclipse-temurin:17-jre}"
OUT="$CTX/docs/load"

REQUESTS="${REQUESTS:-200}"
CONCURRENCY="${CONCURRENCY:-20}"
SEED="${SEED:-42}"

# 超时场景要把网关两侧的超时都调短，否则一轮要等 60s。
# 注意这两个值必须一起调：只调一边会出现「TimeLimiter 判超时但连接还挂着」的口径错位。
HANG_READ_TIMEOUT="${HANG_READ_TIMEOUT:-2s}"
HANG_TIMELIMITER="${HANG_TIMELIMITER:-2s}"

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

  # 等健康检查通过再压，否则头几个请求会把冷启动写进结果
  log "等待网关就绪"
  for _ in $(seq 1 60); do
    if docker run --rm --network aigw-net $IMG_PY python -c "
import urllib.request, sys
try:
    urllib.request.urlopen('http://aigw-gateway:8010/actuator/health', timeout=2)
except Exception as ex:
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

# warm_up <名称> —— 用当前 mock 预热 JVM，结果丢弃。
# 必须在切到故障 mock 之前调用（见下方各场景的顺序）。
warm_up() {
  local name="$1"
  docker run --rm --network aigw-net -v "$CTX/scripts:/scripts" -v "$OUT:/out" $IMG_PY \
    python /scripts/load_test.py --base-url http://aigw-gateway:8010 \
    --requests "$REQUESTS" --concurrency 8 --only non_stream_success \
    --output "/out/fault-$name-warmup.json" >/dev/null
}

# measure <名称> —— 正式测量，只跑 non_stream_success 一个场景
measure() {
  local name="$1"
  log "压测 $name"
  docker run --rm --network aigw-net -v "$CTX/scripts:/scripts" -v "$OUT:/out" $IMG_PY \
    python /scripts/load_test.py --base-url http://aigw-gateway:8010 \
    --requests "$REQUESTS" --concurrency "$CONCURRENCY" --only non_stream_success \
    --output "/out/fault-$name.json"
}

ensure_network
ensure_redis

# 每组都重启网关：熔断器状态活在网关进程里，不重启就会跨组继承。
# 踩过的坑：第一版只在对照组和超时组重启，于是 429 那组继承了 500 组累积的熔断器状态，
# 客户端直接拿到 "CircuitBreaker is OPEN"，测出来的是熔断后的快速失败，
# 而不是「429 会被怎么处理」。两组数据放在一起会得出完全错误的结论。
#
# 另一个同类的坑：预热必须打健康上游。预热若直接打故障上游，
# 熔断参数（minimum-number-of-calls=10 / failure-rate-threshold=50%）
# 会被几十个故障请求顶开，正式测量整轮都跑在已熔断状态。
# 表现是超时组「配了 2s 超时、最大延迟只有 66ms」——这个数字自己就把测量否掉了。
# 两处的共同点：测量对象已经被上一阶段改变了；健康压测里不存在这个问题。

# 0) 对照组：同一次会话里重跑一遍健康上游，作为三组故障场景的同口径基准。
#    不复用 docs/load/README.md 里已有的基线数字——那次是另一轮、另一套容器参数，
#    跨轮次比会踩到「绝对延迟不可跨轮次比较」那个坑。
log "对照组：健康上游"
restart_mock
restart_gateway
warm_up baseline
measure baseline

# 1) 上游 500：验证错误归类（是否算通道故障）与熔断计数是否增长。
log "场景 1：上游 10% 返回 500"
restart_mock                       # 健康上游
restart_gateway
warm_up error500                   # 先用健康上游把 JVM 带热
restart_mock --error-rate 0.10     # 再切到故障上游
measure error500

# 2) 上游 429：与 500 走不同的归因分支（PROVIDER_RATE_LIMITED），
#    且探测逻辑明确不把 429 计入失败计数——这一组是那个判断的实测依据。
log "场景 2：上游 25% 返回 429"
restart_mock
restart_gateway
warm_up ratelimit429
restart_mock --rate-limit-rate 0.25
measure ratelimit429

# 3) 上游不回包：连接被占住但不返回也不断开，只有读超时能救回来。
#    熔断器同样要从关闭态开始，否则测的是「已熔断后」的快速失败而不是超时本身。
log "场景 3：上游收下请求后不回包（网关超时调到 ${HANG_READ_TIMEOUT}）"
restart_mock
restart_gateway \
  --short-link.ai-gateway.upstream.timeout-retry.read-timeout="$HANG_READ_TIMEOUT" \
  --resilience4j.timelimiter.instances.provider-openai.timeout-duration="$HANG_TIMELIMITER"
warm_up hang-timeout
restart_mock --hang-ms 5000
measure hang-timeout

log "完成，结果在 $OUT/fault-*.json"
echo "每组都是独立的网关进程，熔断器状态不跨组继承；预热一律打健康上游。"
echo "要看「连续失败触发熔断后的恢复」，应在同一组内延长时长另跑一轮，不要跨组比。"
