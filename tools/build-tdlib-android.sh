#!/usr/bin/env bash
set -euo pipefail
# Run on Ubuntu x64. No Telegram credentials are needed to build the library.
TD_COMMIT=42e6a5259551178d1dab54a22ad96d14bd906e20
SSL_VERSION=3.5.9
SSL_SHA=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
TD_NDK_VERSION=28.2.13676358
TD_ABI=${1:?ABI required}
TD_OUTPUT=$(realpath -m "${2:?Output directory required}")
case "$TD_ABI" in
  arm64-v8a) SSL_TARGET=android-arm64 ;;
  x86_64) SSL_TARGET=android-x86_64 ;;
  *) printf '%s\n' 'Unsupported ABI' >&2; exit 1 ;;
esac
TD_WORK=$(realpath -m ".cache/tdlib-$TD_ABI")
mkdir -p "$TD_WORK" "$TD_OUTPUT/$TD_ABI"
TD_SDK=${ANDROID_HOME:?Android SDK required}
export ANDROID_NDK_ROOT="$TD_SDK/ndk/$TD_NDK_VERSION"
export PATH="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
if [ ! -d "$TD_WORK/td/.git" ]; then
  git clone --no-checkout https://github.com/tdlib/td.git "$TD_WORK/td"
fi
git -C "$TD_WORK/td" checkout --detach "$TD_COMMIT"
test "$(git -C "$TD_WORK/td" rev-parse HEAD)" = "$TD_COMMIT"
if [ ! -f "$TD_WORK/openssl-$SSL_VERSION.tar.gz" ]; then
  curl --fail --location --retry 2 "https://github.com/openssl/openssl/releases/download/openssl-$SSL_VERSION/openssl-$SSL_VERSION.tar.gz" -o "$TD_WORK/openssl-$SSL_VERSION.tar.gz"
fi
printf '%s  %s\n' "$SSL_SHA" "$TD_WORK/openssl-$SSL_VERSION.tar.gz" | sha256sum --check
if [ ! -f "$TD_WORK/ssl/lib/libcrypto.a" ]; then
  tar -xzf "$TD_WORK/openssl-$SSL_VERSION.tar.gz" -C "$TD_WORK"
  pushd "$TD_WORK/openssl-$SSL_VERSION"
  ./Configure "$SSL_TARGET" no-shared no-tests -D__ANDROID_API__=26 --prefix="$TD_WORK/ssl" -ffunction-sections -fdata-sections
  make -j2
  make install_sw
  popd
fi
cmake -S "$TD_WORK/td/example/android" -B "$TD_WORK/generated" -G Ninja -DTD_ANDROID_JSON_JAVA=ON -DTD_GENERATE_SOURCE_FILES=ON
cmake --build "$TD_WORK/generated" --target prepare_cross_compiling -j2
cmake -S "$TD_WORK/td/example/android" -B "$TD_WORK/native" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$TD_ABI" -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo -DTD_ANDROID_JSON_JAVA=ON \
  -DOPENSSL_ROOT_DIR="$TD_WORK/ssl" -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384
cmake --build "$TD_WORK/native" --target tdjni -j2
cp "$TD_WORK/native/libtdjsonjava.so" "$TD_OUTPUT/$TD_ABI/"
"$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -lW "$TD_OUTPUT/$TD_ABI/libtdjsonjava.so" | awk '/LOAD/ { if ($NF != "0x4000" && $NF != "0x10000") exit 1; found=1 } END { if (!found) exit 1 }'
sha256sum "$TD_OUTPUT/$TD_ABI/libtdjsonjava.so" > "$TD_OUTPUT/$TD_ABI/SHA256SUMS"
