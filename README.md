# Audeo (어디오)

> 대화만으로 약속 생성부터 차량 행동, 픽업까지 이어 주는 AI 만남 모빌리티 서비스

현대자동차그룹 차량용 앱 개발 공모전 출품작입니다. 운전자와 탑승자가 Audeo로 통화하면, 두 사람이 동의한 경우 AI 비서 '오디'가 대화를 듣고 픽업 여정(만날 시각·장소·교통편·짐·동행)을 만듭니다. 그 뒤로는 열차 지연, 차량 연료, 교통 체증, 날씨 같은 상황 변화를 보고 출발 시각, 경유지, 경로, 승차 지점, 공조를 먼저 제안합니다. 운전자가 터치나 음성("응, 바꿔 줘")으로 승인하면 Pleos SDK로 내비게이션과 차량 기능을 실행합니다.

이 저장소는 **Pleos Connect 차량 앱**(Android, Kotlin, Jetpack Compose)입니다.

| 구성 | 저장소 | 역할 |
| --- | --- | --- |
| 차량 앱 | `Audeo-2026/app` (이 저장소) | 운전자 화면, 통화, Pleos SDK(내비·차량·STT/TTS·위치) 실행 |
| AI 오케스트레이션 서버 | `Audeo-2026/audeo-server` | 통화 신호, 대화 분석(LLM), 여정·제안·조율, 시연 탑승자 |
| 탑승자 앱 | `Audeo-2026/app-passenger` | 탑승자 휴대폰(Android·iOS). 통화 수신·분석 동의, 제안 응답, 지도, 차 찾기 나침반 |

## APK

| 항목 | 내용 |
| --- | --- |
| 파일 | [`apk/Audeo_Audeo_release.apk`](apk/Audeo_Audeo_release.apk) |
| 빌드 | release, 서명됨, ABI `arm64-v8a`(Mac 에뮬레이터·차량), `x86_64`(Windows·Linux 에뮬레이터) |
| 패키지 / 버전 | `com.audeo.app` / 0.1.0 (versionCode 1) |
| 접속 서버 | `https://audeo.mosslab.dev/api/v1/` (APK에 들어 있음, 따로 설정할 것 없음) |

## 실행 방법

### 1. 에뮬레이터 준비

1. Android Studio SDK Manager → SDK Update Sites에 `Pleos Connect System Image`를 추가합니다.
   - macOS(Apple Silicon): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
   - Windows·Linux: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
2. SDK Platforms → Android API 34 → **Pleos Connect system Image**를 설치하고, `Pleos Connect v2.1.0`으로 AVD를 만듭니다.
3. AVD Boot options를 **Cold boot**로 둡니다(Quick boot에서는 차량 데이터(VHAL)가 동작하지 않아요).
4. 에뮬레이터 마이크 입력을 켭니다(Extended controls → Microphone → "Virtual microphone uses host audio input").

자세한 내용은 [Pleos Connect 에뮬레이터 설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)를 참고하세요.

### 2. 설치와 첫 실행

```bash
adb install -r apk/Audeo_Audeo_release.apk
```

1. 앱 목록에서 **Audeo**를 엽니다. 위치·마이크 권한을 물으면 허용합니다.
2. 처음 켜면 APK에 들어 있는 초대 코드로 이 차량을 서버에 자동으로 등록합니다. 회원가입이나 입력할 것은 없어요.
3. 시나리오처럼 차를 왕십리 근처에 두려면 에뮬레이터 위치를 옮깁니다.

   ```bash
   adb emu geo fix 127.0371 37.5612
   ```

### 3. 대표 시나리오 시연 (휴대폰 없이 차량 앱만으로)

서버에 **시연 탑승자 '지호 (시연)'**가 있어서, 상대방 기기가 없어도 기획서 4장 시나리오를 처음부터 끝까지 볼 수 있습니다. 서버가 지호 역할을 맡아 전화를 받고, 대본의 대화를 실제 통화 발화처럼 분석에 넣습니다.

1. 첫 화면 오디 시트의 연락처에서 **지호 (시연)**를 눌러 전화를 겁니다.
2. 통화 카드에 자막이 흐르고, '오디가 확인한 것' 줄에 서버의 처리 기록이 차례로 나옵니다.
   - "번호는 몰라! 지금 광명 지나!" → 열차 시간표로 탑승 열차를 찾고 서울역 도착 시각을 확인해요.
   - "엄마가 오래 걷기 힘드셔", "캐리어 두 개", "밥도 못 먹었어", "오늘 좀 춥네" → 탑승 조건으로 기록해요.
3. 통화가 끝나면 픽업 계획 카드(만날 곳·시각·출발 시각)가 뜹니다. 터치하거나 "응"이라고 말해 승인하면 내비게이션 목적지를 설정해요.
4. 그 뒤로 서버가 시간 흐름에 따라 상황을 하나씩 일으킵니다. 앞 질문에 답해야 다음 상황이 나와요(한 번에 질문 하나).

| 단계 | 상황 | 오디의 제안 | 사용 SDK |
| --- | --- | --- | --- |
| ② | 열차 17분 지연 + "밥도 못 먹었어" | 드라이브스루 경유 | NaviHelper `addWaypoint` |
| ④ | 연료 11% + 픽업 후 부평 이동 | 주유소 경유 | NaviHelper `addWaypoint` |
| ⑤ | 도착 예정 시각이 크게 늦어짐 | 대체 경로(경유지 유지) | NaviHelper `changeRouteOption`·`requestReRoute` |
| ⑥ | 추운 날씨 + "오늘 좀 춥네" | 공조 24°, 탑승자 좌석 열선 | Vehicle HVAC·Seat |
| ⑦ | 탑승자 보행 부담 + 캐리어 | 엘리베이터 가까운 승하차장 | 서버 승하차장 DB |
| ⑧ | 만날 곳 300m 진입 + 기어 P | 문 잠금 해제, 트렁크 열기 | FusedLocation `addProximityAlert`, Vehicle Gear·Door |

열차 지연, 연료 잔량, 체증 같은 시연 값은 화면에 **'시연 데이터'**로 표시해서 실제 조회 결과와 구분합니다.

시연 탑승자와의 통화는 음성 연결 없이 서버 대본이 통화 카드 자막으로 흐르고, 통화 카드에 "시연 통화"로 표시돼요. 시나리오를 처음부터 다시 보려면 주행 화면 오디 시트를 펼쳐 **픽업 취소**를 누르면 첫 화면으로 돌아가요.

화면 오른쪽 위 모서리를 길게 누르면 개발 도구(낮/밤 전환, SDK 상태, 서버 다시 확인, 받아쓰기 시험)가 열립니다. 화면 구성·테스트 도구 같은 개발 메모는 [`docs/development.md`](docs/development.md)에 있어요.

### 실제 사람과 통화하기 (선택)

탑승자 앱(`Audeo-2026/app-passenger`)을 휴대폰에 설치하고 같은 초대 코드로 등록하면, 차량 연락처에 그 사람이 나타납니다. 통화 음성은 WebRTC로 차와 휴대폰을 직접 잇고, 두 사람이 모두 동의하면 차에서 Pleos STT로 받아써서 서버로 보냅니다.

## 테스트 환경 및 필요한 설정

| 항목 | 내용 |
| --- | --- |
| 실행 환경 | Pleos Connect 에뮬레이터 v2.1.0 (Android 14, API 34) |
| 개발 PC | macOS(Apple Silicon, `arm64-v8a` 시스템 이미지) |
| 네트워크 | 인터넷 연결 필요(Audeo 서버, 지도 타일, 경로 서버) |
| 서버 | `https://audeo.mosslab.dev` (심사 기간 동안 운영) |
| 권한 | 위치(`FUSED_LOCATION`)·마이크(`RECORD_AUDIO`)는 실행 중 허용. 나머지 Pleos 권한(`CAR_*`, `CONTROL_CAR_*`, `NAVI_*`, `TTS_SERVICE`, `STT_SERVICE`)은 설치할 때 자동으로 부여 |
| 따로 할 설정 | 없음. 서버 주소와 초대 코드가 APK에 들어 있어요 |

- 앱이 `android.hardware.type.automotive`를 요구해서(FusedLocation SDK) **일반 휴대폰에는 설치되지 않습니다.** Pleos 에뮬레이터나 차량에서 실행하세요.
- 에뮬레이터 차는 스스로 움직이지 않습니다. 차가 가까워지는 장면(⑦·⑧)은 `adb emu geo fix`로 위치를 옮기거나, 서버의 시연 상황을 기다리면 볼 수 있어요.
- 서버에 연결되지 않으면 오디 시트에 "Audeo 서버에 연결되지 않았어요"가 뜨고, 점점 간격을 늘려 가며 다시 연결합니다.

## 개발 환경

### Pleos Connect 에뮬레이터 버전과 화면 규격

| 항목 | 값 |
| --- | --- |
| 에뮬레이터 | Pleos Connect Emulator **v2.1.0** (2026-10-19 배포) |
| 시스템 이미지 | Pleos Connect system Image, Android 14 (API 34), `arm64-v8a` |
| 화면 규격 | **2560 × 1440** (가로) |

### 빌드 도구와 SDK

| 항목 | 버전 |
| --- | --- |
| Android Gradle Plugin / Gradle | 8.13.2 / 8.14.3 |
| Kotlin / Jetpack Compose BOM | 2.3.20 / 2025.10.01 |
| compileSdk / targetSdk / minSdk | 36 / 35 / 33 |
| Pleos Vehicle SDK | 2.3.2 |
| Pleos NaviHelper SDK | 3.0.5 |
| Pleos TextToSpeech / SpeechToText SDK | 3.3.1 |
| Pleos FusedLocation SDK | 1.1.3 |
| WebRTC / MapLibre | `libs.versions.toml` 참고 |

Pleos SDK는 `https://nexus-playground.pleos.ai/repository/maven-releases/`에서 받으며 토큰이 필요 없습니다. 버전은 `gradle/libs.versions.toml` 한곳에서 관리합니다.

### 소스에서 빌드하기

JDK 17 이상과 Android SDK가 필요합니다(Android Studio 내장 JBR 사용 가능). `local.properties`(Git에 올리지 않음)에 SDK 경로와 서버 설정을 넣으면, 빌드할 때 `BuildConfig`로 들어갑니다.

```properties
sdk.dir=/Users/<나>/Library/Android/sdk

audeo.api.baseUrl=https://audeo.mosslab.dev/api/v1/
audeo.inviteCode=<초대 코드>
audeo.driver.name=테스트 운전자
audeo.driver.shortName=운전자
audeo.driver.phone=010-0000-0001
audeo.vehicle.label=Pleos 에뮬레이터
audeo.vehicle.plate=00가0000
```

```bash
./gradlew installDebug        # 에뮬레이터에 바로 설치
./gradlew assembleRelease     # release APK
```

휴대폰이 함께 연결돼 있으면 `ANDROID_SERIAL=emulator-5554 ./gradlew installDebug`처럼 에뮬레이터를 지정하세요.

## 동작 구조

통화 처리, 받아쓰기 분석, 제안, 조율은 Audeo 서버(`/api/v1`, 문서 `https://audeo.mosslab.dev/api/v1/docs`)가 맡고, 차량 앱은 화면과 Pleos SDK 실행을 맡습니다. **AI는 들은 내용을 구조화할 뿐이고, 제안 여부는 서버 규칙이 정하며, 실행은 운전자가 승인한 뒤에만 합니다.**

1. 처음 켤 때 초대 코드로 차량 기기를 등록하고(`POST /devices`), 받은 기기 토큰을 앱 저장소에 둡니다. 같은 전화번호면 서버가 같은 사용자로 묶습니다.
2. Vehicle SDK로 확인한 지원 범위(공조 온도 구역, 현재 온도, 시트 열선·통풍, 실행할 수 있는 명령)를 올립니다(`PUT /devices/me/capabilities`). 서버는 차가 지원하는 기능만 제안합니다.
3. 실시간 알림은 `GET /events` long-poll로 받습니다. 통화 상태, 여정, 제안(질문·계획·변경·경유지·경로·차량 준비), 실행 요청, 조율, 오디가 말할 문장이 옵니다.
4. 운전자가 고른 답은 `POST /proposals/{id}/decision`으로 보냅니다. 서버가 실행을 요청하면(`execution.requested`) 그때 NaviHelper·Vehicle SDK를 부르고, SDK 콜백으로 확인된 것만 `succeeded`로 보고합니다. 응답이 없으면 `unknown`입니다.
5. 여정이 진행 중이면 15초마다 위치와 내비 남은 시간·거리, 실내 온도·기어·착석·벨트·창문·문 상태를 보고합니다(`POST /journeys/{id}/status`). 만날 곳 300m에 들어서면(`addProximityAlert`) 바로 한 번 더 보고합니다.

제안마다 결정할 사람은 서버가 `approver`로 정합니다. 운전자 몫(`driver`)이면 차 화면에 선택지가 뜨고, 탑승자 몫(`passenger`)이면 차에는 "○○님이 고르고 있어요"만 보이고 탑승자 앱에 선택지가 뜹니다.

### 사용한 Pleos SDK

| SDK | 쓰는 곳 |
| --- | --- |
| NaviHelper | `requestRoute`(목적지), `addWaypoint`(드라이브스루·주유소 경유), `changeRouteOption`·`requestReRoute`(대체 경로, 재탐색 응답이 없으면 넣어 둔 경유지와 함께 `requestRouteWithWaypoints`), DrivingInfo(남은 시간·거리) |
| Vehicle | HVAC(온도·에어컨·공기청정·내외기·김서림), Seat(`setHvacSeatTemperature`·`setHvacSeatVentilation`, 착석·안전벨트), Window, Door(잠금·트렁크, 기어 P일 때만), Gear |
| SpeechToText / TextToSpeech | 통화 받아쓰기, 오디 음성 안내, "응/아니" 음성 승인 |
| FusedLocation | 차 위치 보고, `addProximityAlert`(만날 곳 300m 진입) |

## 개발용 도구

탑승자 앱 없이 PC에서 상대방을 흉내 낼 수 있습니다.

```bash
python3 tools/fake_passenger.py register --invite <초대 코드> --name 김민지 --short 민지 --phone 010-0000-0002
python3 tools/fake_passenger.py run        # 걸려 온 전화를 동의하고 받기
python3 tools/fake_passenger.py call 운전자  # 차량으로 전화 걸기(수신 화면 확인)
python3 tools/fake_passenger.py late 10    # 진행 중 여정에 "10분 늦어"
```

통화 음성(WebRTC)까지 시험하려면 탑승자 웹 페이지를 씁니다.

```bash
python3 tools/web_passenger/serve.py   # http://127.0.0.1:8787/?auto=1 (전화가 오면 동의하고 받고, 한국어 대사를 통화로 말해요)
```

- KTX 대본: `?scenario=ktx`
- 내 목소리로 통화하기: `?mic=1` (Chrome 마이크를 통화로 보내요. 이어폰을 끼세요)
- 통화 음성 녹음: `&record=1`을 붙이면 통화가 끝날 때 `tools/web_passenger/recordings/`(또는 `AUDEO_REC_DIR`)에 webm으로 저장해요

에뮬레이터와 연결하려면 Chrome을 `--disable-features=WebRtcHideLocalIpsWithMdns`로 열어야 합니다(로컬 IP를 mDNS로 숨기면 에뮬레이터가 찾지 못해요).

## 코드 위치

- `app/src/main/java/com/audeo/app/audeo/AudeoViewModel.kt`: 화면 흐름. 서버 이벤트로 장면을 정하고, 실행 요청을 SDK로 처리합니다.
- `app/src/main/java/com/audeo/app/server/`: Audeo 서버 클라이언트(`AudeoServer`), 응답 해석(`Protocol`), 기기 토큰 저장(`DeviceStore`)
- `app/src/main/java/com/audeo/app/pleos/*Controller.kt`: 내비·공조/시트/창문/문·TTS/STT·위치 SDK 연동
- `app/src/main/java/com/audeo/app/ui/audeo/`: 디자인 시스템 컴포넌트와 화면
  - 오디(`Mascot.kt`): 그림 파일 없이 코드로 그린 캐릭터. 말할 때 입이 움직이고, 들을 때 안테나에서 물결이 퍼지고, 생각할 때 눈웃음을 지어요.
  - 비서 시트(`Sheet.kt`): 왼쪽은 오디의 말과 근거, 오른쪽은 고를 수 있는 길을 의사결정 트리처럼 펼치고 고르면 어떻게 되는지 함께 적어요.
  - 통화 카드(`CallPanel.kt`): 참가자 얼굴을 나란히 두고, 말하는 사람에게 초록 고리가 생겨요.
  - 지도(`LiveMap.kt`): [MapLibre](https://maplibre.org) + [OpenFreeMap](https://openfreemap.org) 타일(키·요금 없음, 한국어 이름) + [OSRM](https://project-osrm.org) 공개 경로 서버. 실제 길안내는 차의 내비(NaviHelper)가 합니다.
- `app/src/main/java/com/audeo/app/pleos/PleosServices.kt`: SDK와 서비스 버전 확인
- `app/src/main/AndroidManifest.xml`: Pleos 권한

## 권한 주의점

- Vehicle SDK 메서드마다 필요한 권한은 SDK의 `@RequiresPermission`에 적혀 있습니다. 예를 들어 누적 주행거리(`Odometer.getCurrentDistanceDriven`)는 `CAR_DRIVE`가 필요합니다. 공식 샘플 매니페스트에 있는 `CAR_MILEAGE`, `CAR_SPEED`, `CAR_TIRES` 등은 SDK 2.3.2와 에뮬레이터 2.1.0에 정의돼 있지 않습니다.
- NaviHelper는 메서드에 따라 권한이 다릅니다. 조회(`get*`, `requestTrip`)는 `NAVI_ROUTE`, 경로 요청·경유지·취소는 `NAVI_ROUTE_SEARCH`, 내비 화면 전환(`requestNavigationScreen`)은 `NAVI_CUSTOM_MAP`이 필요합니다.
- `CAR_*`, `CONTROL_CAR_*`, `NAVI_*`, `TTS_SERVICE`, `STT_SERVICE`는 normal 등급이라 설치할 때 자동으로 부여됩니다.
- `FUSED_LOCATION`은 dangerous 등급이라 위치를 쓰기 전에 사용자에게 요청합니다.

## 참고

- [Pleos 개발 문서](https://document.pleos.ai)
- 공식 샘플: [Vehicle](https://github.com/Pleos-Playground/pleos-sample-vehicle-app), [NaviHelper](https://github.com/Pleos-Playground/pleos-sample-navi-app), [FusedLocation](https://github.com/Pleos-Playground/pleos-sample-fusedlocation-app)
