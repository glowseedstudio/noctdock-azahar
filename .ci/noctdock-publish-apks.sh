#!/bin/bash -ex
# Build vanilla debug + release APKs and stage them for GitHub Releases.
# Invoked by .github/workflows/noctdock-android-release.yml

export NDK_CCACHE="$(command -v ccache || true)"

if [ -n "${ANDROID_KEYSTORE_B64}" ]; then
    export ANDROID_KEYSTORE_FILE="${GITHUB_WORKSPACE}/ks.jks"
    base64 --decode <<< "${ANDROID_KEYSTORE_B64}" > "${ANDROID_KEYSTORE_FILE}"
fi

cd src/android
chmod +x ./gradlew

./gradlew assembleVanillaDebug assembleVanillaRelease

if [ -n "${ANDROID_KEYSTORE_FILE}" ]; then
    rm -f "${ANDROID_KEYSTORE_FILE}"
fi

TAG_NAME="${GIT_TAG_NAME:-${GITHUB_REF_NAME:-dev}}"
OUT_DIR="${GITHUB_WORKSPACE}/noctdock-release-apks"
mkdir -p "${OUT_DIR}"

cp app/build/outputs/apk/vanilla/debug/app-vanilla-debug.apk \
    "${OUT_DIR}/NoctDock-Azahar-${TAG_NAME}-debug.apk"
cp app/build/outputs/apk/vanilla/release/app-vanilla-release.apk \
    "${OUT_DIR}/NoctDock-Azahar-${TAG_NAME}-release.apk"

if command -v ccache >/dev/null 2>&1; then
    ccache -s || true
fi
