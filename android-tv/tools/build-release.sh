#!/bin/bash
# [android-tv] 出 TV 版正式包（版本号来自 git tag，签名用 Mac 上的 TV 专属签名线）
# 用法: tools/build-release.sh            —— 需先 commit 源码并打好 tag（精确落在 HEAD）
set -euo pipefail

SIGN_ENV="${XPC_SIGN_ENV:-$HOME/.xiaopacai/signing/tvos.env}"
JAVA_HOME_DEFAULT="$HOME/jdk/jdk-17.0.20.1+1/Contents/Home"

cd "$(dirname "$0")/.."          # -> android-tv 工程根

echo "=== 前置检查 ==="
if [ ! -f "$SIGN_ENV" ]; then
  echo "缺少签名参数文件: $SIGN_ENV（见 ~/.xiaopacai/signing/README.md）" >&2
  exit 1
fi
TAG=$(git describe --tags --exact-match 2>/dev/null || true)
if [ -z "$TAG" ]; then
  echo "当前 HEAD 没有精确匹配的 tag —— 版本号会退化成 dev-<shortsha>。" >&2
  echo "正确顺序: commit 源码 -> git tag <版本号> -> 再跑本脚本。" >&2
  exit 1
fi
echo "tag=$TAG  commit=$(git rev-parse --short HEAD)"

set -a; . "$SIGN_ENV"; set +a
export JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"
[ -x "$JAVA_HOME/bin/java" ] || { echo "JAVA_HOME 无效: $JAVA_HOME" >&2; exit 1; }

echo "=== 构建 release ==="
./gradlew assembleRelease \
  -PXPC_KEYSTORE="$XPC_KEYSTORE" \
  -PXPC_KEYSTORE_PASSWORD="$XPC_KEYSTORE_PASSWORD" \
  -PXPC_KEY_ALIAS="$XPC_KEY_ALIAS" \
  -PXPC_KEY_PASSWORD="$XPC_KEY_PASSWORD" \
  --console=plain "$@"

APK=app/build/outputs/apk/release/app-release.apk
echo "=== 产物自检 ==="
BT=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/* | tail -1)
"$BT/aapt2" dump badging "$APK" | grep -E "^package" | head -1
"$BT/apksigner" verify --print-certs "$APK" | grep -E "certificate DN|SHA-256 digest" | head -2
ls -la "$APK"
shasum -a 256 "$APK" | awk '{print "sha256="$1}'
echo "期望签名者: CN=Xiaopacai TV（SHA-256 bb387353a073e26dce8766f4d81ee6e82817abd1de24b02b2a1d0a7c9209ecb4）"
