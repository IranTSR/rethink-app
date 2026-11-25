# Aether tunnel engine in Rethink

Vendored Aether core **v2.4.0** (tag `v2.4.0`, commit
`d58f9d568083129ce2c767c85054021eb3ee6a38`, 2026-10-24) from
[CluvexStudio/Aether](https://github.com/CluvexStudio/Aether) — "a Rust
userspace WARP core for censored networks, built around MASQUE over HTTP/3
and HTTP/2". The integration pattern follows the
[mira4420/Aether](https://github.com/mira4420/Aether) Android app: vendor
the whole upstream repo, build the Rust core from source with cargo-ndk,
talk to it through its plain-C JSON FFI, and forward the TUN through the
SOCKS5 proxy it serves.

## Architecture

```
User toggles "Aether tunnel" in AetherActivity
  └─▶ AetherController.enableTunnel()
        1. Claims Rethink's custom-SOCKS5 slot for the engine
           (AppConfig.updateCustomSocks5Proxy, 127.0.0.1:1819),
           backing the user's previous SOCKS5 endpoint up first.
        2. AetherEngine scans for a WARP endpoint (or uses the manual peer)
           and starts the engine.
        3. Registers the distinct firestack proxy id "AETHER" and
           re-registers the SOCKS5 slot with a running VPN
           (VpnController.addAetherProxy / readdSocks5Proxy),
           or starts the VPN.
  └─▶ Engine (Rust, in-process .so) serves SOCKS5 on 127.0.0.1:1819
  └─▶ firestack TUN egress dials that SOCKS5 (id "S5", the normal
      custom-SOCKS5 path) — plus id "AETHER" for direct addressing.
```

### Two invocation paths

The core's FFI (`aether/src/ffi.rs`) only expresses **plain MASQUE /
WireGuard** (`Transport::{Masque, WireGuard}`). Everything else lives in
the CLI path (`run()` in `aether/src/lib.rs`):

| Path | Entry | Supports |
| --- | --- | --- |
| Managed (structured FFI jobs) | `aether_identity_open` → `aether_scan_start` → `aether_tunnel_start`, polled with `aether_job_poll` | MASQUE H3/H2, WireGuard, scan modes, noise profiles, ECH, in-tunnel DNS, routing rules, upstream proxy, Zero Trust team identity, quick-reconnect |
| Whole-engine (CLI args) | `aether_core_start(["--masque", "--gool", …])` | Everything above **plus** gool / gool-classic / mim, Tor chain/reverse/only, Psiphon chain/reverse/only |

`AetherConfig.requiresCliMode()` picks the CLI path automatically when a
nested transport, any Tor mode, or any Psiphon mode is selected. The
remaining options reach the core as `AETHER_*` environment variables —
the core reads them at call time throughout its codebase — applied by the
JNI bridge's `setenv()` immediately before every engine call
(`AetherEngine.applyEnv`, serialised through one Mutex because env is
process-global).

### Kill-switch interplay

A dead SOCKS5 listener refuses connections, so TUN traffic **cannot
leak out direct** when the engine dies — the proxy itself fails closed.
On top of that, when *Fail closed* is on (default), an unexpected engine
death also stops the VPN (`VpnController.stop`) so the failure is visible
instead of a VPN that carries nothing. Rethink's system-level always-on /
lockdown mode is untouched. Disabling Aether restores the user's previous
SOCKS5 endpoint verbatim (or clears the slot if there was none).

## File map

### rethink-app (branch `amnezia-aether`)

| Path | What |
| --- | --- |
| `native/aether/` | **Pristine** upstream v2.4.0 tree (`aether/` + `quiche/` + `Docs/` + `LICENSE` + READMEs + `license-texts/` + `psiphon-build.sh` / `pt-build.sh`). No Rethink patches. `CORE_VERSION` records version/commit/date. `ATTRIBUTION.md` is the licence note. |
| `scripts/sync-core.sh` | `--check` (CI: verifies version/LICENSE/FFI symbols, never touches the network) and `--upgrade <tag>` (re-vendors a newer upstream tag, forward-only). |
| `scripts/build-aether.sh` | cargo-ndk build of `libaether.so` (arm64-v8a + armeabi-v7a, API 26, `--features tor` by default) + NDK-clang build of the JNI bridge `libaetherjni.so` into `app/src/main/jniLibs/<abi>/`, with symbol verification. |
| `scripts/aether-jni.c` | Our JNI bridge: forwards `AetherCore` natives to the engine FFI, plus `setenv`/`unsetenv` and `hasTor` (compile-time, from `AETHER_FEATURES`). Defines its own `JNI_OnLoad`. |
| `app/src/main/java/com/celzero/bravedns/aether/AetherCore.kt` | JNI declarations, library loading, FFI reply parsing. `isSupported()` = API 26+ on arm64-v8a/armeabi-v7a. |
| `…/aether/AetherConfig.kt` | All settings; maps to FFI payloads, `AETHER_*` env, and CLI args; app-private SharedPreferences persistence. No secrets stored (no tokens/passwords). |
| `…/aether/AetherEngine.kt` | Engine lifecycle: identity open, scan, verify, tunnel start/stop, CLI mode, Zero Trust email-code flow, job polling, tunnel-death watcher. |
| `…/aether/AetherController.kt` | Seam to Rethink: SOCKS5-slot claim/restore with backup, enable/disable, clean-IP scan, fail-closed hook. |
| `…/ui/activity/AetherActivity.kt` + `res/layout/activity_aether.xml` + `res/values/aether_strings.xml` | The settings screen: status, transport, scan, noise/DPI, MASQUE/QUIC, Tor, Psiphon, Zero Trust, routing/DNS, kill-switch, advanced (raw CLI args / env). |
| `.github/workflows/android.yml`, `nightly.yml` | NDK + Rust + cargo-ndk setup, `sync-core.sh --check`, `build-aether.sh` before Gradle. No Gradle changes: `src/main/jniLibs` is packaged by default. |

Existing files edited (minimal, all additive except the two noted):

| File | Change |
| --- | --- |
| `app/src/full/AndroidManifest.xml`, `app/src/tv/AndroidManifest.xml` | Register `AetherActivity` (same flags as neighbouring activities). |
| `app/src/full/res/layout/fragment_proxy_configure.xml` | Aether card (title + container + icon + arrow) after the WireGuard card. |
| `ui/activity/ProxySettingsActivity.kt` | Click handlers + `openAetherActivity()`. |
| `service/ProxyManager.kt` | `ID_AETHER_BASE = "AETHER"` const. |
| `net/go/GoVpnAdapter.kt` | `addAetherProxy()` (tries `aether://`, falls back to `socks5://`) + `removeAetherProxy()`. |
| `service/BraveVPNService.kt`, `service/VpnController.kt` | Passthroughs for the two methods above. |

Nothing in WireGuard/Amnezia files was touched, and no `build.gradle` /
`settings.gradle` firestack wiring was touched.

### firestack (branch `amnezia-aether`, sibling repo)

| Path | What |
| --- | --- |
| `intra/settings/aetheropts.go` | `AetherOptions` (id, SOCKS addrs, enabled) + defaults `127.0.0.1:1819` / `:1820` + `ProxyOptions()` (`aether` scheme). |
| `intra/ipn/aether.go` | `aether` proxy type: wraps the SOCKS5 proxy, `Type() == "aether"`, loopback-only guard, `EngineAddr()`, `EngineAlive()` liveness probe. |
| `intra/backend/ipn_proxies.go` | `AetherS5 = "AETHER"` id const, `AETHER = "aether"` type const. |
| `intra/ipn/proxies.go` | Aliases + `isAether()` helper. |
| `intra/ipn/proxy.go` | `fromOpts` case `"aether"` → `NewAetherProxy`. |

## Feature coverage (UI ↔ core)

| Core feature | UI | Notes |
| --- | --- | --- |
| WARP endpoint scan (turbo/balanced/thorough/verified/ironclad) | Scan section: mode, IP version, ports, exclusions, Scan button | FFI scan job; manual peer skips the scan; quick-reconnect reuses last good endpoint |
| Clean-IP scan | "Clean-IP scan" button | Ironclad scan + data-plane verify; a gateway is trusted only once it carries traffic |
| Noise / DPI evasion profiles (off/light/firewall/balanced/aggressive) | Noise section | FFI `profile` (aethernoize) + `AETHER_NOIZE` |
| MASQUE H3/H2, QUIC v2 opener, SNI, H2 peer | MASQUE/QUIC section | `AETHER_MASQUE_HTTP2`, `AETHER_QUIC_V2`, … |
| ClientHello fragmentation | Noise section | `AETHER_MASQUE_H2_FRAGMENT[_SIZE|_DELAY]` |
| ECH | Noise section | FFI `ech` (auto) + `AETHER_ECH_DOMAIN` |
| Tor (chain/reverse/only, bridges auto/off/custom, country) | Tor section | Whole-engine path; needs the `tor` cargo feature (default on; UI warns if the build lacks it); Tor-through-tunnel adds SOCKS on 1820 |
| Psiphon (chain/reverse/only, region, CDN SNI, bin/config/entries paths) | Psiphon section | **Not usable yet**: Psiphon shells out to an external binary and none is bundled with the app; settings are persisted for a future build that bundles one |
| Zero Trust (team, email-code sign-in, gateway) | Zero Trust section | FFI team calls; token stays in the core's storage, never in the app |
| Routing rules (block/direct, SNI-matched) | Routing section | `AETHER_ROUTE_BLOCK` / `AETHER_ROUTE_DIRECT` |
| Custom in-tunnel DNS | Routing section | `AETHER_DNS` (default 1.1.1.1,1.0.0.1) |
| Upstream proxy, exit location, keepalive, HTTP proxy port | Routing / MASQUE sections | `AETHER_UPSTREAM`, `AETHER_EXIT_LOC`, … |
| Kill-switch interplay | Kill-switch section | Fail-closed toggle; see above |
| Remaining TLS knobs (groups, ciphers, GREASE, ALPS, …) | Advanced: raw CLI args / raw `AETHER_*` lines | Full access without 40 toggles |

## Licence compliance (AGPL-3.0)

- The vendored tree keeps upstream's `LICENSE` (AGPL-3.0), copyright
  headers, `TRADEMARK.md`, and third-party notices (`license-texts/`,
  `quiche/COPYING`). Nothing was stripped.
- The tree is **pristine** — no Rethink patches — so the Corresponding
  Source of the shipped engine is exactly `native/aether/` at the
  recorded commit; `scripts/sync-core.sh --check` enforces this in CI
  (version match, LICENSE present, FFI symbols present, cdylib target).
- The fork is public and the source ships in the repository, satisfying
  AGPL-3.0 §6/§13 for the engine. The Rethink app's own Apache-2.0 files
  are unaffected except where they link the engine; the combined work's
  Aether-derived parts remain AGPL-3.0 (see `native/aether/ATTRIBUTION.md`).
- Upgrades are explicit (`CORE_TARGET=vX.Y.Z bash scripts/sync-core.sh
  --upgrade`); CI never auto-upgrades.

## What still needs CI verification (no live test was possible)

1. **The Rust build itself** — no JDK/Android SDK/Rust toolchain exists
   on this VM. First CI run must prove: `cargo-ndk` builds `libaether.so`
   for both ABIs (quiche/boringssl CMake build on the runner), the
   `tor` feature (arti-client + static-sqlite) compiles for Android, and
   the symbol checks pass. If the `tor` feature fails, set
   `AETHER_FEATURES=""` in the workflows — the UI then hides Tor.
2. **Build time** — quiche/boringssl + arti for two ABIs is likely
   20–40 min on first build; consider a cargo cache if CI gets slow.
3. **APK contents** — verify `libaether.so` + `libaetherjni.so` are in
   the arm64-v8a and armeabi-v7a APK splits (and absent from x86_64,
   where the UI correctly reports unsupported).
4. **On-device** — engine loads, identity registers, scan finds an
   endpoint, tunnel serves SOCKS5 on 1819, TUN traffic flows through it,
   engine death fails closed, Zero Trust code flow works, Tor chain
   mode works (if built with the feature).
5. **firestack AAR** — the `aether://` scheme only works once a firestack
   AAR containing `intra/ipn/aether.go` is published and the app's
   `firestackCommit` points at it. Until then `addAetherProxy()` falls
   back to `socks5://` under the same id, and the primary SOCKS5-slot
   path works with any firestack version.
6. **Psiphon** — needs a `psiphon` binary bundled per ABI before its
   modes can work (see `psiphon-build.sh` in the vendored tree).
