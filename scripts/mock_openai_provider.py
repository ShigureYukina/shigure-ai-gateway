from http.server import BaseHTTPRequestHandler, HTTPServer
import json


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/v1/models":
            body = json.dumps({
                "data": [
                    {"id": "gpt-4o-mini"},
                    {"id": "gpt-4o-mini-compatible"}
                ]
            }).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        if self.path == "/v1/chat/completions":
            request = json.loads(raw or b"{}")
            model = request.get("model", "unknown")
            stream = bool(request.get("stream", False))
            if stream:
                payload = (
                    'data: {"id":"chatcmpl-mock-1","object":"chat.completion.chunk","model":"%s","choices":[{"index":0,"delta":{"content":"mock response ok"},"finish_reason":null}]}\n\n'
                    'data: {"id":"chatcmpl-mock-1","object":"chat.completion.chunk","model":"%s","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}\n\n'
                    'data: [DONE]\n\n'
                ) % (model, model)
                body = payload.encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return

            body = json.dumps({
                "id": "chatcmpl-mock-1",
                "object": "chat.completion",
                "model": model,
                "choices": [
                    {
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "mock response ok"
                        },
                        "finish_reason": "stop"
                    }
                ],
                "usage": {
                    "prompt_tokens": 12,
                    "completion_tokens": 8,
                    "total_tokens": 20
                }
            }).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.end_headers()

    def log_message(self, fmt, *args):
        return


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", 18080), Handler).serve_forever()
