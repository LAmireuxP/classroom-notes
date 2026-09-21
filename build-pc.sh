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
VER=1.2.1

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
  # 密钥文件缺失时**不要**默默生成新的。
  #
  # Android 只允许「签名一致」的 APK 覆盖升级。这里悄悄造一把新钥匙，签出来的包
  # 和此前所有版本签名都不同，老用户会装不上——而 apksigner verify 依然报「成功」
  # （它只验签名有效性，不比对历史指纹），发布时根本察觉不到。
  # 所以改成必须显式确认。
  echo "" >&2
  echo "!!! 找不到签名密钥：$KS" >&2
  echo "    正式发布请勿自动生成新密钥 —— 那会导致所有老用户无法覆盖升级。" >&2
  echo "    确认要用新密钥（仅限首次搭建），请显式执行：" >&2
  echo "        ALLOW_NEW_KEYSTORE=1 ./build-pc.sh" >&2
  echo "" >&2
  [ "${ALLOW_NEW_KEYSTORE:-0}" = "1" ] || exit 1
  echo "  （已确认）生成新密钥 $KS ..."
  keytool -genkeypair -keystore "$KS" -alias classroom -keyalg RSA -keysize 2048 \
    -validity 10950 -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=Lamireux, OU=Dev, O=Personal, L=CN, ST=CN, C=CN"
fi

# 打出指纹，发布前随手核对（正常应恒为 32:A8:95:D3:...）
echo "--- 签名密钥指纹 ---"
keytool -list -keystore "$KS" -storepass "$KS_PASS" | grep -i 'SHA256' || true
echo "--------------------"

"$BT/apksigner.bat" sign --ks "$KS" --ks-key-alias classroom \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "build/课堂笔记-v$VER.apk" build/aligned.apk

"$BT/apksigner.bat" verify "build/课堂笔记-v$VER.apk" && echo "BUILD OK: build/课堂笔记-v$VER.apk"
