#!/bin/sh
# 从 CI（apk-latest 分支）取包 → 用本机 keystore 重新签名 → 放到 /data/local/tmp/aa.apk
#
# 为什么需要重签：CI runner 没有你的密钥，会临时生成一个，签名与本地包不同，
# 直接 pm install -r 会报 INSTALL_FAILED_UPDATE_INCOMPATIBLE。
# 这里在本地用同一个 keystore 重签，产物就能覆盖安装。
#
# 用法：
#   sh /workspace/android-build/airannounce/scripts/install-ci.sh
#   （然后在 Android 侧执行）
#   chmod 644 /data/local/tmp/aa.apk && pm install -r /data/local/tmp/aa.apk
set -e

PROJECT="/workspace/android-build/airannounce"
SDK_BUILD_TOOLS="/workspace/android-build/sdk/build-tools"
KEYSTORE="$HOME/.android/debug.keystore"
OUT="/data/local/tmp/aa.apk"
TMP="/tmp/ci-raw.apk"

cd "$PROJECT"
echo "[1/3] 拉取 apk-latest"
git fetch -q origin apk-latest
git show origin/apk-latest:build-info.txt
git show origin/apk-latest:airannounce-debug.apk > "$TMP"

echo "[2/3] 用本机 keystore 重签"
APKSIGNER=$(ls "$SDK_BUILD_TOOLS"/*/apksigner 2>/dev/null | head -1)
[ -n "$APKSIGNER" ] || { echo "找不到 apksigner"; exit 1; }
"$APKSIGNER" sign \
    --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --out "$OUT" "$TMP" >/dev/null 2>&1

echo "[3/3] 校验签名"
"$APKSIGNER" verify --print-certs "$OUT" 2>/dev/null | grep -i "certificate SHA-256" | head -1
ls -l "$OUT"
echo
echo "下一步（Android 侧）："
echo "  chmod 644 /data/local/tmp/aa.apk && pm install -r /data/local/tmp/aa.apk"
