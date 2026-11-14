#!/usr/bin/env python3
"""Audeo 테스트용 탑승자 웹 페이지 서버(로컬 전용).

탑승자 앱은 링크로 들어오는 웹 앱이 될 예정이라, 차량 앱 통화를 브라우저로 시험합니다.
- 127.0.0.1에서만 열고, 페이지(index.html)와 설정(/config.json)을 줍니다.
- 브라우저에서 Audeo 서버를 직접 부르면 CORS에 막혀서 REST는 /api/... 로 받아 서버로 전달합니다(토큰은 여기서 붙임).
- 실시간 이벤트·통화 신호는 브라우저가 WebSocket으로 서버에 직접 붙습니다(WebSocket은 CORS 대상이 아님).
- 탑승자 대사는 macOS `say`로 한국어 음성(audio/lineN.wav)을 처음 실행할 때 만듭니다.

  python3 tools/web_passenger/serve.py            # http://127.0.0.1:8787/?auto=1
토큰은 tools/fake_passenger.py register 로 만든 tools/.fake_passenger.json 을 씁니다.
"""
import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
STATE = os.path.join(HERE, "..", ".fake_passenger.json")
BASE = os.environ.get("AUDEO_API_BASE_URL", "https://audeo.mosslab.dev/api/v1/").rstrip("/")
PORT = int(os.environ.get("PORT", "8787"))

# 탑승자가 통화에서 하는 말(차가 받아써서 서버 AI가 약속을 정리합니다).
LINES = [
    "응, 나 오늘 인천공항으로 들어가.",
    "대한항공 케이이 칠백십이 편 타고, 세 시 사십 분에 도착해.",
    "제 이 터미널이야. 짐 찾고 나오면 네 시 십 분쯤 될 거야.",
    "바깥 날씨가 많이 춥대. 차 안 따뜻하게 해 줘.",
]


def ensure_audio():
    out = os.path.join(HERE, "audio")
    os.makedirs(out, exist_ok=True)
    for i, text in enumerate(LINES, 1):
        wav = os.path.join(out, f"line{i}.wav")
        if os.path.exists(wav):
            continue
        if not shutil.which("say"):
            print("say 명령이 없어 대사 음성을 만들지 못했어요(macOS 전용)", file=sys.stderr)
            return
        aiff = wav[:-4] + ".aiff"
        subprocess.run(["say", "-v", "Yuna", "-o", aiff, text], check=True)
        subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@48000", "-c", "1", aiff, wav], check=True)
        os.remove(aiff)


def token():
    with open(STATE, encoding="utf-8") as f:
        return json.load(f)["token"]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("%s\n" % (fmt % args))

    def _send(self, code, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.startswith("/api/"):
            return self._proxy("GET")
        if self.path == "/config.json":
            ws = BASE.replace("https://", "wss://").replace("http://", "ws://") + "/ws"
            return self._send(200, json.dumps({"ws": ws, "token": token(), "lines": len(LINES), "texts": LINES}, ensure_ascii=False))
        path = self.path.split("?", 1)[0]
        name = "index.html" if path in ("/", "") else path.lstrip("/")
        full = os.path.normpath(os.path.join(HERE, name))
        if not full.startswith(HERE) or not os.path.isfile(full):
            return self._send(404, "{}")
        ctype = {"html": "text/html; charset=utf-8", "js": "text/javascript", "wav": "audio/wav"}.get(full.rsplit(".", 1)[-1], "application/octet-stream")
        with open(full, "rb") as f:
            self._send(200, f.read(), ctype)

    def do_POST(self):
        if self.path.startswith("/api/"):
            return self._proxy("POST")
        self._send(404, "{}")

    def _proxy(self, method):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        req = urllib.request.Request(BASE + self.path[len("/api"):], data=body, method=method)
        req.add_header("Authorization", f"Bearer {token()}")
        req.add_header("Accept", "application/json")
        req.add_header("User-Agent", "Audeo-WebPassenger-Test/0.1")
        if body:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                self._send(r.status, r.read())
        except urllib.error.HTTPError as e:
            self._send(e.code, e.read())


if __name__ == "__main__":
    ensure_audio()
    print(f"탑승자 웹 페이지: http://127.0.0.1:{PORT}/?auto=1")
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
