#!/usr/bin/env bash
# 제출용 release APK 를 만들어 apk/ 에 넣는다.
# 서명 키·초대 코드는 local.properties(audeo.release.*, audeo.inviteCode)에서 읽는다. 서명 키가 없으면 debug 키로 서명된다.
set -euo pipefail
cd "$(dirname "$0")/.."
NAME="${1:-Audeo_Audeo_release.apk}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
grep -q '^audeo.inviteCode=.\+' local.properties 2>/dev/null || { echo "local.properties 에 audeo.inviteCode 가 없어요" >&2; exit 1; }
./gradlew -q :app:assembleRelease
mkdir -p apk
cp app/build/outputs/apk/release/app-release.apk "apk/$NAME"
"$(ls -d "$ANDROID_HOME"/build-tools/* | tail -1)/apksigner" verify "apk/$NAME" && echo "서명 확인됨"
ls -lh "apk/$NAME"
echo "commit: git add -f apk/$NAME && git commit -m 'release: 제출용 APK'"
