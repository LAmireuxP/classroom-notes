#!/bin/bash
# 课堂笔记 v2 PC 构建脚本（Windows + Git Bash）
# 依赖：JDK 17（javac/keytool）、Android SDK build-tools 34、platforms;android-34
# 用法：./build-pc.sh  （产物 build/课堂笔记-v$VER.apk，版本号见下方 VER）
set -eu

SDK=${ANDROID_SDK:-/d/Zcode/android-sdk}
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-34/android.jar"
KS=${KEYSTORE:-keystore.jks}
KS_PASS=${KS_PASS:-classroom-v2}
VER=1.2

cd "$(dirname "$0")"
rm -rf build && mkdir -p build/classes build/gen build/dex

echo "=== [1/6] aapt2 compile ==="
"$BT/aapt2.exe" compile --dir src/res -o build/res.zip

echo "=== [2/6] aapt2 link ==="
"$BT/aapt2.exe" link -o build/base.apk -I "$AJ" \
  --manifest src/AndroidManifest.xml --java build/gen \
  --min-sdk-version 22 --target-sdk-version 33 build/res.zip

echo "=== [3/6] javac ==="
find src/java build/gen -name '*.java' > build/sources.txt
javac -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath "$AJ" \
  -classpath "$AJ" -d build/classes @build/sources.txt 2>/dev/null

echo "=== [4/6] d8 ==="
"$BT/d8.bat" --release --lib "$AJ" --output build/dex $(find build/classes -name '*.class')

echo "=== [5/6] 打包 ==="
python - <<'PY'
import zipfile
src = zipfile.ZipFile('build/base.apk')
out = zipfile.ZipFile('build/unsigned.apk', 'w')
for info in src.infolist():
    out.writestr(zipfile.ZipInfo(info.filename, info.date_time),
                 src.read(info.filename), compress_type=info.compress_type)
out.writestr(zipfile.ZipInfo('classes.dex'),
             open('build/dex/classes.dex', 'rb').read(),
             compress_type=zipfile.ZIP_DEFLATED)
out.close()
print('packed')
PY

"$BT/zipalign.exe" -f 4 build/unsigned.apk build/aligned.apk

echo "=== [6/6] 签名 ==="
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias classroom -keyalg RSA -keysize 2048 \
    -validity 10950 -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=Lamireux, OU=Dev, O=Personal, L=CN, ST=CN, C=CN"
fi
"$BT/apksigner.bat" sign --ks "$KS" --ks-key-alias classroom \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "build/课堂笔记-v$VER.apk" build/aligned.apk

"$BT/apksigner.bat" verify "build/课堂笔记-v$VER.apk" && echo "BUILD OK: build/课堂笔记-v$VER.apk"
