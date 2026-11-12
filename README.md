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

첫 화면은 각 Pleos SDK가 연결할 차량 서비스가 설치돼 있는지, 버전이 SDK의 최소 요구 버전 이상인지 보여 줍니다. Vehicle은 누적 주행거리를 한 번 읽고, NaviHelper는 기본 내비 앱에 경로 상태를 요청해 실제 연결을 확인합니다.

## 코드 위치

- `app/src/main/java/com/audeo/app/pleos/PleosServices.kt`: SDK와 서비스 버전 확인
- `app/src/main/java/com/audeo/app/pleos/VehicleProbe.kt`: Vehicle SDK 사용 예 (initialize → API 호출 → release)
- `app/src/main/java/com/audeo/app/pleos/NaviProbe.kt`: NaviHelper SDK 사용 예 (결과는 리스너 콜백으로 수신)
- `app/src/main/AndroidManifest.xml`: Pleos 권한. 차량 데이터를 더 쓰려면 `CAR_*`(읽기), `CONTROL_CAR_*`(제어) 권한을 추가합니다.

## 권한 주의점

- Vehicle SDK 메서드마다 필요한 권한은 SDK의 `@RequiresPermission`에 적혀 있습니다. 예를 들어 누적 주행거리(`Odometer.getCurrentDistanceDriven`)는 `CAR_DRIVE`가 필요합니다. 공식 샘플 매니페스트에 있는 `CAR_MILEAGE`, `CAR_SPEED`, `CAR_TIRES` 등은 SDK 2.3.2와 에뮬레이터 2.1.0에 정의돼 있지 않습니다.
- `CAR_*`, `CONTROL_CAR_*`, `NAVI_*`, `TTS_SERVICE`, `STT_SERVICE`는 normal 등급이라 설치할 때 자동으로 부여됩니다.
- `FUSED_LOCATION`은 dangerous 등급이라 위치를 쓰기 전에 `ActivityResultContracts.RequestPermission`으로 사용자에게 요청해야 합니다.

## 참고

- [Pleos 개발 문서](https://document.pleos.ai)
- 공식 샘플: [Vehicle](https://github.com/Pleos-Playground/pleos-sample-vehicle-app), [NaviHelper](https://github.com/Pleos-Playground/pleos-sample-navi-app), [FusedLocation](https://github.com/Pleos-Playground/pleos-sample-fusedlocation-app)
