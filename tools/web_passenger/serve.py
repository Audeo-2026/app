#!/usr/bin/env python3
"""Audeo 테스트용 탑승자 웹 페이지 서버(로컬 전용).

탑승자 앱은 링크로 들어오는 웹 앱이 될 예정이라, 차량 앱 통화를 브라우저로 시험합니다.
- 127.0.0.1에서만 열고, 페이지(index.html)와 설정(/config.json)을 줍니다.
- 브라우저에서 Audeo 서버를 직접 부르면 CORS에 막혀서 REST는 /api/... 로 받아 서버로 전달합니다(토큰은 여기서 붙임).
- 실시간 이벤트·통화 신호는 브라우저가 WebSocket으로 서버에 직접 붙습니다(WebSocket은 CORS 대상이 아님).
- 탑승자 대사는 macOS `say`로 한국어 음성(audio/lineN.wav)을 처음 실행할 때 만듭니다.

  python3 tools/web_passenger/serve.py            # http://127.0.0.1:8787/?auto=1
  내 목소리로: http://127.0.0.1:8787/?mic=1 (이어폰 권장), 통화 녹음: &record=1
  상황 고르기: &scenario=cold(기본, 추운 날 공항) | hot(더운 날 에어컨) | train(KTX 서울역)
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
# ?record=1 로 녹음한 통화 음성을 저장할 곳
REC_DIR = os.environ.get("AUDEO_REC_DIR", os.path.join(HERE, "recordings"))

# 상황별로 탑승자가 통화에서 하는 말(차가 받아써서 서버 AI가 약속을 정리합니다). 페이지 주소의 ?scenario= 로 고릅니다.
# 서버는 지금 시각과 만남 시각을 비교해(차가 늦으면 변경 제안, 만남이 가까우면 차량 준비 제안) 움직이므로,
# 도착·만남 시각은 페이지를 열 때 지금 기준으로 정합니다: {arr} = 약 20분 뒤 도착, {meet} = 그 25분 뒤.
SCENARIOS = {
    # 비 오고 추운 날 공항 픽업(기본)
    "cold": [
        "응, 나 오늘 인천공항으로 들어가.",
        "대한항공 케이이 칠백십이 편 타고, {arr}에 도착해.",
        "제 이 터미널이야. 짐 찾고 나오면 {meet}쯤 될 거야.",
        "바깥 날씨가 많이 춥대. 차 안 따뜻하게 해 줘.",
    ],
    # 더운 날: 에어컨을 틀어 달라는 요청
    "hot": [
        "응, 나 오늘 인천공항으로 들어가.",
        "대한항공 케이이 칠백십이 편 타고, {arr}에 도착해.",
        "제 이 터미널 삼번 출구로 나갈게. {meet}쯤 될 거야.",
        "오늘 날씨가 너무 덥대. 에어컨 좀 시원하게 틀어 줘.",
    ],
    # 비행기가 아니라 기차(KTX)
    "train": [
        "나 오늘 부산에서 케이티엑스 타고 서울역으로 올라가.",
        "케이티엑스 이십사 열차고, {arr}에 서울역 도착이야.",
        "서울역 서부역 쪽으로 나갈게. 캐리어 하나 있어.",
        "오늘 좀 덥대. 차 안 시원하게 해 줘.",
    ],
}
LINES = SCENARIOS["cold"]


HOURS = ["열두", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열", "열한"]
DIGITS = ["", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구"]


def say_time(t):
    """14:25 → '오후 두 시 이십오 분' (TTS가 읽기 좋게 한글로)."""
    part = "오전" if t.hour < 12 else ("오후" if t.hour < 18 else "저녁")
    m = t.minute
    mins = "" if m == 0 else ((DIGITS[m // 10] if m >= 20 else "") + ("십" if m >= 10 else "") + DIGITS[m % 10] + " 분")
    return f"{part} {HOURS[t.hour % 12]} 시 {mins}".strip()


def timed_lines(name):
    """지금 기준 도착·만남 시각을 넣은 대사."""
    import datetime
    now = datetime.datetime.now()
    arr = now + datetime.timedelta(minutes=20)
    arr = arr.replace(minute=(arr.minute // 5) * 5, second=0, microsecond=0)
    meet = arr + datetime.timedelta(minutes=25)
    return [l.format(arr=say_time(arr), meet=say_time(meet)) for l in SCENARIOS[name]], arr.strftime("%H%M")


def ensure_audio():
    for name in SCENARIOS:
        lines, stamp = timed_lines(name)
        make_audio(os.path.join(HERE, "audio", name, stamp), lines)


def make_audio(out, lines):
    os.makedirs(out, exist_ok=True)
    for i, text in enumerate(lines, 1):
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
        if self.path.startswith("/config.json"):
            ws = BASE.replace("https://", "wss://").replace("http://", "ws://") + "/ws"
            q = dict(p.split("=", 1) for p in self.path.partition("?")[2].split("&") if "=" in p)
            name = q.get("scenario", "cold") if q.get("scenario") in SCENARIOS else "cold"
            lines, stamp = timed_lines(name)
            make_audio(os.path.join(HERE, "audio", name, stamp), lines)
            files = [f"audio/{name}/{stamp}/line{i}.wav" for i in range(1, len(lines) + 1)]
            return self._send(200, json.dumps({"ws": ws, "token": token(), "scenario": name, "lines": len(lines), "texts": lines, "files": files}, ensure_ascii=False))
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
        if self.path.startswith("/upload"):
            # 페이지가 녹음한 통화 음성(webm). t0 = 녹음을 시작한 시각(ms)
            q = dict(p.split("=", 1) for p in self.path.partition("?")[2].split("&") if "=" in p)
            t0 = "".join(ch for ch in q.get("t0", "0") if ch.isdigit()) or "0"
            os.makedirs(REC_DIR, exist_ok=True)
            path = os.path.join(REC_DIR, f"call-{t0}.webm")
            with open(path, "wb") as f:
                f.write(self.rfile.read(int(self.headers.get("Content-Length") or 0)))
            return self._send(200, json.dumps({"file": path}))
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
