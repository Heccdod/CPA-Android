#!/usr/bin/env python3
"""假 Antigravity 上游：按 SCENARIO 返回不同 SSE，供本地 CPA 服务端翻译。"""
import json, os, socket, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SCENARIO = os.environ.get("SCENARIO", "normal")
DELAY = float(os.environ.get("DELAY", "0"))

def frame(obj):
    return ("data: " + json.dumps(obj, ensure_ascii=False) + "\n\n").encode()

def chunk(parts, finish=None, usage=None, model="gemini-3.8-flash-high", rid="resp-1"):
    cand = {"content": {"role": "model", "parts": parts}}
    if finish:
        cand["finishReason"] = finish
    body = {"response": {"candidates": [cand], "modelVersion": model, "responseId": rid}}
    if usage:
        body["response"]["usageMetadata"] = usage
    return frame(body)

TEXT = "你好，这是正常正文。"
THOUGHT = "先想一想。" * 30

class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a):
        pass
    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        try:
            req = json.loads(raw or b"{}")
        except Exception:
            req = {}
        with open("/tmp/diag-upstream-req.json", "wb") as f:
            f.write(raw)
        sys.stderr.write("UPSTREAM %s scenario=%s\n" % (self.path, SCENARIO))
        if "fetchAvailableModels" in self.path and os.environ.get("PROBE", "") == "fail":
            self.send_response(500)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if "fetchAvailableModels" in self.path or "loadCodeAssist" in self.path:
            body = json.dumps({"models": {
                "gemini-3.8-flash-high": {"displayName": "Gemini 3.8 Flash (High)"},
                "gemini-3.8-flash-medium": {"displayName": "Gemini 3.8 Flash (Medium)"},
                "gemini-3.8-flash-low": {"displayName": "Gemini 3.8 Flash (Low)"},
                "claude-sonnet-4-6": {"displayName": "Claude Sonnet 4.6 (Thinking)"},
            }, "webSearchModelIds": []}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        elif SCENARIO == "e429":
            body = json.dumps({"error": {"code": 429, "message": "Resource has been exhausted", "status": "RESOURCE_EXHAUSTED"}}).encode()
            self.send_response(429)
            self.send_header("Content-Type", "application/json")
            self.send_header("Retry-After", "2")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        elif SCENARIO == "e500":
            body = json.dumps({"error": {"code": 500, "message": "internal", "status": "INTERNAL"}}).encode()
            self.send_response(500)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        elif SCENARIO == "json200_error":
            # 上游 200 但 body 是 JSON 错误体（非 SSE）——GFE 限流常见
            body = json.dumps({"error": {"code": 429, "message": "Resource exhausted", "status": "RESOURCE_EXHAUSTED"}}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        def w(b):
            self.wfile.write(("%x\r\n" % len(b)).encode() + b + b"\r\n")
            self.wfile.flush()

        def end():
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()

        u = {"promptTokenCount": 12, "candidatesTokenCount": 5, "totalTokenCount": 20, "thoughtsTokenCount": 3}

        if SCENARIO == "normal":
            w(chunk([{"text": TEXT}]))
            w(chunk([], finish="STOP", usage=u))
            end()
        elif SCENARIO == "empty_body":
            end()
        elif SCENARIO == "thinking_only_max":
            for i in range(3):
                w(chunk([{"text": THOUGHT, "thought": True}]))
            w(chunk([], finish="MAX_TOKENS", usage={"promptTokenCount": 10, "candidatesTokenCount": 0, "totalTokenCount": 210, "thoughtsTokenCount": 200}))
            end()
        elif SCENARIO == "safety":
            w(chunk([{"text": "部分内容"}]))
            w(chunk([], finish="SAFETY", usage=u))
            end()
        elif SCENARIO == "mid_error":
            w(chunk([{"text": "写了一半"}]))
            time.sleep(0.2)
            w(frame({"error": {"code": 503, "message": "backend capacity exhausted", "status": "UNAVAILABLE"}}))
            end()
        elif SCENARIO == "no_finish":
            w(chunk([{"text": "正文但上游没给 finishReason"}]))
            end()
        elif SCENARIO == "split_usage":
            w(chunk([{"text": "正文"}]))
            w(chunk([], usage=u))                       # usage 单独一块，无 finishReason
            time.sleep(0.3)
            w(chunk([{"text": "后续还有内容"}]))
            w(chunk([], finish="STOP"))                 # finish 块不带 usage
            end()
        elif SCENARIO == "slow_first_token":
            time.sleep(DELAY)
            w(chunk([{"text": TEXT}]))
            w(chunk([], finish="STOP", usage=u))
            end()
        elif SCENARIO == "slow_between":
            w(chunk([{"text": "开头"}]))
            time.sleep(DELAY)
            w(chunk([{"text": "结尾"}]))
            w(chunk([], finish="STOP", usage=u))
            end()
        elif SCENARIO == "truncate_rst":
            w(chunk([{"text": "这是会被截断的正文"}]))
            self.wfile.flush()
            try:
                import struct
                self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
                self.connection.close()
            except Exception:
                pass
            return
        elif SCENARIO == "empty_candidates":
            w(chunk([], finish=None))
            w(frame({"response": {"candidates": [{"content": {"role": "model", "parts": []}}], "modelVersion": "gemini-3.8-flash-high"}}))
            w(chunk([], finish="STOP", usage=u))
            end()
        elif SCENARIO == "hang_no_data":
            # 上游接受请求后既不发送数据也不关闭——真实配额限流/NAT 静默丢弃时的表现
            time.sleep(DELAY or 300)
            end()
        elif SCENARIO == "finish_then_hold":
            # 上游发出最终帧（finishReason + usage）后不关闭连接——真实 GFE/HTTP2 常见
            w(chunk([{"text": "正文说完了"}], finish="MAX_TOKENS", usage=u))
            time.sleep(DELAY or 40)
            end()
        elif SCENARIO == "finish_no_usage_then_hold":
            w(chunk([{"text": "正文"}], finish="STOP"))
            time.sleep(DELAY or 40)
            end()
        elif SCENARIO == "abrupt_close":
            w(chunk([{"text": "半句话"}], finish="STOP", usage=u))
            # 不发终止块，直接 RST 式关闭
            try:
                self.wfile.flush()
                self.connection.close()
            except Exception:
                pass
            return
        else:
            w(chunk([{"text": "unknown scenario"}]))
            w(chunk([], finish="STOP", usage=u))
            end()

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9911
    ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()