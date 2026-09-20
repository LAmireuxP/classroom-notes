#!/bin/bash
# 课堂整理 v2 —— 一键构建脚本（Android 原生，无 Gradle）
set -u

PROJ=/sdcard/Download/Operit/classroom_v2
TOOLS=/tmp/tools
OUT=$PROJ/build
PKG=com.lamireuxp.classroom

echo "=== [1/7] 清理 ==="
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

echo "=== [2/7] 编译资源 (aapt2 compile) ==="
"$TOOLS/aapt2" compile --dir "$PROJ/res" -o "$OUT/res.zip" 2>&1 | grep -v tzdata || true

echo "=== [3/7] 链接资源 (aapt2 link) ==="
"$TOOLS/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$TOOLS/android.jar" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 22 \
  --target-sdk-version 33 \
  "$OUT/res.zip" 2>&1 | grep -v tzdata || true

echo "=== [4/7] 编译 Java (javac) ==="
find "$PROJ/java" -name '*.java' > "$OUT/sources.txt"
find "$OUT/gen" -name '*.java' >> "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$TOOLS/android.jar" \
  -classpath "$TOOLS/android.jar" \
  -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1
JAVAC_RC=$?
grep -v 'bootstrap class path' "$OUT/javac.log" | grep -v '^Note:' || true

if [ $JAVAC_RC -ne 0 ] || [ ! -d "$OUT/classes/com" ]; then
  echo "!!! Java 编译失败 (rc=$JAVAC_RC)"
  exit 1
fi

echo "=== [5/7] 转 dex (d8) ==="
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$TOOLS/r8.jar" com.android.tools.r8.D8 \
  --min-api 22 --lib "$TOOLS/android.jar" \
  --output "$OUT/dex" @"$OUT/classes.txt" 2>&1 | head -20

if [ ! -f "$OUT/dex/classes.dex" ]; then
  echo "!!! d8 失败"
  exit 1
fi

echo "=== [6/7] 打包 APK ==="
cd "$OUT"
python3 - <<'EOF'
import zipfile, shutil, os
shutil.copy('base.apk','unsigned.apk')
z=zipfile.ZipFile('unsigned.apk','a')
z.write('dex/classes.dex','classes.dex')
z.close()
print('packed')
EOF

echo "=== [7/7] 签名 ==="
java -jar "$TOOLS/apksigner.jar" sign \
  --ks "$PROJ/release.keystore" \
  --ks-pass pass:classroom2026 --key-pass pass:classroom2026 \
  --ks-key-alias classroom \
  --min-sdk-version 22 \
  --out "$OUT/classroom-v2-signed.apk" \
  "$OUT/unsigned.apk" 2>&1 | head

if [ -f "$OUT/classroom-v2-signed.apk" ]; then
  cp "$OUT/classroom-v2-signed.apk" /sdcard/Download/classroom-v2.apk
  echo ""
  echo "======================================"
  echo "✅ 构建成功"
  ls -l /sdcard/Download/classroom-v2.apk
  echo "======================================"
else
  echo "!!! 签名失败"
  exit 1
fi