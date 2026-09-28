#!/usr/bin/env bash
# RPGMV 查看器 — 免 Gradle 构建脚本（aapt2 + javac + d8 + zipalign + apksigner）
# 用法：在项目根目录执行  ./build.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

JDK="$ROOT/tools/jdk-17.0.20.1+1"
BT="$ROOT/tools/sdk/build-tools/34.0.0"
# 相对路径传给原生工具，避免 MSYS 路径转换问题
PLATFORM_REL="tools/sdk/platforms/android-34/android.jar"

echo "==> [1/7] 生成应用图标"
"$JDK/bin/java.exe" tools/src/MakeIcon.java app/res

echo "==> [2/7] 清理目录"
rm -rf build
mkdir -p build/gen build/classes build/dex dist

echo "==> [3/7] aapt2 compile"
"$BT/aapt2.exe" compile --dir app/res -o build/res.zip

echo "==> [4/7] aapt2 link"
"$BT/aapt2.exe" link -o build/base.apk \
  -I "$PLATFORM_REL" \
  --manifest app/AndroidManifest.xml \
  --java build/gen \
  -R build/res.zip \
  --min-sdk-version 21 --target-sdk-version 34 \
  --version-code 11 --version-name 2.2 \
  --auto-add-overlay

echo "==> [5/7] javac + d8"
find build/gen app/src -name '*.java' > build/sources.txt
"$JDK/bin/javac.exe" --release 8 -encoding UTF-8 \
  -classpath "$PLATFORM_REL" -d build/classes @build/sources.txt
find build/classes -name '*.class' > build/classes.txt
"$JDK/bin/java.exe" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release --lib "$PLATFORM_REL" --min-api 21 \
  --output build/dex @build/classes.txt

echo "==> [6/7] 打包 dex + 对齐"
(cd build/dex && "$JDK/bin/jar.exe" uf ../base.apk classes.dex)
"$BT/zipalign.exe" -f 4 build/base.apk build/aligned.apk

echo "==> [7/7] 生成密钥并签名"
if [ ! -f tools/rpgmv.keystore ]; then
  "$JDK/bin/keytool.exe" -genkeypair -alias rpgmv -keyalg RSA -keysize 2048 -validity 10950 \
    -keystore tools/rpgmv.keystore -storepass rpgmvviewer -keypass rpgmvviewer \
    -dname "CN=RPGMV Viewer, OU=DeepWork, O=DeepWork, C=CN" >/dev/null 2>&1
fi
"$JDK/bin/java.exe" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool sign \
  --ks tools/rpgmv.keystore --ks-pass pass:rpgmvviewer --ks-key-alias rpgmv \
  --out dist/MangaFlow-v2.2.apk build/aligned.apk

echo "==> 验证"
"$JDK/bin/java.exe" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool verify dist/MangaFlow-v2.2.apk \
  && echo "签名验证 OK"
"$BT/aapt2.exe" dump badging dist/MangaFlow-v2.2.apk | head -8
sha256sum dist/MangaFlow-v2.2.apk
echo ""
echo "构建完成: dist/MangaFlow-v2.2.apk"
