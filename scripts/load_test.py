import argparse
import json
import statistics
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed


def percentile(sorted_values, pct):
    if not sorted_values:
        return 0.0
    if len(sorted_values) == 1:
        return float(sorted_values[0])
    index = (len(sorted_values) - 1) * pct
    lower = int(index)
    upper = min(lower + 1, len(sorted_values) - 1)
    weight = index - lower
    return float(sorted_values[lower] * (1 - weight) + sorted_values[upper] * weight)


def make_request(url, method="GET", headers=None, body=None, stream=False):
    request = urllib.request.Request(url=url, data=body, headers=headers or {}, method=method)
    start = time.perf_counter()
    first_byte_ms = None
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            status = response.status
            if stream:
                first_chunk = response.readline()
                first_byte_ms = (time.perf_counter() - start) * 1000
                remainder = response.read()
                payload = first_chunk + remainder
            else:
                payload = response.read()
            elapsed_ms = (time.perf_counter() - start) * 1000
            return {
                "ok": 200 <= status < 300,
                "status": status,
                "elapsed_ms": elapsed_ms,
                "first_byte_ms": first_byte_ms,
                "bytes": len(payload),
                "error": None,
            }
    except urllib.error.HTTPError as ex:
        elapsed_ms = (time.perf_counter() - start) * 1000
        payload = ex.read()
        return {
            "ok": False,
            "status": ex.code,
            "elapsed_ms": elapsed_ms,
            "first_byte_ms": first_byte_ms,
            "bytes": len(payload),
            "error": payload.decode("utf-8", errors="ignore"),
        }
    except Exception as ex:
        elapsed_ms = (time.perf_counter() - start) * 1000
        return {
            "ok": False,
            "status": 0,
            "elapsed_ms": elapsed_ms,
            "first_byte_ms": first_byte_ms,
            "bytes": 0,
            "error": str(ex),
        }


def run_scenario(name, total_requests, concurrency, request_factory):
    started = time.perf_counter()
    results = []
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(request_factory, index) for index in range(total_requests)]
        for future in as_completed(futures):
            results.append(future.result())
    wall_seconds = time.perf_counter() - started

    latencies = sorted(item["elapsed_ms"] for item in results)
    first_bytes = sorted(item["first_byte_ms"] for item in results if item["first_byte_ms"] is not None)
    success = sum(1 for item in results if item["ok"])
    status_counts = {}
    for item in results:
        status_counts[str(item["status"])] = status_counts.get(str(item["status"]), 0) + 1

    summary = {
        "scenario": name,
        "requests": total_requests,
        "concurrency": concurrency,
        "success": success,
        "failure": total_requests - success,
        "success_rate": round(success / total_requests, 4) if total_requests else 0,
        "throughput_rps": round(total_requests / wall_seconds, 2) if wall_seconds else 0,
        "wall_seconds": round(wall_seconds, 3),
        "latency_ms": {
            "avg": round(statistics.mean(latencies), 2) if latencies else 0,
            "p50": round(percentile(latencies, 0.50), 2),
            "p95": round(percentile(latencies, 0.95), 2),
            "p99": round(percentile(latencies, 0.99), 2),
            "max": round(max(latencies), 2) if latencies else 0,
        },
        "first_byte_ms": {
            "avg": round(statistics.mean(first_bytes), 2) if first_bytes else None,
            "p50": round(percentile(first_bytes, 0.50), 2) if first_bytes else None,
            "p95": round(percentile(first_bytes, 0.95), 2) if first_bytes else None,
            "max": round(max(first_bytes), 2) if first_bytes else None,
        },
        "status_counts": status_counts,
        "sample_errors": [item["error"] for item in results if item["error"]][:3],
    }
    return summary


def main():
    parser = argparse.ArgumentParser(description="AI Gateway local load test")
    parser.add_argument("--base-url", default="http://127.0.0.1:8011")
    parser.add_argument("--requests", type=int, default=80)
    parser.add_argument("--concurrency", type=int, default=10)
    parser.add_argument("--output", default="target/load-test-summary.json")
    args = parser.parse_args()

    base_headers = {"Content-Type": "application/json"}
    auth_headers = {
        "Content-Type": "application/json",
        "Authorization": "Bearer demo-platform-key",
    }

    non_stream_body = json.dumps({
        "model": "default",
        "messages": [{"role": "user", "content": "hello from load test"}],
        "stream": False,
    }).encode("utf-8")

    stream_body = json.dumps({
        "model": "default",
        "messages": [{"role": "user", "content": "hello stream"}],
        "stream": True,
    }).encode("utf-8")

    forbidden_body = json.dumps({
        "model": "claude-3-5-sonnet-latest",
        "messages": [{"role": "user", "content": "hello forbidden"}],
        "stream": False,
    }).encode("utf-8")

    scenarios = []
    scenarios.append(run_scenario(
        "non_stream_success",
        args.requests,
        args.concurrency,
        lambda _: make_request(f"{args.base_url}/v1/chat/completions", method="POST", headers=auth_headers, body=non_stream_body),
    ))
    scenarios.append(run_scenario(
        "stream_success",
        max(10, args.requests // 4),
        max(2, min(args.concurrency, 5)),
        lambda _: make_request(f"{args.base_url}/v1/chat/completions", method="POST", headers=auth_headers, body=stream_body, stream=True),
    ))
    scenarios.append(run_scenario(
        "missing_api_key_unauthorized",
        max(10, args.requests // 4),
        max(2, min(args.concurrency, 5)),
        lambda _: make_request(f"{args.base_url}/v1/chat/completions", method="POST", headers=base_headers, body=non_stream_body),
    ))
    scenarios.append(run_scenario(
        "forbidden_model_rejected",
        max(10, args.requests // 4),
        max(2, min(args.concurrency, 5)),
        lambda _: make_request(f"{args.base_url}/v1/chat/completions", method="POST", headers=auth_headers, body=forbidden_body),
    ))

    with open(args.output, "w", encoding="utf-8") as output_file:
        json.dump({"base_url": args.base_url, "scenarios": scenarios}, output_file, ensure_ascii=False, indent=2)

    print(json.dumps({"base_url": args.base_url, "scenarios": scenarios}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
