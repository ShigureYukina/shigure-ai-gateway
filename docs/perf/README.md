# 每请求 Redis 往返：16 次 → 1 次

> 对应提交 `31aa7f4`（perf: 网关代理链路响应式化）中的指标埋点改造。
> 本文记录该结论的实测方法、原始数据与边界，供复核。

## 一、要验证的结论

网关在**响应终态**记录调用明细与聚合指标。改造前这段逻辑用阻塞式
`StringRedisTemplate` 逐条发命令；改造后改用 `ReactiveStringRedisTemplate`，
把整段写入合并成**一个 Lua 脚本**，发一次 `EVALSHA`；并且 fire-and-forget，不阻塞响应。

要验证的是这句话中最容易被追问的部分：**"每请求的 Redis 往返次数从 16 降到 1"**。

## 二、为什么用 `INFO commandstats` 而不是数代码行

数代码能数出"脚本里有几条命令"，但数不出**往返次数** —— 这正是这个优化的全部意义。
Lua 脚本内的命令在 Redis 内部执行，不产生网络往返，但 Redis 会照常把它们计入 commandstats。

所以测量方法是：**重置统计 → 打固定数量的请求 → 看统计增量**。

关键点：`evalsha` 的 calls 数就是往返次数；脚本内的 hincrby / expire / zadd / rpush
虽然也出现在统计里，但它们是**脚本内部执行**的，不额外产生往返。两者必须分开看，
否则会把"1 次往返"误读成"17 次命令"。

## 三、测量步骤

```bash
export MSYS_NO_PATHCONV=1
CTX=E:/DeepSeek-work/shigure-ai-gateway
PY=docker.m.daocloud.io/library/python:3.12-slim

# 1) 预热：先打 20 个请求，把连接与脚本缓存（EVALSHA 前的 EVAL）带热
docker run --name rp-warm -v "$CTX/target/redis_probe.py:/probe.py" --network aigw-net $PY \
  python /probe.py http://aigw-gateway:8010 20

# 2) 清零统计，再打 100 个请求
docker exec aigw-redis redis-cli CONFIG RESETSTAT
docker run --name rp-main -v "$CTX/target/redis_probe.py:/probe.py" --network aigw-net $PY \
  python /probe.py http://aigw-gateway:8010 100

# 3) 读增量
docker exec aigw-redis redis-cli INFO commandstats
```

`target/redis_probe.py` 是最小探针：串行发 N 个非流式请求，只统计成功数，
不夹带并发，避免把"并发下的命令合并"误读成优化效果。

对比版本用 `git worktree add ../aigw-before 31aa7f4^` 检出改造前的代码，
同样用 Docker 构建、同样参数启动（端口 8030）。

## 四、测量结果

固定发 **100 个请求**，`CONFIG RESETSTAT` 之后的 commandstats 增量如下。

### 改造前（commit `ab01568`）

| 命令 | calls | 每请求 |
| --- | --- | --- |
| hincrby | 800 | 8 |
| pexpire | 400 | 4 |
| hincrbyfloat | 200 | 2 |
| zadd | 100 | 1 |
| rpush | 100 | 1 |
| **合计** | **1600** | **16** |

16 条命令 = 16 次网络往返（每条命令一个请求-响应）。

### 改造后（commit `31aa7f4` 及之后）

| 命令 | calls | 每请求 | 说明 |
| --- | --- | --- | --- |
| **evalsha** | **100** | **1** | 唯一的网络往返 |
| hincrby | 800 | 8 | 脚本内部执行，不计往返 |
| expire | 400 | 4 | 同上 |
| hincrbyfloat | 200 | 2 | 同上 |
| zadd | 100 | 1 | 同上 |
| rpush | 100 | 1 | 同上 |

脚本内依旧是 16 条命令，但**网络往返从 16 降到 1**。命令一条没少，慢的是往返。

客户端侧观察：同样串行发 100 个请求（上游固定 35ms），改造前 4.301s、改造后 3.943s，
每请求差约 3.6ms —— 与"省下 15 次本机 Redis 往返"的量级一致。注意这是**串行**场景的差值；
并发下阻塞式写入会占住 Netty 事件循环线程，代价远不止往返时间本身。

## 五、一处口径修正

简历与早期描述里写的是"**17 次**"，实测是 **16 次**：`cacheHit` 字段只在缓存命中时
才执行 `HINCRBY`，非缓存路径上不存在这条命令。改造前的 16 条命令是

```
RPUSH callKey + EXPIRE callKey                            2
HINCRBY metricKey {calls,success,tokenIn,tokenOut,cost}   5
EXPIRE metricKey                                          1
HINCRBY tenantMetricKey {calls,success,tokenIn,tokenOut,cost} 5
EXPIRE tenantMetricKey                                    1
ZADD latencyKey + EXPIRE latencyKey                       2
---------------------------------------------------------------
                                                         16
```

**结论按 16 修正**，避免在面试中被追问"哪 17 条"时对不上。

## 六、边界

- 只覆盖"非缓存命中"的单次成功调用。缓存命中路径会多两条命令（`cacheHit` 计数与缓存事件计数），
  那些路径也各自合并成单次往返（`TENANT_CACHE_EVENT_SCRIPT`），但本文未单独测量。
- 只测了 Redis 往返次数，没有复测提交信息里"纯代理吞吐 1.3k → 11.5k RPS"这一条 ——
  那是在另一套压测条件下得到的，本目录不引用该数字。
- 统计口径以本机 Redis 7.2 为准；`INFO commandstats` 会统计脚本内部命令这一点，
  在不同 Redis 版本上行为一致，但若将来关闭该统计需改回 MONITOR 方式取样。
