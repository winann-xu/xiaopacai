#!/bin/bash
# [xiaopacai] 手机端出包脚本（Mac 为唯一签名来源）
#   tools/build-release.sh stable   —— 正式线（com.xiaopacai.parent，正式签名）
#   tools/build-release.sh special  —— 特别版（strictTestkey 变体，AOSP testkey 签名）
#   tools/build-release.sh debug    —— 仅本地调试用
# 版本号说明：手机端 versionName 由 build.gradle.kts 内的版本常量/属性决定（非 tag 驱动），
# 发版前请先确认 versionCode 单调递增。
set -euo pipefail

MODE="${1:-stable}"
SIGN_DIR="$HOME/.xiaopacai/signing"
JAVA_HOME_DEFAULT="$HOME/jdk/jdk-17.0.20.1+1/Contents/Home"
cd "$(dirname "$0")/.."          # -> android 工程根

export JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"
[ -x "$JAVA_HOME/bin/java" ] || { echo "JAVA_HOME 无效: $JAVA_HOME" >&2; exit 1; }
[ -f local.properties ] || echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

BT=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/* | tail -1)

case "$MODE" in
  stable)
    ENV_FILE="$SIGN_DIR/parent-stable.env"
    [ -f "$ENV_FILE" ] || { echo "缺少 $ENV_FILE（见 ~/.xiaopacai/signing/README.md）" >&2; exit 1; }
    set -a; . "$ENV_FILE"; set +a
    if [ ! -f "$XPC_KEYSTORE" ]; then
      echo "❌ 正式线 keystore 不存在: $XPC_KEYSTORE" >&2
      echo "   必须从旧构建机拷来同一个 .jks（指纹 d4fac8e9d7fedc229c30aa067865af813b5f068ca3e533f9f7da7b54ecf0c488），" >&2
      echo "   否则不能出正式包——换签名会让老用户装不上。" >&2
      exit 1
    fi
    echo "=== 构建 stable（正式签名） ==="
    ./gradlew assembleRelease --console=plain \
      -PXPC_KEYSTORE="$XPC_KEYSTORE" -PXPC_STORE_PASS="$XPC_STORE_PASS" \
      -PXPC_KEY_ALIAS="$XPC_KEY_ALIAS" -PXPC_KEY_PASS="$XPC_KEY_PASS"
    APK=$(ls app/build/outputs/apk/release/app-release*.apk | head -1)
    EXPECT_DN="CN=Xiaopacai, OU=ChildGuard, O=Xiaopacai, L=Shanghai, ST=Shanghai, C=CN"
    ;;
  special)
    ENV_FILE="$SIGN_DIR/parent-testkey.env"
    [ -f "$ENV_FILE" ] || { echo "缺少 $ENV_FILE" >&2; exit 1; }
    set -a; . "$ENV_FILE"; set +a
    [ -f "$XPC_TESTKEY" ] || { echo "❌ testkey.jks 不存在: $XPC_TESTKEY" >&2; exit 1; }
    echo "=== 构建 special（strictTestkey，AOSP testkey 签名） ==="
    ./gradlew assembleStrictTestkey --console=plain -PXPC_TESTKEY="$XPC_TESTKEY"
    APK=$(ls app/build/outputs/apk/strictTestkey/*.apk | head -1)
    EXPECT_DN="EMAILADDRESS=android@android.com, CN=Android, OU=Android, O=Android, L=Mountain View, ST=California, C=US"
    ;;
  debug)
    echo "=== 构建 debug ==="
    ./gradlew assembleDebug --console=plain
    APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)
    EXPECT_DN="(debug 签名，不可发布)"
    ;;
  *) echo "用法: $0 [stable|special|debug]" >&2; exit 2 ;;
esac

echo "=== 产物自检 ==="
"$BT/aapt2" dump badging "$APK" | grep -E "^package" | head -1
"$BT/apksigner" verify --print-certs "$APK" | grep -E "certificate DN|SHA-256 digest" | head -2
ls -la "$APK"
shasum -a 256 "$APK" | awk '{print "sha256="$1}'
echo "期望签名者 DN: $EXPECT_DN"
