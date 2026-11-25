#!/usr/bin/env bash
#
# sync-core.sh — keep the vendored Aether engine under native/aether in sync
# with the official upstream repository (CluvexStudio/Aether).
#
# The vendored tree is kept PRISTINE: this fork applies no patches to the
# core at all (all integration lives in scripts/aether-jni.c and in Kotlin),
# so an upgrade is a plain re-vendor, never a merge. See
# native/aether/ATTRIBUTION.md for the licence story (AGPL-3.0).
#
# Modes:
#   scripts/sync-core.sh --check            # default in CI: verify only, never
#                                           # touches the network or the tree.
#                                           # Fails if CORE_VERSION disagrees
#                                           # with the vendored Cargo.toml, if
#                                           # LICENSE is missing, or if a
#                                           # required FFI entry point is gone.
#   scripts/sync-core.sh --upgrade [tag]    # re-vendor upstream tag (default:
#                                           # latest release). Needs network.
#                                           # CORE_TARGET=<tag> also works.
#   CORE_SYNC=off scripts/sync-core.sh --check   # no-op (escape hatch)
#
# An upgrade moves FORWARD only (never downgrades) and refuses to run if the
# working tree has uncommitted changes under native/aether/.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

CORE_DIR="native/aether"
VERSION_FILE="$CORE_DIR/CORE_VERSION"
LICENSE_FILE="$CORE_DIR/LICENSE"
CARGO_TOML="$CORE_DIR/aether/Cargo.toml"
CORE_REPO="${AETHER_REPO:-CluvexStudio/Aether}"

# FFI entry points the Kotlin layer (AetherCore.kt) and scripts/build-aether.sh
# depend on. If upstream ever renames one, --check fails loudly instead of
# shipping an app whose natives do not resolve.
REQUIRED_FFI_SYMBOLS=(
  aether_version aether_string_free
  aether_identity_open aether_identity_summary aether_identity_free
  aether_scan_start aether_verify_start aether_tunnel_start aether_core_start
  aether_job_poll aether_job_cancel aether_job_free
  aether_team_sign_in aether_team_code_request aether_team_code_resend
  aether_team_code_submit aether_team_session_free
  aether_team_token_set aether_team_token_clear
)

log() { printf '[core-sync] %s\n' "$*"; }
die() { printf '[core-sync] ERROR: %s\n' "$*" >&2; exit 1; }

vendored_version() { sed -n '1p' "$VERSION_FILE" | tr -d '[:space:]'; }
vendored_commit() { sed -n '2p' "$VERSION_FILE" | tr -d '[:space:]'; }

cargo_version() {
  grep -m1 -E '^version[[:space:]]*=' "$CARGO_TOML" \
    | sed -E 's/.*"[[:space:]]*([^"]+).*/\1/' | tr -d '[:space:]'
}

check() {
  [ -f "$VERSION_FILE" ] || die "missing $VERSION_FILE"
  [ -f "$LICENSE_FILE" ] || die "missing $LICENSE_FILE (AGPL-3.0 text must stay vendored)"
  [ -f "$CARGO_TOML" ] || die "missing $CARGO_TOML"
  head -1 "$LICENSE_FILE" | grep -q "GNU AFFERO GENERAL PUBLIC LICENSE" \
    || die "$LICENSE_FILE is not the AGPL-3.0 text"
  local vv cv
  vv="$(vendored_version)"; cv="$(cargo_version)"
  [ -n "$vv" ] || die "CORE_VERSION is empty"
  [ "$vv" = "$cv" ] || die "CORE_VERSION ($vv) disagrees with vendored Cargo.toml ($cv)"
  local sym
  for sym in "${REQUIRED_FFI_SYMBOLS[@]}"; do
    grep -q "extern \"C\" fn $sym" "$CORE_DIR/aether/src/ffi.rs" \
      || die "FFI entry point $sym is missing from vendored ffi.rs — Kotlin/JNI would not resolve"
  done
  grep -q 'crate-type = \[.*"cdylib"' "$CARGO_TOML" \
    || die "vendored Cargo.toml no longer builds a cdylib (libaether.so)"
  log "OK: vendored Aether v$vv ($(vendored_commit)) — LICENSE present, ${#REQUIRED_FFI_SYMBOLS[@]} FFI symbols present, cdylib target present."
}

upgrade() {
  local target="${1:-${CORE_TARGET:-}}"
  [ -n "$target" ] || die "usage: $0 --upgrade <tag>  (or CORE_TARGET=<tag>)"
  target="${target#v}" # accept "v2.5.0" or "2.5.0"
  local current
  current="$(vendored_version)"
  if [ "$target" = "$current" ]; then
    log "already at v$current; nothing to do."
    return 0
  fi
  # crude semver-guard: refuse to move backwards (lexicographic is good
  # enough for the x.y.z tags upstream ships; a human can override by
  # editing CORE_VERSION by hand and re-running).
  if [ "$(printf '%s\n%s\n' "$target" "$current" | sort -V | head -n1)" != "$current" ]; then
    die "refusing to downgrade from v$current to v$target"
  fi
  if ! git diff --quiet -- "$CORE_DIR" 2>/dev/null; then
    die "uncommitted changes under $CORE_DIR — commit or stash them first"
  fi
  command -v git >/dev/null || die "git is required for --upgrade"
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  log "cloning $CORE_REPO @ v$target ..."
  git clone --depth 1 --branch "v$target" "https://github.com/${CORE_REPO}" "$tmp/aether-upstream" \
    || die "could not clone $CORE_REPO tag v$target (network? tag does not exist?)"
  local commit
  commit="$(git -C "$tmp/aether-upstream" rev-parse HEAD)"
  local upstream_date
  upstream_date="$(git -C "$tmp/aether-upstream" log -1 --format=%ad --date=short)"
  [ -f "$tmp/aether-upstream/LICENSE" ] || die "upstream tag v$target has no LICENSE file"
  head -1 "$tmp/aether-upstream/LICENSE" | grep -q "GNU AFFERO GENERAL PUBLIC LICENSE" \
    || die "upstream tag v$target LICENSE is not AGPL-3.0 — refusing"
  log "upstream v$target = $commit ($upstream_date)"
  # Replace the vendored tree, keeping this fork's own files (they live
  # alongside the vendor, not inside upstream's tree).
  rm -rf "$CORE_DIR/aether" "$CORE_DIR/quiche" "$CORE_DIR/Docs" \
    "$CORE_DIR/README.md" "$CORE_DIR/README.fa.md" "$CORE_DIR/TRADEMARK.md" \
    "$CORE_DIR/aether.sh" "$CORE_DIR/license-texts" \
    "$CORE_DIR/psiphon-build.sh" "$CORE_DIR/pt-build.sh" "$CORE_DIR/LICENSE"
  cp -r "$tmp/aether-upstream/aether" "$CORE_DIR/aether"
  cp -r "$tmp/aether-upstream/quiche" "$CORE_DIR/quiche"
  cp -r "$tmp/aether-upstream/Docs" "$CORE_DIR/Docs"
  cp "$tmp/aether-upstream/LICENSE" "$tmp/aether-upstream/README.md" \
    "$tmp/aether-upstream/README.fa.md" "$tmp/aether-upstream/TRADEMARK.md" \
    "$tmp/aether-upstream/aether.sh" "$CORE_DIR/"
  cp -r "$tmp/aether-upstream/license-texts" "$CORE_DIR/license-texts"
  cp "$tmp/aether-upstream/psiphon-build.sh" "$tmp/aether-upstream/pt-build.sh" "$CORE_DIR/"
  printf '%s\n%s\nhttps://github.com/%s\n%s\n' \
    "$target" "$commit" "$CORE_REPO" "$upstream_date" > "$VERSION_FILE"
  # Re-verify the fresh tree before declaring success.
  check
  log "upgraded vendored Aether v$current -> v$target ($commit)."
  log "review with: git status --short $CORE_DIR ; git diff --stat $CORE_DIR"
}

if [ "${CORE_SYNC:-on}" = "off" ]; then
  log "CORE_SYNC=off — skipping."
  exit 0
fi

case "${1:---check}" in
  --check) check ;;
  --upgrade) upgrade "${2:-}" ;;
  *) die "usage: $0 [--check | --upgrade <tag>]" ;;
esac
