from http.server import BaseHTTPRequestHandler, HTTPServer


class MockHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self.send_response(404)
            self.end_headers()
            return

        content_length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else ""
        is_stream = '"stream":true' in body.replace(" ", "").lower()

        if is_stream:
            payload = (
                "data: {\"id\":\"chatcmpl-mock-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello \"}}]}\\n\\n"
                "data: {\"id\":\"chatcmpl-mock-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"world\"}}]}\\n\\n"
                "data: [DONE]\\n\\n"
            )
            data = payload.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return

        payload = (
            '{"id":"chatcmpl-mock-1","object":"chat.completion","model":"gpt-4o-mini",'
            '"choices":[{"index":0,"message":{"role":"assistant","content":"hello from mock"},"finish_reason":"stop"}],'
            '"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}'
        )
        data = payload.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format, *args):
        return


if __name__ == "__main__":
    server = HTTPServer(("127.0.0.1", 18080), MockHandler)
    print("mock-openai listening on 127.0.0.1:18080", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
