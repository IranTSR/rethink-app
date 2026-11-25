#!/usr/bin/env bash
#
# build-aether.sh — build the vendored Aether engine (native/aether) from
# source into app/src/main/jniLibs/<abi>/, plus our own JNI bridge.
#
# Outputs per ABI:
#   libaether.so    <- the Aether engine, cross-compiled from Rust with
#                      cargo-ndk. The crate builds a cdylib
#                      (aether/Cargo.toml: crate-type = ["lib", "staticlib",
#                      "cdylib"]). Upstream publishes NO Android binaries,
#                      so this step is mandatory — there is no prebuilt core.
#   libaetherjni.so <- OUR OWN JNI bridge (scripts/aether-jni.c), compiled
#                      with the NDK clang. It forwards the Kotlin natives in
#                      AetherCore.kt to the engine's plain-C FFI
#                      (aether_* in aether/src/ffi.rs) and exposes setenv()
#                      so the app can set the engine's AETHER_* options.
#                      No Gradle change is needed: Gradle packages
#                      app/src/main/jniLibs automatically.
#
# Usage:  bash scripts/build-aether.sh
#
# Requires: ANDROID_NDK_HOME, rustup with the Android targets, cargo-ndk.
# Env knobs:
#   ANDROID_API     NDK platform level (default 26 — matches the mira
#                   Aether-app build; the engine is therefore only offered
#                   on devices running API 26+, see AetherCore.isSupported()).
#   AETHER_FEATURES cargo features for the engine (default "tor", i.e. the
#                   arti-based Tor modes. Set to "" for a no-Tor build; the
#                   bridge then reports hasTor=false and the UI hides Tor).
#   AETHER_ABIS     space-separated ABIs (default "arm64-v8a armeabi-v7a").
#                   x86/x86_64 are intentionally skipped: Rethink ships
#                   those APK splits for emulators only, and doubling the
#                   (already long) Rust build for them is not worth it.
#   CARGO_NET_OFFLINE=1 honoured by cargo as usual.
#
# Verification (fails the build instead of shipping a broken .so):
#   - libaether.so must export every FFI symbol AetherCore.kt needs
#   - libaetherjni.so must export JNI_OnLoad + every Java_* bridge symbol
#   - libaetherjni.so must not DT_NEEDED anything but system libs + libaether.so
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CORE_DIR="${PROJECT_DIR}/native/aether"
CRATE_DIR="${CORE_DIR}/aether"
JNI_DIR="${PROJECT_DIR}/app/src/main/jniLibs"
BRIDGE_SRC="${SCRIPT_DIR}/aether-jni.c"

API="${ANDROID_API:-26}"
# NB: do NOT write this as ABIS=(${AETHER_ABIS:-"arm64-v8a armeabi-v7a"}):
# a quoted default inside ${var:-"..."} expands to ONE word, so the loop
# below would see a single "ABI" called "arm64-v8a armeabi-v7a" and die.
# read -ra splits the value (or the default) on whitespace, always.
read -ra ABIS <<< "${AETHER_ABIS:-arm64-v8a armeabi-v7a}"
FEATURES="${AETHER_FEATURES:-tor}"

[ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "${ANDROID_NDK_HOME}" ] \
  || { echo "ERROR: ANDROID_NDK_HOME is not set or does not exist." >&2; exit 1; }
command -v cargo >/dev/null || { echo "ERROR: cargo not on PATH (run the CI rust-toolchain step)." >&2; exit 1; }
command -v cargo-ndk >/dev/null || { echo "ERROR: cargo-ndk not on PATH (cargo install cargo-ndk --locked)." >&2; exit 1; }
[ -f "${CRATE_DIR}/Cargo.toml" ] || { echo "ERROR: vendored crate missing: ${CRATE_DIR}/Cargo.toml (run scripts/sync-core.sh --check)." >&2; exit 1; }
[ -f "${BRIDGE_SRC}" ] || { echo "ERROR: bridge source missing: ${BRIDGE_SRC}" >&2; exit 1; }

CORE_VERSION="$(sed -n '1p' "${CORE_DIR}/CORE_VERSION" | tr -d '[:space:]')"
echo "==> [aether] vendored core v${CORE_VERSION} (features: '${FEATURES:-<none>}')"

# NDK LLVM toolchain (host tag differs per runner OS).
NDK_TOOLCHAIN=""
for host in linux-x86_64 darwin-x86_64 windows-x86_64; do
  if [ -d "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${host}/bin" ]; then
    NDK_TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${host}/bin"
    break
  fi
done
[ -n "${NDK_TOOLCHAIN}" ] || { echo "ERROR: no NDK LLVM toolchain under ${ANDROID_NDK_HOME}" >&2; exit 1; }
echo "==> NDK toolchain: ${NDK_TOOLCHAIN}"

clang_for_abi() {
  case "$1" in
    arm64-v8a)   echo "${NDK_TOOLCHAIN}/aarch64-linux-android${API}-clang" ;;
    armeabi-v7a) echo "${NDK_TOOLCHAIN}/armv7a-linux-androideabi${API}-clang" ;;
    *) echo "" ;;
  esac
}
triple_for_abi() {
  case "$1" in
    arm64-v8a)   echo "aarch64-linux-android" ;;
    armeabi-v7a) echo "armv7-linux-androideabi" ;;
    *) echo "" ;;
  esac
}

# FFI symbols the Kotlin layer needs (must stay in sync with AetherCore.kt
# and with scripts/sync-core.sh).
FFI_SYMBOLS="aether_version aether_string_free aether_identity_open
  aether_identity_summary aether_identity_free aether_scan_start
  aether_verify_start aether_tunnel_start aether_core_start
  aether_job_poll aether_job_cancel aether_job_free
  aether_team_sign_in aether_team_code_request aether_team_code_resend
  aether_team_code_submit aether_team_session_free
  aether_team_token_set aether_team_token_clear"

export CARGO_TARGET_DIR="${CORE_DIR}/target"

HAS_TOR=0
[ -n "$FEATURES" ] && case " $FEATURES " in *" tor "*) HAS_TOR=1 ;; esac

for abi in "${ABIS[@]}"; do
  triple="$(triple_for_abi "$abi")"
  [ -n "$triple" ] || { echo "ERROR: unknown ABI $abi" >&2; exit 1; }
  echo "==> [aether] cargo-ndk build for ${abi} (${triple}, API ${API})"
  if [ -n "$FEATURES" ]; then
    ( cd "${CRATE_DIR}" && ANDROID_NDK_ROOT="${ANDROID_NDK_HOME}" \
      cargo ndk -t "${abi}" --platform "${API}" \
      build --release --locked --features "$FEATURES" )
  else
    ( cd "${CRATE_DIR}" && ANDROID_NDK_ROOT="${ANDROID_NDK_HOME}" \
      cargo ndk -t "${abi}" --platform "${API}" \
      build --release --locked )
  fi
  artifact="${CARGO_TARGET_DIR}/${triple}/release/libaether.so"
  [ -f "$artifact" ] || { echo "ERROR: no libaether.so in ${CARGO_TARGET_DIR}/${triple}/release" >&2; exit 1; }
  mkdir -p "${JNI_DIR}/${abi}"
  cp "$artifact" "${JNI_DIR}/${abi}/libaether.so"
  "${NDK_TOOLCHAIN}/llvm-strip" "${JNI_DIR}/${abi}/libaether.so" 2>/dev/null || true
  echo "    installed libaether.so for ${abi}"

  # ---- Verify the engine exports every FFI symbol we call. ----
  dynsyms="$("${NDK_TOOLCHAIN}/llvm-nm" --dynamic --defined-only "${JNI_DIR}/${abi}/libaether.so" 2>/dev/null || true)"
  for sym in $FFI_SYMBOLS; do
    echo "$dynsyms" | grep -qw "$sym" \
      || { echo "ERROR: [${abi}] libaether.so lacks FFI symbol ${sym}." >&2; exit 1; }
  done
  echo "    [${abi}] libaether.so verified: all FFI symbols present"

  # ---- Build OUR OWN JNI bridge: libaetherjni.so ----
  clang="$(clang_for_abi "${abi}")"
  [ -n "$clang" ] && [ -x "$clang" ] \
    || { echo "ERROR: [${abi}] NDK clang not found for this ABI." >&2; exit 1; }
  echo "==> [aetherjni] building libaetherjni.so for ${abi} (HAS_TOR=${HAS_TOR})"
  "${clang}" -O2 -fPIC -shared -Wall -Werror \
    -DAETHER_HAS_TOR="${HAS_TOR}" \
    -Wl,-soname,libaetherjni.so -Wl,--no-undefined \
    -o "${JNI_DIR}/${abi}/libaetherjni.so" "${BRIDGE_SRC}" \
    -L"${JNI_DIR}/${abi}" -laether -llog
  bridgesyms="$("${NDK_TOOLCHAIN}/llvm-nm" --dynamic --defined-only "${JNI_DIR}/${abi}/libaetherjni.so" 2>/dev/null || true)"
  for sym in \
    JNI_OnLoad \
    Java_com_celzero_bravedns_aether_AetherCore_nativeVersion \
    Java_com_celzero_bravedns_aether_AetherCore_nativeSetEnv \
    Java_com_celzero_bravedns_aether_AetherCore_nativeUnsetEnv \
    Java_com_celzero_bravedns_aether_AetherCore_nativeHasTor \
    Java_com_celzero_bravedns_aether_AetherCore_nativeIdentityOpen \
    Java_com_celzero_bravedns_aether_AetherCore_nativeIdentitySummary \
    Java_com_celzero_bravedns_aether_AetherCore_nativeIdentityFree \
    Java_com_celzero_bravedns_aether_AetherCore_nativeScanStart \
    Java_com_celzero_bravedns_aether_AetherCore_nativeVerifyStart \
    Java_com_celzero_bravedns_aether_AetherCore_nativeTunnelStart \
    Java_com_celzero_bravedns_aether_AetherCore_nativeCoreStart \
    Java_com_celzero_bravedns_aether_AetherCore_nativeJobPoll \
    Java_com_celzero_bravedns_aether_AetherCore_nativeJobCancel \
    Java_com_celzero_bravedns_aether_AetherCore_nativeJobFree \
    Java_com_celzero_bravedns_aether_AetherCore_nativeTeamSignIn \
    Java_com_celzero_bravedns_aether_AetherCore_nativeTeamCodeRequest \
    Java_com_celzero_bravedns_aether_AetherCore_nativeTeamCodeResend \
    Java_com_celzero_bravedns_aether_AetherCore_nativeTeamCodeSubmit \
    Java_com_celzero_bravedns_aether_AetherCore_nativeTeamSessionFree \
    Java_com_celzero_bravedns_aether_AetherCore_nativeTeamTokenClear; do
    echo "$bridgesyms" | grep -qw "$sym" \
      || { echo "ERROR: [${abi}] libaetherjni.so lacks ${sym} — Kotlin external would not resolve." >&2; exit 1; }
  done
  echo "    [${abi}] libaetherjni.so verified: all Java_* bridge symbols present"

  # The bridge must only need system libs + libaether.so; anything else
  # (e.g. a stray libc++_shared) would fail to load on device.
  needed="$("${NDK_TOOLCHAIN}/llvm-readelf" -d "${JNI_DIR}/${abi}/libaetherjni.so" 2>/dev/null \
    | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')"
  while IFS= read -r dep; do
    [ -n "$dep" ] || continue
    case "$dep" in
      libc.so|libdl.so|liblog.so|libm.so|libandroid.so|libaether.so) ;;
      *) echo "ERROR: [${abi}] libaetherjni.so needs unexpected library: ${dep}" >&2; exit 1 ;;
    esac
  done <<< "$needed"
done

echo "==> Done. Installed libs:"
find "${JNI_DIR}" -type f -name 'libaether*.so' -exec ls -la {} + 2>/dev/null || true
