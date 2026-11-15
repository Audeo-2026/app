# 차량 앱 ↔ 서버 맞추기 지침 (제출 전 최종 수정용)

- 기준 서버: `Audeo-2026/audeo-server` main `7470fe2` (2026-11-15, `https://audeo.mosslab.dev`에 자동 배포)
- 기준 앱: `feat/vehicle-actions` (`ee910e2`)
- 서버 API 전체: `audeo-server/docs/api.md`, 대화형 문서 `https://audeo.mosslab.dev/api/v1/docs`

이번 서버 커밋으로 **오케스트레이션 루프**(식사·주유 경유, 대체 경로, 승차 지점), **시연 탑승자 '지호 (시연)'**, 경유지·승차 지점 후보가 배포 서버에 들어갔어요. 심사위원은 차량 APK 하나로 지호에게 전화해서 시나리오 ①~⑧을 봐야 하니, 아래 **P0는 제출 전에 꼭** 해 주세요.

> 서버가 앱에 맞추지 않고 앱이 서버에 맞추는 이유: 서버 규칙(한 번에 질문 하나, 승인 후 실행, 시연 데이터 표시)은 기획서 5장에 그대로 적혀 있고 테스트로 묶여 있어요. 앱에서 바꾸는 쪽이 안전해요.

---

## P0. 제출 전 필수

### 1. 시연 탑승자 통화

지금 앱은 지호에게 전화를 걸면 일반 통화처럼 WebRTC와 STT를 켜요. 상대 기기가 없으니 **"음성 연결 중"에서 멈추고**, 운전자 마이크의 STT가 대본과 섞여 서버로 들어가요.

| 할 일 | 위치 | 내용 |
| --- | --- | --- |
| `demo` 파싱 | `server/Protocol.kt` 연락처, `audeo/Model.kt` `Contact` | `/contacts` 응답의 `demo: true`를 `Contact.demo`로 받기. 서버는 시연 탑승자의 `note`를 "시연 탑승자 · 전화하면 서버가 받아요"로 보내요 |
| 시연 통화 구분 | `AudeoViewModel.callContact()` | 건 상대가 `demo`면 그 통화 id를 기억해 두기(예: `demoCallId`) |
| WebRTC·STT 끄기 | `startCallAudio()`, `updateTranscription()` | 시연 통화면 `callAudio.start()`와 `transcriber.start()`를 부르지 않기. 통화 카드 상태 문구는 "음성 연결 중" 대신 "시연 통화" 같은 것으로 |
| `call.transcript` 처리 | `server/Protocol.kt` 이벤트, `AudeoViewModel` | 새 이벤트. 아래 모양을 받아 통화 카드 자막으로 띄우기 |

```jsonc
// type = "call.transcript" (운전자에게만, 시연 통화에서만 옴)
{"call_id": "…", "seq": 100003, "role": "passenger", "speaker": "지호", "text": "번호는 몰라! 지금 광명 지나!", "demo": true}
```

- 자막은 지금 STT 결과를 띄우는 `onTranscribed()`와 같은 방식이면 돼요: `VoiceUi(VoiceMode.LISTENING, who = "${speaker}이 말하는 중", caption = "“$text”")`. 단 **서버로 `utterances`를 다시 보내면 안 돼요**(서버가 이미 넣었어요).
- `agent.thinking`의 `highlights`(예: `광명 지나`, `오늘 좀 춥네`)는 지금 코드처럼 보이는 자막에 그 말이 있을 때만 강조하면 그대로 동작해요.
- 대본 11줄이 끝나면 **서버가 통화를 끊어요**(`call.state` ended). 그 뒤 계획 카드는 지금 흐름 그대로 나와요.

### 2. 새 제안 종류 `stop`, `route`

지금 `resolveScene()`은 모르는 `kind`를 `Scene.PLAN`으로 보내서, 드라이브스루·주유소 경유와 대체 경로가 **'계획' 카드로 잘못 보여요.** `PROPOSAL_ORDER`에도 없어서 다른 제안 뒤로 밀려요.

| kind | 언제 | 선택지 `id` | 승인하면 오는 실행 명령 |
| --- | --- | --- | --- |
| `stop` | ② 열차 지연 + "밥도 못 먹었어" → 드라이브스루, ④ 연료 부족 → 주유소 | `accept`("들를게요") / `no`("그냥 갈게요") | `{"op":"addWaypoint","waypoint":{lat,lng,poi_name,address},"index":"FIRST"}` |
| `route` | ⑤ 도착 예정이 기준보다 6분 이상 늦어짐 | `accept`("바꿔 줘") / `no`("지금 길로") | `{"op":"requestRoute","destination":{…},"route_option":"SHORT_TIME","reroute":true}` |

- `AudeoViewModel.PROPOSAL_ORDER` = `listOf("ask", "change", "stop", "route", "vehicle_action", "plan")`
- `resolveScene()`: `"stop"`, `"route"` → `Scene.CHANGE`로 보내면 지금 변경 카드(근거 `reasons`, `rows` from→to, `options`)가 그대로 맞아요. 시간이 되면 `Scene.STOP`/`Scene.ROUTE`를 따로 두고 kicker를 "경유지 제안", "대체 경로"로 바꿔 주세요.
- 두 제안 모두 `approver = driver`이고 `voice_prompt`가 있어요. "응, 바꿔 줘"/"들를게"는 지금 음성 승인 흐름(`via: voice`)으로 가면 돼요. 서버는 **가장 최근 제안 하나에만** 음성 승인을 적용해요.
- `reasons[].source == "demo"`면 근거 옆에 '시연 데이터'를 표시해 주세요(`SourceKind.DEMO`는 이미 있어요). 열차 17분 지연, 연료 11%, 체증 9분이 여기 해당해요.
- 실행: `addWaypoint`, `requestRoute`는 이미 구현돼 있어요. `reroute: true`를 보면 `NaviHelper.requestReRoute()` 또는 `changeRouteOption(route_option)`을 쓰면 기획서에 적힌 API와 맞아요(선택). 결과는 지금처럼 SDK 콜백으로 확인된 것만 `succeeded`.

### 3. release APK와 저장소

| 할 일 | 내용 |
| --- | --- |
| 서명 | `app/build.gradle.kts`의 `release`에 `signingConfig` 추가. 지금 `assembleRelease`는 `app-release-unsigned.apk`가 나와서 설치되지 않아요. 키스토어 파일과 비밀번호는 커밋하지 말고 `local.properties`나 환경 변수로 |
| 초대 코드 | 빌드할 때 `audeo.inviteCode`(또는 `AUDEO_INVITE_CODE`)를 넣어야 해요. 없으면 첫 화면에서 "초대 코드(audeo.inviteCode)" 오류가 나요. 심사용 코드는 한창훈에게 받아 주세요 |
| 용량 | 지금 release APK가 139MB라 GitHub 파일 한도(100MB)를 넘어요. Pleos 에뮬레이터는 arm64라 `defaultConfig { ndk { abiFilters += "arm64-v8a" } }`로 줄여 주세요. 그래도 크면 GitHub Releases에 올리고 그 주소를 제출해요 |
| 파일 | `apk/<팀명>_<앱이름>_release.apk`. `.gitignore`에 `*.apk`가 있으니 `!apk/*.apk` 예외 추가 |
| README | 제출 양식 항목으로 새로 써 뒀어요(`README.md`). 화면 규격 2560×1440은 `adb shell wm size`로 확인해서 고쳐 주세요. APK 파일명이 바뀌면 README 두 군데(APK 표, `adb install`)도 같이 |
| 브랜치 | 기본 브랜치가 `setup/pleos-project`이에요. 최종본을 기본 브랜치에 병합하고 **그 커밋 해시**를 접수 메일에 적어요 |

### 4. 끝까지 한 번 돌려 보기

Pleos 에뮬레이터(cold boot)에 release APK를 설치하고 아래가 모두 보이면 돼요.

1. 첫 화면 연락처에 **지호 (시연)**이 보임
2. 전화를 걸면 1.5초 뒤 받고, 자막 11줄이 흐르고, 생각 줄에 "KTX 341 → 서울역 도착 확인"이 나옴
3. 통화가 끝나면 계획 카드 → 승인하면 내비 목적지 설정
4. 차례로: 만날 곳 변경(엘리베이터 가까운 승하차장) → 드라이브스루 경유(`stop`) → 주유소 경유(`stop`) → 대체 경로(`route`) → 공조·열선 → 도착 안내
5. 근거에 '시연 데이터' 표시

막히면 `GET /journeys/{id}/agent/trace`로 서버가 무엇을 보고 왜 넘겼는지 볼 수 있어요. 상황을 바로 넣으려면 `POST /journeys/{id}/demo/events` `{"kind":"train_delay","minutes":17}`.

---

## P1. 기획서 5장과 맞추기 (시간이 되면)

기획서 'SDK/API 사용 방안'에 적었는데 앱에서 아직 부르지 않는 것들이에요. 심사위원이 코드와 대조할 수 있어요.

### 5. 차량 상태에 에너지 값 보고

`POST /journeys/{id}/status`의 `vehicle`에 지금은 `cabin_c, hvac_on, ac_on, seat_heat, windows_open, doors_locked, gear, occupied, belted`만 들어가요. 아래를 추가해 주세요. 모르는 값은 빼면 돼요.

| 필드 | Vehicle SDK |
| --- | --- |
| `fuel_pct` | `Fuel.getFuelPercent` |
| `ev_soc` | `EvBattery.getEvBatteryStateOfCharge` |
| `range_km` | `Odometer.getRangeRemainingDistance` |
| `seat_vent` | `Seat.getHvacSeatVentilationState` (0이 아닌 것만, `{"ROW_1_RIGHT": 2}`) |
| `soc_at_dest` | NaviHelper `requestTrip` → `onTripInfo.estimatedSocAtDest` (선택) |

> ⚠️ 서버는 앱이 `fuel_pct`·`ev_soc`·`range_km` 중 하나라도 보내면 **시연 값(연료 11%) 대신 실제 값**을 써요. 에뮬레이터 연료가 넉넉하면 ④ 주유소 경유가 안 나와요. 서버 쪽 처리가 정해질 때까지는 **시연 탑승자와의 여정에서는 이 세 값을 빼고** 보내 주세요(여정의 탑승자가 `demo` 연락처인지로 구분).

### 6. 내비 즐겨찾기·최근 목적지 올리기

기기 등록 직후(그리고 앱을 켤 때 한 번) NaviHelper `getBookmarkInfo`·`getRecentDestinationInfo` 결과를 올려 주세요. 서버는 STT가 장소 이름을 잘못 들었을 때 이걸 후보로 써요(예: '관명역' → 광명역).

```jsonc
// PUT /devices/me/saved-places (통째로 교체)
{"bookmarks": [{"name": "집", "lat": 37.49, "lng": 126.72, "address": "인천 부평구 …", "poi_id": "…"}],
 "recent":    [{"name": "서울역", "lat": 37.5547, "lng": 126.9707}]}
```

권한은 `NAVI_ROUTE`(이미 매니페스트에 있어요).

### 7. 체증 정보 (선택)

내비가 체증을 직접 알려 주면 `/status`에 `extra.traffic = {"delay_min": 9, "section": "강변북로"}`를 같이 보내 주세요. 안 보내도 서버가 `navi.remain_s`로 계산해요.

---

## P2. 있으면 좋은 것

- **`agent.step` 이벤트**: 루프가 제안 없이 내린 결론 한 줄 `{skill, decision: skip|say, summary}`(예: "만날 곳이 가까워 식사 경유는 넘겼어요"). 생각 줄에 회색으로 보여 주면 '오디가 판단하고 있다'가 잘 드러나요. 저장하지 않는 이벤트라 놓쳐도 괜찮아요.
- **README의 낡은 문장**: "지금 서버는 만날 곳이 바뀌는 변경도 `driver`로 보내서…"는 이제 맞지 않아요. 만날 곳이 바뀌는 `change`는 `approver = driver`, `also_confirm = passenger`(탑승자에게도 확인 카드)예요.

---

## 탑승자 앱 담당(`audio-app-passenger`)에게 같이 전할 것

- `ask` 제안은 `audience = ["driver","passenger"]`, `approver = null`이에요. 서버는 **둘 중 누구의 답이든** 받아요. 지금 탑승자 앱은 `approver == "passenger"`일 때만 버튼을 보여 줘서, 탑승자에게 온 질문이 버튼 없이 "운전자가 확인하고 있어요"로 떠요. `approver == null && "passenger" in audience`일 때도 버튼을 보여 주세요(`ui/Screens.kt` `ProposalCard`).
- 만날 곳이 바뀌는 `change`에 `also_confirm == "passenger"`가 있으면 탑승자에게 "확인했어요" 정도의 확인을 보여 주면 기획서 ⑦과 맞아요.

---

## 바꾸지 말아야 할 것 (서버 규칙과 맞물려 있어요)

- 실행 결과는 **SDK 콜백으로 확인된 것만** `succeeded`. 모르면 `unknown`
- 문 명령은 실행 직전에 기어를 다시 확인하고, P가 아니면 실행하지 말고 `failed`(detail `GEAR_NOT_PARK`)
- capabilities의 `ops`에는 **앱이 실제로 실행할 수 있는 명령만** 넣기. 서버는 `ops`에 있는 명령만 제안해요
- 운전 중 질문은 한 번에 하나. 서버가 이미 막지만, 앱도 대기 중인 질문이 있으면 새 카드를 위로 덮지 말고 순서대로
