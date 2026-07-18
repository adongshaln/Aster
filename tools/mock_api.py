from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import argparse, base64, json, time
from pathlib import Path

PIXEL = "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAAAgElEQVR4nNXOQREAIAzAsFJpeMG/BBCxB9coyLpnUyZxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEidxEufvwNQDzIYCJqVrPjsAAAAASUVORK5CYII="

class Handler(BaseHTTPRequestHandler):
    label = "mock"
    models = []
    cache_keys = set()
    reset_before_seen = False
    def _json(self, payload, code=200):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code); self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_GET(self):
        if self.path == "/v1/models":
            self._json({"object": "list", "data": self.models})
        elif self.path == "/image.png":
            data = Path("ADChat_icon.png").read_bytes()
            self.send_response(200); self.send_header("Content-Type", "image/png")
            self.send_header("Connection", "close")
            self.end_headers(); self.wfile.flush(); self.connection.sendall(data + b"\x00\x00\x00\x00"); self.close_connection = True
        else:
            self._json({"error": {"message": "not found"}}, 404)
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        if self.path == "/v1/images/edits":
            if "multipart/form-data" not in self.headers.get("Content-Type", ""):
                return self._json({"error": {"message": "multipart required"}}, 400)
            array_images = raw.count(b'name="image[]"')
            single_images = raw.count(b'name="image"')
            reference_count = array_images + single_images
            print(f"[{self.label}] image edit references={reference_count} array={array_images} single={single_images}", flush=True)
            if b"multi-reference" in raw and (array_images < 2 or single_images > 0):
                return self._json({"error": {"message": "expected two image[] parts"}}, 400)
            return self._json({"data": [{"b64_json": PIXEL}], "reference_count": reference_count})
        body = json.loads(raw or b"{}")
        cache_key = body.get("prompt_cache_key", "")
        cached = 9216 if cache_key and cache_key in self.cache_keys else 0
        cache_write = 0 if cached else (10240 if cache_key else 0)
        if cache_key: self.cache_keys.add(cache_key)
        if self.path == "/v1/responses":
            prompt = body.get("input", [{}])[-1].get("content", "") if body.get("input") else ""
            if body.get("stream"):
                self.send_response(200); self.send_header("Content-Type", "text/event-stream"); self.end_headers()
                text = f"# Responses API\n\n[{self.label}] **GPT-5.6 Sol** response: {prompt}"
                for chunk in [text[i:i+18] for i in range(0, len(text), 18)]:
                    self.wfile.write(("data: " + json.dumps({"type":"response.output_text.delta","delta":chunk}, ensure_ascii=False) + "\n\n").encode("utf-8")); self.wfile.flush(); time.sleep(.05)
                usage = {"input_tokens":12000,"input_tokens_details":{"cached_tokens":cached,"cache_creation_tokens":cache_write},"output_tokens":640,"output_tokens_details":{"reasoning_tokens":320},"total_tokens":12640}
                self.wfile.write(("data: " + json.dumps({"type":"response.completed","response":{"usage":usage}}, ensure_ascii=False) + "\n\n").encode("utf-8")); self.wfile.write(b"data: [DONE]\n\n")
            else: self._json({"output_text":f"[{self.label}] response", "usage":{"input_tokens":12000,"input_tokens_details":{"cached_tokens":cached},"output_tokens":640,"total_tokens":12640}})
        elif self.path == "/v1/chat/completions":
            messages = body.get("messages", [{}])
            prompt = messages[-1].get("content", "")
            if isinstance(prompt, list):
                prompt = "".join(item.get("text", "") for item in prompt if isinstance(item, dict))
            history_text = "\n".join(
                item.get("content", "") if isinstance(item.get("content", ""), str) else ""
                for item in messages
            )
            if body.get("stream"):
                self.send_response(200); self.send_header("Content-Type", "text/event-stream; charset=utf-8"); self.end_headers()
                if "reset-before-delta" in prompt and not Handler.reset_before_seen:
                    Handler.reset_before_seen = True
                    self.close_connection = True
                    return
                if "sse-multiline" in prompt:
                    self.wfile.write(b'data: {"choices":\n')
                    self.wfile.write(b'data: [{"delta":{"content":"SSE_MULTILINE_OK"}}]}\n\n')
                    self.wfile.write(b'data: [DONE]\n\n'); self.wfile.flush()
                    return
                if "sse-no-blank" in prompt:
                    first = {"choices": [{"delta": {"content": "SSE_"}}]}
                    second = {"choices": [{"delta": {"content": "NO_BLANK_OK"}}]}
                    self.wfile.write(("data: " + json.dumps(first) + "\n").encode("utf-8"))
                    self.wfile.write(("data: " + json.dumps(second) + "\n").encode("utf-8"))
                    self.wfile.write(b'data: [DONE]\n'); self.wfile.flush()
                    return
                if "sse-raw-json" in prompt:
                    event = {"choices": [{"delta": {"content": "SSE_RAW_JSON_OK"}}]}
                    self.wfile.write((json.dumps(event) + "\n[DONE]\n").encode("utf-8")); self.wfile.flush()
                    return
                if "[ADCHAT_STREAM_RESUME]" in prompt and "reset-mid-stream-twice" in history_text:
                    chunks = ["gamma|OVERLAP-TOKEN|", "resume-partial|"]
                elif "[ADCHAT_STREAM_RESUME]" in prompt and "reset-mid-stream" in history_text:
                    chunks = ["gamma|OVERLAP-TOKEN|", "delta|epsilon|RECOVERY_DONE"]
                elif "reset-mid-stream" in prompt:
                    chunks = ["BEGIN|alpha|", "beta|gamma|", "OVERLAP-TOKEN"]
                elif "slow-stream" in prompt:
                    full = "".join(f"\n\n## \u6d41\u5f0f\u6bb5\u843d {i}\n\n\u8fd9\u662f\u7528\u4e8e\u9a8c\u8bc1\u505c\u6b62\u751f\u6210\u4e0e\u5185\u5bb9\u4fdd\u7559\u7684\u957f\u56de\u590d\u3002" for i in range(1, 121))
                    chunks = [full[i:i+18] for i in range(0, len(full), 18)]
                elif "quote-heavy" in prompt:
                    full = "\n\n".join([
                        "> \u5730\u72f1\u8bde\u751f\u4ee5\u540e\uff0c\u8389\u8389\u4e1d\u6ca1\u6709\u4eb2\u624b\u96d5\u7422\u6bcf\u4e00\u4f4d\u5b50\u55e3\u3002",
                        "> \u5979\u5c06\u81ea\u5df1\u7684\u521b\u9020\u4e4b\u529b\u57cb\u5165\u5730\u72f1\u6df1\u5904\uff0c\u8d4b\u4e88\u8fd9\u4e2a\u4e16\u754c\u6620\u7167\u4e07\u7269\u7684\u80fd\u529b\u3002",
                        "> \u81ea\u90a3\u4ee5\u540e\uff0c\u5b58\u5728\u4e8e\u4e16\u95f4\u7684\u6982\u5ff5\u5f00\u59cb\u5728\u5730\u72f1\u7559\u4e0b\u5f71\u5b50\u3002",
                        "> \u706b\u7130\u71c3\u70e7\uff0c\u5730\u72f1\u4fbf\u8bb0\u4f4f\u4e86\u706b\uff1b\u751f\u547d\u9965\u997f\uff0c\u5730\u72f1\u4fbf\u542c\u89c1\u4e86\u6c38\u4e0d\u6ee1\u8db3\u7684\u547c\u5524\u3002",
                        "> \u5f53\u4e00\u4e2a\u6982\u5ff5\u8db3\u591f\u660e\u786e\uff0c\u8db3\u591f\u53e4\u8001\uff0c\u5b83\u5728\u5730\u72f1\u4e2d\u7684\u5012\u5f71\u4fbf\u4f1a\u9010\u6e10\u83b7\u5f97\u5f62\u4f53\u3002"
                    ])
                    chunks = [full[i:i+24] for i in range(0, len(full), 24)]
                elif "markdown-table" in prompt:
                    full = """# 模型方案对比

下面的表格用于验证宽表、对齐方式、行内格式和单元格换行。

| 模型 | 适用场景 | 核心优势 | 缓存命中 | 首字延迟 |
| :--- | :--- | :--- | ---: | ---: |
| **gpt-5.6-sol** | 深度推理<br>长上下文 | 稳定续传、复杂任务表现更好 | 92.4% | `1.28s` |
| gpt-5.6-fast | 日常问答 | 响应更快，适合 A \\| B 测试 | 88.1% | `0.46s` |
| `vision | pro` | 图片理解 | 支持参考图与多模态输入 | 84.7% | `1.03s` |

表格之后的普通段落也应保持正常排版。"""
                    chunks = [full[i:i+17] for i in range(0, len(full), 17)]
                elif "markdown-long" in prompt:
                    full = "# \u6d41\u5f0f\u6807\u9898\n\n\u8fd9\u662f\u4e00\u6bb5\u5305\u542b **\u52a0\u7c97\u6587\u5b57** \u548c `inline code` \u7684\u56de\u590d\u3002\n\n## \u5185\u5bb9\u5217\u8868\n" + "\n".join(f"- \u7b2c {i} \u9879\uff1a\u7528\u4e8e\u9a8c\u8bc1\u7528\u6237\u5411\u4e0a\u6ed1\u52a8\u540e\u4e0d\u4f1a\u88ab\u81ea\u52a8\u62c9\u56de\u5e95\u90e8\u3002" for i in range(1, 55)) + "\n\n```kotlin\nfun main() {\n    println(\"ADChat markdown test\")\n}\n```"
                    chunks = [full[i:i+24] for i in range(0, len(full), 24)]
                else:
                    chunks = [f"[{self.label}] ", "\u8fde\u63a5\u6b63\u5e38\u3002", f"\u6536\u5230\uff1a{prompt}"]
                chunk_delay = .18 if "slow-stream" in prompt else .05
                for index, text in enumerate(chunks):
                    event = {"choices": [{"delta": {"content": text}}]}
                    self.wfile.write(("data: " + json.dumps(event, ensure_ascii=False) + "\n\n").encode("utf-8")); self.wfile.flush(); time.sleep(chunk_delay)
                    if "reset-mid-stream-twice" in history_text and "[ADCHAT_STREAM_RESUME]" in prompt and index >= 1:
                        self.close_connection = True
                        return
                    if "reset-mid-stream" in prompt and "[ADCHAT_STREAM_RESUME]" not in prompt and index >= 2:
                        self.close_connection = True
                        return
                    if "reset-after-delta" in prompt and index >= 1:
                        self.close_connection = True
                        return
                usage = {"prompt_tokens":12000,"prompt_tokens_details":{"cached_tokens":cached,"cache_creation_tokens":cache_write},"completion_tokens":640,"completion_tokens_details":{"reasoning_tokens":320},"total_tokens":12640}
                self.wfile.write(("data: " + json.dumps({"choices":[],"usage":usage}, ensure_ascii=False) + "\n\n").encode("utf-8"))
                self.wfile.write(b"data: [DONE]\n\n")
            else: self._json({"choices": [{"message": {"content": f"[{self.label}] 连接正常"}}]})
        elif self.path == "/v1/images/generations":
            if "slow-image" in body.get("prompt", ""):
                time.sleep(20)
            self._json({"data": [{"url": "http://10.0.2.2:8000/image.png"}]})
        else: self._json({"error": {"message": "not found"}}, 404)
    def log_message(self, fmt, *args): print(f"[{self.label}] " + fmt % args, flush=True)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(); parser.add_argument("--port", type=int, default=8000); parser.add_argument("--label", default="mock")
    args = parser.parse_args(); Handler.label = args.label
    Handler.models = ([{"id": f"{args.label}-text-fast", "owned_by": args.label}, {"id": f"{args.label}-text-pro", "owned_by": args.label}]
                      if "chat" in args.label else [{"id": f"{args.label}-image-1", "owned_by": args.label}, {"id": f"{args.label}-image-hd", "owned_by": args.label}])
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()

