# Audeo

현대자동차그룹 차량용 앱 개발 공모전 출품작. Pleos Connect(차량 인포테인먼트, Android 14 기반)에서 동작하는 Android 앱입니다.

## 구성

| 항목 | 버전 |
| --- | --- |
| Android Gradle Plugin / Gradle | 8.13.2 / 8.14.3 |
| Kotlin / Jetpack Compose BOM | 2.3.20 / 2025.10.01 |
| compileSdk / targetSdk / minSdk | 36 / 35 / 33 |
| Pleos Vehicle SDK | 2.3.2 |
| Pleos NaviHelper SDK | 3.0.5 |
| Pleos TextToSpeech / SpeechToText SDK | 3.3.1 |
| Pleos FusedLocation SDK | 1.1.3 |

Pleos SDK는 `https://nexus-playground.pleos.ai/repository/maven-releases/`에서 받으며 토큰이 필요 없습니다. 버전은 `gradle/libs.versions.toml`에서 한곳에서 관리합니다.

FusedLocation SDK가 `android.hardware.type.automotive`를 필수 기능으로 선언하므로, 앱 마켓에서는 차량 전용 앱으로 분류됩니다. 일반 휴대폰에는 Pleos 서비스가 없어서 SDK가 동작하지 않으니 Pleos 에뮬레이터나 차량에서 실행하세요.

## 개발 환경

1. Android Studio와 JDK 17 이상(Android Studio 내장 JBR 사용 가능)
2. Pleos Connect 에뮬레이터: [설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
   - SDK Manager에 `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml` 추가
   - `system-images;android-34;pleos_car_emulator;arm64-v8a` 설치 후 AVD 생성

## 실행

```bash
./gradlew installDebug
```

휴대폰이 함께 연결돼 있으면 `ANDROID_SERIAL=emulator-5554 ./gradlew installDebug`처럼 에뮬레이터를 지정하세요.

첫 화면은 Audeo 차량 화면입니다(지도 위 다이얼로그 하나). 오른쪽 아래 `데모` 메뉴에서

- `실제 화면` / 장면 이름: 서버 상태로 그린 실제 화면과, 테스트 데이터로 그린 장면 미리보기를 오갑니다. 미리보기에서는 버튼을 눌러도 실행하지 않습니다.
- `SDK 상태`: 각 Pleos SDK가 연결할 차량 서비스가 설치돼 있는지, 버전이 최소 요구 버전 이상인지 보여 줍니다.
- `서버 다시 확인`: Audeo 서버 연결을 다시 확인합니다.

## Audeo 서버 연결

통화 처리·받아쓰기 분석·제안·조율은 Audeo 서버(`/api/v1`, 문서 `https://audeo.mosslab.dev/api/v1/docs`)가 맡고, 차량 앱은 화면과 Pleos SDK 실행을 맡습니다.
`local.properties`(커밋 안 됨)에 아래 값을 넣으면 빌드할 때 `BuildConfig`로 들어갑니다.

```properties
audeo.api.baseUrl=https://audeo.mosslab.dev/api/v1/
audeo.inviteCode=<초대 코드>
audeo.driver.name=테스트 운전자
audeo.driver.shortName=운전자
audeo.driver.phone=010-0000-0001
audeo.vehicle.label=Pleos 에뮬레이터
audeo.vehicle.plate=00가0000
```

1. 처음 켤 때 초대 코드로 차량 기기를 등록(`POST /devices`)하고 받은 기기 토큰을 앱 저장소에 둡니다. 같은 전화번호면 서버가 같은 사용자로 묶습니다.
2. Vehicle SDK로 확인한 지원 범위(공조 온도 구역, 현재 온도, 시트 열선·통풍)를 올립니다(`PUT /devices/me/capabilities`).
3. 실시간 알림은 `GET /events` long-poll로 받습니다. 통화 상태, 여정, 제안(질문·계획·변경·차량 준비), 실행 요청, 조율, Audeo가 말할 문장이 옵니다.
4. 운전자가 고른 답은 `POST /proposals/{id}/decision`으로 보냅니다. 서버가 실행을 요청하면(`execution.requested`) 그때 NaviHelper·Vehicle SDK를 부르고, SDK 콜백으로 확인된 것만 `succeeded`로 보고합니다. 응답이 없으면 `unknown`입니다.
5. 여정이 진행 중이면 15초마다 위치와 내비 남은 시간·거리를 보고합니다(`POST /journeys/{id}/status`).

통화 음성(WebRTC)은 아직 붙이지 않았습니다. 통화가 연결되면 `TestData.callTranscript` 대본을 받아쓰기 결과처럼 서버에 보내서(`POST /calls/{id}/utterances`) 실제 AI 분석을 돌립니다.

### 테스트 탑승자

탑승자 앱이 없어서 `tools/fake_passenger.py`로 상대방을 흉내 냅니다(표준 라이브러리만 사용).

```bash
python3 tools/fake_passenger.py register --invite <초대 코드> --name 김민지 --short 민지 --phone 010-0000-0002
python3 tools/fake_passenger.py run        # 걸려 온 전화를 동의하고 받기
python3 tools/fake_passenger.py call 운전자  # 차량으로 전화 걸기(수신 화면 확인)
python3 tools/fake_passenger.py late 10    # 진행 중 여정에 "10분 늦어"
```

차량 준비 제안과 도착 화면은 차가 픽업 지점에 가까워져야(남은 거리·시간) 서버가 보냅니다. 에뮬레이터 차는 움직이지 않으니, 확인하려면 같은 운전자의 다른 기기 토큰으로 가까운 상태를 한 번 보내 주세요.

## 코드 위치

- `app/src/main/java/com/audeo/app/audeo/AudeoViewModel.kt`: 화면 흐름. 서버 이벤트로 장면을 정하고, 실행 요청을 SDK로 처리합니다.
- `app/src/main/java/com/audeo/app/server/`: Audeo 서버 클라이언트(`AudeoServer`), 응답 해석(`Protocol`), 기기 토큰 저장(`DeviceStore`)
- `app/src/main/java/com/audeo/app/pleos/*Controller.kt`: 내비·공조/시트·TTS/STT·위치 SDK 연동
- `app/src/main/java/com/audeo/app/ui/audeo/`: 디자인 시스템 컴포넌트와 화면(다이얼로그, 지도, 통화 바)
- `app/src/main/java/com/audeo/app/pleos/PleosServices.kt`: SDK와 서비스 버전 확인
- `app/src/main/java/com/audeo/app/pleos/VehicleProbe.kt`: Vehicle SDK 사용 예 (initialize → API 호출 → release)
- `app/src/main/java/com/audeo/app/pleos/NaviProbe.kt`: NaviHelper SDK 사용 예 (결과는 리스너 콜백으로 수신)
- `app/src/main/AndroidManifest.xml`: Pleos 권한. 차량 데이터를 더 쓰려면 `CAR_*`(읽기), `CONTROL_CAR_*`(제어) 권한을 추가합니다.

## 권한 주의점

- Vehicle SDK 메서드마다 필요한 권한은 SDK의 `@RequiresPermission`에 적혀 있습니다. 예를 들어 누적 주행거리(`Odometer.getCurrentDistanceDriven`)는 `CAR_DRIVE`가 필요합니다. 공식 샘플 매니페스트에 있는 `CAR_MILEAGE`, `CAR_SPEED`, `CAR_TIRES` 등은 SDK 2.3.2와 에뮬레이터 2.1.0에 정의돼 있지 않습니다.
- NaviHelper는 메서드에 따라 권한이 다릅니다. 조회(`get*`, `requestTrip`)는 `NAVI_ROUTE`, 경로 요청·경유지·취소는 `NAVI_ROUTE_SEARCH`, 내비 화면 전환(`requestNavigationScreen`)은 `NAVI_CUSTOM_MAP`이 필요합니다.
- `CAR_*`, `CONTROL_CAR_*`, `NAVI_*`, `TTS_SERVICE`, `STT_SERVICE`는 normal 등급이라 설치할 때 자동으로 부여됩니다.
- `FUSED_LOCATION`은 dangerous 등급이라 위치를 쓰기 전에 `ActivityResultContracts.RequestPermission`으로 사용자에게 요청해야 합니다.

## 참고

- [Pleos 개발 문서](https://document.pleos.ai)
- 공식 샘플: [Vehicle](https://github.com/Pleos-Playground/pleos-sample-vehicle-app), [NaviHelper](https://github.com/Pleos-Playground/pleos-sample-navi-app), [FusedLocation](https://github.com/Pleos-Playground/pleos-sample-fusedlocation-app)
