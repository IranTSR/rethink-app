# Aether engine — attribution & licence

The directory `native/aether/` vendors the **Aether** core, verbatim from the
official upstream repository, plus the `quiche` library it builds against
(which upstream ships next to it in the same repository).

- Upstream: <https://github.com/CluvexStudio/Aether>
- Vendored release: **v2.4.0** (tag `v2.4.0`, commit
  `d58f9d568083129ce2c767c85054021eb3ee6a38`, 2026-10-24) — see `CORE_VERSION`
- Upstream authors: **CluvexStudio**
- Licence: **GNU Affero General Public License v3.0 (AGPL-3.0)** — the full
  text is in `native/aether/LICENSE`. The crate itself declares
  `license = "AGPL-3.0-only"` (`native/aether/aether/Cargo.toml`).
- MASQUE support is built on Cloudflare's **Quiche** library
  (`native/aether/quiche/`, BSD-2-Clause — see `native/aether/quiche/COPYING`).
  Third-party notices for the Rust dependencies live in
  `native/aether/license-texts/`.

## What this fork does with it

- The vendored tree is kept **pristine**: no Rethink-specific patches are
  applied to any file under `native/aether/`. All integration lives outside
  it — the JNI bridge in `scripts/aether-jni.c`, the build in
  `scripts/build-aether.sh`, the Kotlin engine/UI in
  `app/src/main/java/com/celzero/bravedns/aether/`. That is deliberate: a
  pristine vendor makes `scripts/sync-core.sh --check` a plain diff, and it
  keeps the AGPL "Corresponding Source" story trivial.
- The engine is built **from source** in CI (`scripts/build-aether.sh`,
  `cargo-ndk`) and linked as `libaether.so`; upstream publishes no Android
  binaries, so no prebuilt core is ever shipped.
- This fork is public and the vendored source (with its licence) ships in
  the repository, so anyone who receives the app can obtain the exact
  Corresponding Source of the engine it runs, as AGPL-3.0 §6/§13 require.
  The modified work as a whole stays under AGPL-3.0 for the Aether-derived
  parts; the Rethink app's own Apache-2.0 files are unaffected except where
  they link the engine (see `docs/aether-integration.md` for the
  licence-interaction notes).
- Do **not** strip `LICENSE`, `TRADEMARK.md`, or the copyright headers from
  this tree. `scripts/sync-core.sh` refuses to run if `LICENSE` is missing.

## Updating the core

    CORE_TARGET=v2.5.0 bash scripts/sync-core.sh --upgrade

That re-vendors the named upstream tag and updates `CORE_VERSION`. CI only
runs `scripts/sync-core.sh --check` (verify, never upgrade), so a release
can never silently change engine underneath it.
