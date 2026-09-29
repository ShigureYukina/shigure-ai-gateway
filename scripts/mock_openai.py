import argparse
import json
import random
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CONFIG = {
    "ttft_ms": 0,
    "latency_ms": 0,
    "jitter_ms": 0,
    "chunk_count": 20,
    "error_rate": 0.0,
}


def _sleep_ms(base_ms, jitter_ms=0):
    delay_ms = base_ms
    if jitter_ms > 0:
        delay_ms += random.uniform(-jitter_ms, jitter_ms)
    if delay_ms > 0:
        time.sleep(delay_ms / 1000.0)


class MockServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    # Default socketserver backlog is 5, which overflows on the burst of TCP
    # connects a gateway opens under load and shows up as "connection refused".
    request_queue_size = 512


class MockHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "mock-openai/2.0"
    # Nagle's algorithm is on by default. Combined with delayed ACK on the
    # gateway's keep-alive connection it stalls each response by ~40ms, which
    # gets misread as gateway overhead. Every request here is a small
    # request/response pair, so batching gains nothing.
    disable_nagle_algorithm = True

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self._send_json(404, b'{"error":{"message":"not found"}}')
            return

        content_length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else ""
        compact = body.replace(" ", "").lower()
        is_stream = '"stream":true' in compact
        model = self._extract_model(body)

        if CONFIG["error_rate"] > 0 and random.random() < CONFIG["error_rate"]:
            self._send_json(500, b'{"error":{"message":"injected upstream error"}}')
            return

        if is_stream:
            self._handle_stream(model)
        else:
            self._handle_non_stream(model)

    def _handle_non_stream(self, model):
        _sleep_ms(CONFIG["ttft_ms"] + CONFIG["latency_ms"], CONFIG["jitter_ms"])
        payload = json.dumps({
            "id": "chatcmpl-mock-" + uuid.uuid4().hex[:12],
            "object": "chat.completion",
            "model": model,
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": "hello from mock"},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
        }).encode("utf-8")
        self._send_json(200, payload)

    def _handle_stream(self, model):
        chunk_id = "chatcmpl-mock-" + uuid.uuid4().hex[:12]
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        _sleep_ms(CONFIG["ttft_ms"])

        chunk_count = max(1, CONFIG["chunk_count"])
        interval_ms = CONFIG["latency_ms"] / chunk_count
        for index in range(chunk_count):
            event = {
                "id": chunk_id,
                "object": "chat.completion.chunk",
                "model": model,
                "choices": [{"index": 0, "delta": {"content": f"token{index} "}}],
            }
            self._write_chunk(f"data: {json.dumps(event)}\n\n".encode("utf-8"))
            _sleep_ms(interval_ms, CONFIG["jitter_ms"] / chunk_count)

        # 真实 OpenAI 在 stream_options.include_usage 打开时，会在 [DONE] 前补发一个
        # 只带 usage 的 chunk。mock 必须对齐，否则网关的"流式用量结算"在本地产线
        # 无法被验证，只能靠真实上游才能发现问题。
        usage_chunk = {
            "id": chunk_id,
            "object": "chat.completion.chunk",
            "model": model,
            "choices": [],
            "usage": {
                "prompt_tokens": 10,
                "completion_tokens": chunk_count,
                "total_tokens": 10 + chunk_count,
            },
        }
        self._write_chunk(f"data: {json.dumps(usage_chunk)}\n\n".encode("utf-8"))

        self._write_chunk(b"data: [DONE]\n\n")
        self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()

    def _write_chunk(self, data):
        self.wfile.write(f"{len(data):X}\r\n".encode("ascii"))
        self.wfile.write(data)
        self.wfile.write(b"\r\n")
        self.wfile.flush()

    def _send_json(self, status, payload):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _extract_model(self, body):
        try:
            return json.loads(body).get("model") or "gpt-4o-mini"
        except (ValueError, AttributeError):
            return "gpt-4o-mini"

    def log_message(self, format, *args):
        return


def parse_args():
    parser = argparse.ArgumentParser(description="Mock OpenAI-compatible upstream for load testing")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--ttft-ms", type=float, default=0.0, help="time to first token (ms)")
    parser.add_argument("--latency-ms", type=float, default=0.0, help="total generation time (ms)")
    parser.add_argument("--jitter-ms", type=float, default=0.0, help="random jitter applied per request (ms)")
    parser.add_argument("--chunk-count", type=int, default=20, help="SSE chunks emitted for a stream response")
    parser.add_argument("--error-rate", type=float, default=0.0, help="fraction of requests answered with 500")
    parser.add_argument("--seed", type=int, default=None, help="random seed for reproducible jitter")
    return parser.parse_args()


def main():
    args = parse_args()
    if args.seed is not None:
        random.seed(args.seed)
    CONFIG.update({
        "ttft_ms": args.ttft_ms,
        "latency_ms": args.latency_ms,
        "jitter_ms": args.jitter_ms,
        "chunk_count": args.chunk_count,
        "error_rate": args.error_rate,
    })

    server = MockServer((args.host, args.port), MockHandler)
    print(
        f"mock-openai listening on {args.host}:{args.port} "
        f"(ttft={args.ttft_ms}ms latency={args.latency_ms}ms jitter={args.jitter_ms}ms "
        f"chunks={args.chunk_count} error_rate={args.error_rate})",
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
