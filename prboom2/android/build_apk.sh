#!/bin/bash
# Builds PrBoom+ RT for Android (arm64-v8a) into a signed APK, without Gradle.
#
# Required environment:
#   ANDROID_HOME    SDK with platforms;android-34, build-tools;35.0.0, ndk;27.2.12479018
#   RTGL1_SDK_PATH  RayTracedGL1 checkout (ray-query-android branch)
#   SDL2_SRC        SDL 2.30.x source checkout
#   IWAD            path to freedoom1.wad (bundled as the default game)
#   HOST_BUILD      a native (desktop) build dir of prboom2, for the rdatawad tool
# Optional:
#   OUT             output folder (default: ./out)
#   KEYSTORE        signing keystore (default: a generated debug key in $OUT)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PRBOOM2="$(cd "$HERE/.." && pwd)"
OUT="${OUT:-$PWD/out}"
NDK="$ANDROID_HOME/ndk/27.2.12479018"
BT="$ANDROID_HOME/build-tools/35.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
API=31
ABI=arm64-v8a
VERSION_CODE="${VERSION_CODE:-4}"
VERSION_NAME="${VERSION_NAME:-2.6.1-rt1.0.2-android4}"
TOOLCHAIN=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
           -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$API -DCMAKE_BUILD_TYPE=Release)
JOBS="$(nproc)"

mkdir -p "$OUT"
B="$OUT/build"
mkdir -p "$B"

echo "== RayTracedGL1"
cmake -S "$RTGL1_SDK_PATH" -B "$B/rtgl1" "${TOOLCHAIN[@]}" -DRG_WITH_SURFACE_ANDROID=ON > "$B/rtgl1.log"
cmake --build "$B/rtgl1" -j"$JOBS" >> "$B/rtgl1.log"

echo "== RayTracedGL1 shaders"
mkdir -p "$B/shaders"
( cd "$RTGL1_SDK_PATH/Source/Shaders"
  for f in *.comp *.vert *.frag; do
    glslc --target-env=vulkan1.2 -I. -I../Generated "$f" -o "$B/shaders/$f.spv"
  done )

echo "== SDL2"
cmake -S "$SDL2_SRC" -B "$B/sdl2" "${TOOLCHAIN[@]}" -DSDL_STATIC=OFF -DSDL_SHARED=ON -DSDL_TEST=OFF > "$B/sdl2.log"
cmake --build "$B/sdl2" -j"$JOBS" >> "$B/sdl2.log"

echo "== PrBoom+ RT"
RTGL1_SDK_PATH="$RTGL1_SDK_PATH" cmake -S "$PRBOOM2" -B "$B/prboom" "${TOOLCHAIN[@]}" \
    -DIMPORT_EXECUTABLES="$HOST_BUILD/ImportExecutables.cmake" \
    -DSDL2_ANDROID_SOURCE_DIR="$SDL2_SRC" -DSDL2_ANDROID_LIBRARY="$B/sdl2/libSDL2.so" \
    -DRTGL1_ANDROID_LIBRARY="$B/rtgl1/libRayTracedGL1.so" \
    -DWITH_IMAGE=OFF -DWITH_MIXER=OFF -DWITH_NET=OFF -DWITH_PCRE=OFF -DWITH_ZLIB=OFF \
    -DWITH_MAD=OFF -DWITH_FLUIDSYNTH=OFF -DWITH_DUMB=OFF -DWITH_VORBISFILE=OFF \
    -DWITH_PORTMIDI=OFF -DWITH_ALSA=OFF > "$B/prboom.log"
RTGL1_SDK_PATH="$RTGL1_SDK_PATH" cmake --build "$B/prboom" -j"$JOBS" >> "$B/prboom.log"

echo "== Java"
J="$B/java"; rm -rf "$J"; mkdir -p "$J/classes" "$J/dex"
javac -nowarn -Xlint:none -Xlint:-options --release 8 -XDstringConcat=inline -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$J/classes" \
    "$HERE"/app/java/org/libsdl/app/*.java \
    "$HERE"/app/java/org/prboomplus/rt/*.java
"$BT/d8" --release --min-api $API --lib "$ANDROID_JAR" --output "$J/dex" $(find "$J/classes" -name '*.class')

echo "== Assets"
S="$B/stage"; rm -rf "$S"; mkdir -p "$S/assets/ovrd/shaders" "$S/assets/licenses" "$S/lib/$ABI"
cp "$B/shaders/"*.spv "$S/assets/ovrd/shaders/"
cp "$RTGL1_SDK_PATH/Tools/BlueNoise_LDR_RGBA_128.ktx2" "$S/assets/ovrd/"
python3 -I "$HERE/tools/make_water_normal_ktx2.py" "$S/assets/ovrd" > /dev/null
cp "$HERE/ovrd/textures_metainfo.txt" "$S/assets/ovrd/"
cp "$B/prboom/prboom-plus.wad" "$S/assets/"
cp "$IWAD" "$S/assets/freedoom1.wad"
cp "$HERE/licenses/"* "$S/assets/licenses/"
cp "$SDL2_SRC/LICENSE.txt" "$S/assets/licenses/SDL2-LICENSE.txt"
cp "$PRBOOM2/COPYING" "$S/assets/licenses/PRBOOM-PLUS-COPYING.txt"
cp "$RTGL1_SDK_PATH/LICENSE" "$S/assets/licenses/RTGL1-LICENSE.txt"

echo "== Native libraries"
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
for so in "$B/rtgl1/libRayTracedGL1.so" "$B/sdl2/libSDL2.so" "$B/prboom/src/libmain.so"; do
    "$STRIP" --strip-unneeded -o "$S/lib/$ABI/$(basename "$so")" "$so"
done

echo "== APK"
"$BT/aapt2" compile --dir "$HERE/app/res" -o "$B/res.zip"
"$BT/aapt2" link -o "$B/unaligned.apk" -I "$ANDROID_JAR" \
    --manifest "$HERE/app/AndroidManifest.xml" \
    --min-sdk-version $API --target-sdk-version 34 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    -A "$S/assets" "$B/res.zip"
( cd "$J/dex" && zip -q "$B/unaligned.apk" classes.dex )
( cd "$S" && zip -q -r "$B/unaligned.apk" lib )

# 16 KB page alignment for native libs (Android 15+ devices)
"$BT/zipalign" -f -P 16 4 "$B/unaligned.apk" "$B/aligned.apk"

KEYSTORE="${KEYSTORE:-$OUT/debug.keystore}"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=PrBoom Plus RT Android Debug" > /dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/prboom-plus-rt-android-arm64.apk" "$B/aligned.apk"
"$BT/apksigner" verify "$OUT/prboom-plus-rt-android-arm64.apk"
echo "== Done: $OUT/prboom-plus-rt-android-arm64.apk"
