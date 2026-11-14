#!/usr/bin/env python3
"""Audeo 테스트용 가짜 탑승자.

탑승자 앱이 아직 없어서, 차량 앱의 통화·동의·지연·도착 흐름을 에뮬레이터에서 끝까지 보려고 만든 도구입니다.
표준 라이브러리만 씁니다.

  python3 tools/fake_passenger.py register --invite <초대 코드> --name 김민지 --short 민지 --phone 010-0000-0002
  python3 tools/fake_passenger.py run              # 걸려 온 전화를 동의하고 받고, 이벤트를 출력
  python3 tools/fake_passenger.py call 운전자       # 이름에 '운전자'가 들어간 연락처에게 전화(차량 수신 테스트)
  python3 tools/fake_passenger.py late 10          # 진행 중 여정에 "10분 늦어"
  python3 tools/fake_passenger.py ready baggage    # 준비 상태 보고(in_transit, baggage, walking, at_spot …)

토큰은 tools/.fake_passenger.json(커밋 안 됨)에 저장합니다. AUDEO_API_BASE_URL로 서버를 바꿀 수 있어요.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("AUDEO_API_BASE_URL", "https://audeo.mosslab.dev/api/v1/").rstrip("/")
STATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".fake_passenger.json")


def load():
    try:
        with open(STATE, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        sys.exit("먼저 register 로 탑승자 기기를 등록하세요.")


def api(method, path, body=None, token=None, timeout=20):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"{BASE}/{path}", data=data, method=method)
    req.add_header("Accept", "application/json")
    req.add_header("User-Agent", "Audeo-FakePassenger/0.1")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            text = r.read().decode()
            return json.loads(text) if text else {}
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} /{path} → {e.code} {e.read().decode()}")


def active_journey(token):
    for j in api("GET", "journeys", token=token).get("journeys", []):
        if j.get("status") not in ("completed", "cancelled"):
            return j["id"]
    sys.exit("진행 중인 여정이 없어요.")


def cmd_register(a):
    body = {"invite_code": a.invite, "kind": "phone", "display_name": a.name, "short_name": a.short, "phone": a.phone}
    r = api("POST", "devices", {k: v for k, v in body.items() if v})
    with open(STATE, "w", encoding="utf-8") as f:
        json.dump({"token": r["device_token"], "user_id": r["user"]["user_id"], "name": r["user"]["name"]}, f, ensure_ascii=False)
    print(f"등록됨: {r['user']['name']} ({r['user']['call_name']})")


def cmd_run(a):
    st = load()
    token = st["token"]
    after = api("GET", "events?after_seq=0&wait_s=0", token=token).get("last_seq", 0)
    print(f"{st['name']} 대기 중 (Ctrl+C로 끝)")
    while True:
        try:
            r = api("GET", f"events?after_seq={after}&wait_s=25", token=token, timeout=45)
        except (urllib.error.URLError, TimeoutError) as e:
            print("연결 끊김:", e)
            time.sleep(3)
            continue
        for e in r.get("events", []):
            t, d = e["type"], e.get("data", {})
            if t == "call.incoming":
                print(f"전화 옴: {d['from']['name']} → {a.answer_after}초 뒤 동의하고 받기")
                time.sleep(a.answer_after)
                api("POST", f"calls/{d['id']}/answer", {"analysis_consent": not a.no_ai}, token=token)
            elif t == "agent.say":
                print(f"[Audeo → {d.get('role')}] {d.get('text')}")
            elif t.startswith("proposal."):
                print(f"{t}: {d.get('kind')} {d.get('status')} · {d.get('title')}")
            elif t == "call.state":
                print(f"통화 {d.get('status')} (분석 {'켜짐' if d.get('analysis_on') else '꺼짐'})")
            else:
                print(t)
        after = r.get("last_seq", after)


def cmd_call(a):
    token = load()["token"]
    contacts = api("GET", "contacts", token=token)["contacts"]
    target = next((c for c in contacts if a.who in c["name"]), None)
    if not target:
        sys.exit(f"'{a.who}' 연락처가 없어요: " + ", ".join(c["name"] for c in contacts))
    r = api("POST", "calls", {"callee_id": target["user_id"], "analysis_requested": True, "caller_role": "passenger"}, token=token)
    print(f"{target['name']}에게 전화 거는 중: {r['id']}")


def cmd_late(a):
    token = load()["token"]
    jid = active_journey(token)
    api("POST", f"journeys/{jid}/voice", {"text": f"수하물이 늦게 나와서 {a.minutes}분 늦어"}, token=token)
    print(f"{jid}: {a.minutes}분 늦는다고 보냈어요")


def cmd_ready(a):
    token = load()["token"]
    jid = active_journey(token)
    api("POST", f"journeys/{jid}/status", {"client_seq": int(time.time()), "ready": a.state}, token=token)
    print(f"{jid}: ready={a.state}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("register")
    r.add_argument("--invite", required=True)
    r.add_argument("--name", default="테스트 탑승자")
    r.add_argument("--short")
    r.add_argument("--phone")
    r.set_defaults(fn=cmd_register)
    r = sub.add_parser("run")
    r.add_argument("--answer-after", type=float, default=2.0)
    r.add_argument("--no-ai", action="store_true", help="AI 분석에 동의하지 않고 받기")
    r.set_defaults(fn=cmd_run)
    r = sub.add_parser("call")
    r.add_argument("who")
    r.set_defaults(fn=cmd_call)
    r = sub.add_parser("late")
    r.add_argument("minutes", type=int, nargs="?", default=10)
    r.set_defaults(fn=cmd_late)
    r = sub.add_parser("ready")
    r.add_argument("state", choices=["unknown", "in_transit", "baggage", "preparing", "ready", "walking", "at_spot"])
    r.set_defaults(fn=cmd_ready)
    a = p.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
