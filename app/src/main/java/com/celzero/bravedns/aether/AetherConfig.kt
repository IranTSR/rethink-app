/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * AetherConfig is the single source of truth for the Aether engine's
 * settings. It maps to the engine three ways:
 *   1. FFI payloads (aether_scan_start / aether_tunnel_start /
 *      aether_identity_open) — the structured fields the JSON FFI accepts.
 *   2. AETHER_* environment variables — everything else the core reads at
 *      call time (see aether-core/Docs/DOCS.en.md "Environment variables"
 *      and aether/src/cli.rs). AetherEngine applies them with setenv()
 *      before every engine call.
 *   3. CLI arguments (toCliArgs) — the whole-engine path
 *      (aether_core_start), which is the ONLY path that supports nested
 *      transports (gool / gool-classic / mim), Tor-only / Tor-reverse and
 *      Psiphon-only / Psiphon-reverse. requiresCliMode() is true exactly
 *      when one of those is selected.
 *
 * Nothing secret is ever stored here: no Zero Trust tokens, no service
 * secrets, no passwords. The Zero Trust email-code flow keeps its token
 * inside the core's own storage (see AetherCore.team*); the UI never sees
 * it. The only credentials-adjacent value is the optional upstream-proxy
 * URL, which the user types in themselves and which is stored only in the
 * app-private SharedPreferences, same as Rethink's own proxy passwords.
 */
package com.celzero.bravedns.aether

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

data class AetherConfig(
    // -- transport ---------------------------------------------------------
    var transport: Transport = Transport.MASQUE,
    /** Manual endpoint (ip:port). When set, the scan is skipped. */
    var peer: String? = null,
    /** For gool-classic: outer hop (ip:port). For mim: outer hop. */
    var outerPeer: String? = null,
    /** For gool-classic: inner hop (ip:port). For mim: inner hop. */
    var innerPeer: String? = null,
    /** For gool: the WireGuard hop inside the MASQUE tunnel (ip:port). */
    var goolPeer: String? = null,

    // -- endpoint scan ------------------------------------------------------
    var scanMode: ScanMode = ScanMode.BALANCED,
    var scanIp: ScanIp = ScanIp.V4,
    /** Extra ports to sweep (CSV). Empty = the transport's defaults. */
    var scanPorts: String? = null,
    /** Endpoints to skip (CSV of ip:port). */
    var excludedEndpoints: String? = null,
    /** Reuse the last known-good endpoint without a new sweep. */
    var quickReconnect: Boolean = true,

    // -- obfuscation / DPI evasion ------------------------------------------
    var noizeProfile: NoizeProfile = NoizeProfile.FIREWALL,
    /** Fragment the TLS ClientHello (HTTP/2 MASQUE + WARP API calls). */
    var fragmentHello: Boolean = false,
    var fragmentSize: Int? = null,
    var fragmentDelayMs: Int? = null,
    /** Fetch an ECH config before handshakes (MASQUE only). */
    var ech: Boolean = false,
    var echDomain: String? = null,

    // -- MASQUE / QUIC -------------------------------------------------------
    /** true = MASQUE over HTTP/2 (TCP); false = MASQUE over HTTP/3 (QUIC). */
    var masqueHttp2: Boolean = false,
    /** QUIC v2 version-negotiation opener (on by default upstream). */
    var quicV2: Boolean = true,
    var masqueSni: String? = null,
    var masqueH2Peer: String? = null,

    // -- Tor (needs the `tor` cargo feature; see AetherCore.hasTor) --------
    var torMode: TorMode = TorMode.OFF,
    var torBridges: TorBridges = TorBridges.AUTO,
    /** One bridge per line, same format as the core's --tor-bridge flag. */
    var torCustomBridges: String? = null,
    var torCountry: String? = null,

    // -- Psiphon -------------------------------------------------------------
    // NOTE: Psiphon runs an EXTERNAL psiphon binary (AETHER_PSIPHON_BIN or
    // one next to the engine binary). No psiphon binary is bundled with
    // this app, so Psiphon modes currently fail at runtime with the core's
    // install hint. The settings are still exposed (and persisted) so a
    // future build that bundles the binary can use them unchanged.
    var psiphonMode: PsiphonMode = PsiphonMode.OFF,
    var psiphonRegion: String? = null,
    var psiphonCdnSni: String? = null,
    var psiphonBinPath: String? = null,
    var psiphonConfigPath: String? = null,
    var psiphonServerEntriesPath: String? = null,

    // -- Zero Trust -----------------------------------------------------------
    /** Team name or full https://<team>.cloudflareaccess.com URL. */
    var zeroTrustTeam: String? = null,
    var zeroTrustEmail: String? = null,
    /** Route HTTP through the organisation gateway (Zero Trust). */
    var zeroTrustGateway: Boolean = false,

    // -- routing / DNS / upstream ----------------------------------------------
    /** Domains / addresses / ports to block (comma-separated). */
    var routeBlock: String? = null,
    /** Domains / addresses / ports to send direct (comma-separated). */
    var routeDirect: String? = null,
    /** Resolvers used inside the tunnel (default 1.1.1.1,1.0.0.1). */
    var inTunnelDns: String? = null,
    /** Another proxy/VPN the engine dials out through (URL). */
    var upstreamProxy: String? = null,
    /** Preferred exit location (country code, e.g. DE). */
    var exitLocation: String? = null,
    /** WireGuard keepalive seconds (WG transports). */
    var keepaliveSecs: Int = 5,
    /** Optional extra HTTP CONNECT listener (port), e.g. 1821. */
    var httpProxyPort: Int? = null,

    // -- behaviour -------------------------------------------------------------
    /** Fail closed: if the engine dies while the tunnel is enabled, stop
     *  the VPN instead of letting traffic leak out direct. */
    var killSwitchFailClosed: Boolean = true,

    // -- advanced / raw ----------------------------------------------------------
    /** Extra CLI arguments, one per line (CLI mode only). Lets the user reach
     *  every remaining core knob (TLS groups/ciphers, GREASE, ALPS, …) that
     *  has no dedicated UI row. */
    var extraCliArgs: String? = null,
    /** Extra AETHER_* lines, KEY=VALUE one per line (managed mode). */
    var extraEnv: String? = null,

    // -- state (not settings) ----------------------------------------------------
    var tunnelEnabled: Boolean = false,
    var lastEndpoint: String? = null,
    var lastScanAtMs: Long = 0L,
    /**
     * The user's previous custom SOCKS5 endpoint (JSON), backed up when
     * Aether takes over the SOCKS5 slot and restored when it is disabled.
     * See AetherController. Never a secret: host/port/name only.
     */
    var backupSocks5Json: String? = null,
) {
    enum class Transport { MASQUE, WIREGUARD, GOOL, GOOL_CLASSIC, MIM }

    enum class ScanMode { TURBO, BALANCED, THOROUGH, VERIFIED, IRONCLAD }

    enum class ScanIp { V4, V6, DUAL }

    /** Upstream names: off | light | firewall | balanced | gfw | aggressive.
     *  `firewall` is the MASQUE default; `balanced` the WireGuard default. */
    enum class NoizeProfile { OFF, LIGHT, FIREWALL, BALANCED, AGGRESSIVE }

    /** OFF = no Tor. CHAIN = Tor inside the tunnel (exit = Tor exit,
     *  second SOCKS on 1820). REVERSE = tunnel through Tor (exit = WARP).
     *  ONLY = Tor alone, no WARP tunnel. */
    enum class TorMode { OFF, CHAIN, REVERSE, ONLY }

    enum class TorBridges { AUTO, OFF, CUSTOM }

    /** OFF = no Psiphon. CHAIN = Psiphon inside the tunnel. REVERSE =
     *  tunnel through Psiphon. ONLY = Psiphon alone, no WARP tunnel. */
    enum class PsiphonMode { OFF, CHAIN, REVERSE, ONLY }

    /** True when the selected options need the whole-engine CLI path
     *  (aether_core_start): the structured FFI tunnel only does plain
     *  MASQUE / WireGuard, with no Tor/Psiphon carrier and no nesting. */
    fun requiresCliMode(): Boolean =
        transport == Transport.GOOL ||
            transport == Transport.GOOL_CLASSIC ||
            transport == Transport.MIM ||
            torMode != TorMode.OFF ||
            psiphonMode != PsiphonMode.OFF

    fun ffiTransport(): String =
        when (transport) {
            Transport.WIREGUARD -> "wg"
            else -> "masque" // GOOL/MIM/GOOL_CLASSIC never reach the FFI tunnel
        }

    fun scanModeName(): String = scanMode.name.lowercase()

    fun noizeName(): String =
        when (noizeProfile) {
            NoizeProfile.OFF -> "off"
            NoizeProfile.LIGHT -> "light"
            NoizeProfile.FIREWALL -> "firewall"
            NoizeProfile.BALANCED -> "balanced"
            NoizeProfile.AGGRESSIVE -> "aggressive"
        }

    fun scanIpName(): String =
        when (scanIp) {
            ScanIp.V4 -> "v4"
            ScanIp.V6 -> "v6"
            ScanIp.DUAL -> "both"
        }

    fun scanIpEnv(): String =
        when (scanIp) {
            ScanIp.V4 -> "4"
            ScanIp.V6 -> "6"
            ScanIp.DUAL -> "dual"
        }

    // -- FFI payloads -------------------------------------------------------

    fun toScanPayload(): JSONObject {
        val o = JSONObject()
        o.put("transport", ffiTransport())
        o.put("mode", scanModeName())
        o.put("ip", scanIpName())
        o.put("profile", noizeName())
        o.put("ech", ech)
        scanPorts?.takeIf { it.isNotBlank() }?.let { raw ->
            val ports = org.json.JSONArray()
            raw.split(",", " ", ";").mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..65535 }.forEach { ports.put(it) }
            if (ports.length() > 0) o.put("ports", ports)
        }
        excludedEndpoints?.takeIf { it.isNotBlank() }?.let { raw ->
            val arr = org.json.JSONArray()
            raw.split(",", ";").map { it.trim() }.filter { it.isNotBlank() }
                .forEach { arr.put(it) }
            if (arr.length() > 0) o.put("excluded", arr)
        }
        return o
    }

    fun toTunnelPayload(peer: String): JSONObject {
        val o = JSONObject()
        o.put("peer", peer)
        o.put("transport", ffiTransport())
        o.put("socks", "${AetherCore.SOCKS_ADDR}:${AetherCore.SOCKS_PORT}")
        o.put("profile", noizeName())
        o.put("keepalive", keepaliveSecs)
        o.put("ech", ech)
        httpProxyPort?.let { o.put("http", "${AetherCore.SOCKS_ADDR}:$it") }
        return o
    }

    fun toVerifyPayload(peer: String): JSONObject = toTunnelPayload(peer)

    fun toIdentityPayload(identityPath: String): JSONObject {
        val o = JSONObject()
        o.put("path", identityPath)
        o.put("transport", ffiTransport())
        zeroTrustTeam?.takeIf { it.isNotBlank() }?.let { team ->
            val t = JSONObject()
            t.put("team", team)
            zeroTrustEmail?.takeIf { it.isNotBlank() }?.let { t.put("email", it) }
            o.put("team", t)
        }
        return o
    }

    // -- AETHER_* environment ----------------------------------------------
    // Keys the config owns; AetherEngine unsets any of these that the
    // config does not set, so a stale value can never leak into a later run.

    fun toEnvMap(): Map<String, String> {
        val env = linkedMapOf<String, String>()
        env["AETHER_PROTOCOL"] =
            when (transport) {
                Transport.MASQUE -> "masque"
                Transport.WIREGUARD -> "wg"
                Transport.GOOL -> "gool"
                Transport.GOOL_CLASSIC -> "gool-classic"
                Transport.MIM -> "mim"
            }
        env["AETHER_SCAN"] = scanModeName()
        env["AETHER_NOIZE"] = noizeName()
        env["AETHER_IP"] = scanIpEnv()
        env["AETHER_QUICK_RECONNECT"] = if (quickReconnect) "1" else "0"
        env["AETHER_MASQUE_HTTP2"] = if (masqueHttp2) "1" else "0"
        env["AETHER_QUIC_V2"] = if (quicV2) "1" else "0"
        env["AETHER_MASQUE_H2_FRAGMENT"] = if (fragmentHello) "1" else "0"
        fragmentSize?.let { env["AETHER_MASQUE_H2_FRAGMENT_SIZE"] = it.toString() }
        fragmentDelayMs?.let { env["AETHER_MASQUE_H2_FRAGMENT_DELAY"] = it.toString() }
        if (ech) env["AETHER_ECH"] = "auto"
        echDomain?.takeIf { it.isNotBlank() }?.let { env["AETHER_ECH_DOMAIN"] = it }
        masqueSni?.takeIf { it.isNotBlank() }?.let { env["AETHER_MASQUE_SNI"] = it }
        masqueH2Peer?.takeIf { it.isNotBlank() }?.let { env["AETHER_MASQUE_H2_PEER"] = it }
        peer?.takeIf { it.isNotBlank() }?.let {
            if (transport == Transport.WIREGUARD) env["AETHER_WG_PEER"] = it
            else env["AETHER_PEER"] = it
        }
        goolPeer?.takeIf { it.isNotBlank() }?.let { env["AETHER_GOOL_PEER"] = it }
        outerPeer?.takeIf { it.isNotBlank() }?.let {
            when (transport) {
                Transport.GOOL_CLASSIC -> env["AETHER_WIW_OUTER_PEER"] = it
                Transport.MIM -> env["AETHER_MIM_OUTER_PEER"] = it
                else -> Unit
            }
        }
        innerPeer?.takeIf { it.isNotBlank() }?.let {
            when (transport) {
                Transport.GOOL_CLASSIC -> env["AETHER_WIW_INNER_PEER"] = it
                Transport.MIM -> env["AETHER_MIM_INNER_PEER"] = it
                else -> Unit
            }
        }
        env["AETHER_WG_KEEPALIVE"] = keepaliveSecs.toString()
        env["AETHER_SOCKS"] = "${AetherCore.SOCKS_ADDR}:${AetherCore.SOCKS_PORT}"
        httpProxyPort?.let { env["AETHER_HTTP_PROXY"] = "${AetherCore.SOCKS_ADDR}:$it" }
        inTunnelDns?.takeIf { it.isNotBlank() }?.let { env["AETHER_DNS"] = it }
        routeBlock?.takeIf { it.isNotBlank() }?.let { env["AETHER_ROUTE_BLOCK"] = it }
        routeDirect?.takeIf { it.isNotBlank() }?.let { env["AETHER_ROUTE_DIRECT"] = it }
        upstreamProxy?.takeIf { it.isNotBlank() }?.let { env["AETHER_UPSTREAM"] = it }
        exitLocation?.takeIf { it.isNotBlank() }?.let { env["AETHER_EXIT_LOC"] = it }
        when (torMode) {
            TorMode.OFF -> Unit
            TorMode.CHAIN -> env["AETHER_TOR"] = "chain"
            TorMode.REVERSE -> env["AETHER_TOR"] = "reverse"
            TorMode.ONLY -> env["AETHER_TOR"] = "only"
        }
        if (torMode != TorMode.OFF) {
            when (torBridges) {
                TorBridges.AUTO -> env["AETHER_TOR_BRIDGES"] = "auto"
                TorBridges.OFF -> env["AETHER_TOR_BRIDGES"] = "off"
                TorBridges.CUSTOM ->
                    torCustomBridges?.takeIf { it.isNotBlank() }
                        ?.let { env["AETHER_TOR_BRIDGES"] = it }
            }
            torCountry?.takeIf { it.isNotBlank() }?.let { env["AETHER_TOR_COUNTRY"] = it }
            env["AETHER_TOR_BIND"] = "${AetherCore.SOCKS_ADDR}:${AetherCore.TOR_SOCKS_PORT}"
        }
        when (psiphonMode) {
            PsiphonMode.OFF -> Unit
            PsiphonMode.CHAIN -> env["AETHER_PSIPHON"] = "chain"
            PsiphonMode.REVERSE -> env["AETHER_PSIPHON"] = "reverse"
            PsiphonMode.ONLY -> env["AETHER_PSIPHON"] = "only"
        }
        if (psiphonMode != PsiphonMode.OFF) {
            psiphonRegion?.takeIf { it.isNotBlank() }?.let { env["AETHER_PSIPHON_REGION"] = it }
            psiphonCdnSni?.takeIf { it.isNotBlank() }?.let { env["AETHER_PSIPHON_CDN_SNI"] = it }
            psiphonBinPath?.takeIf { it.isNotBlank() }?.let { env["AETHER_PSIPHON_BIN"] = it }
            psiphonConfigPath?.takeIf { it.isNotBlank() }?.let { env["AETHER_PSIPHON_CONFIG"] = it }
            psiphonServerEntriesPath?.takeIf { it.isNotBlank() }
                ?.let { env["AETHER_PSIPHON_SERVER_ENTRIES"] = it }
        }
        zeroTrustTeam?.takeIf { it.isNotBlank() }?.let { env["AETHER_TEAM"] = it }
        zeroTrustEmail?.takeIf { it.isNotBlank() }?.let { env["AETHER_ACCESS_EMAIL"] = it }
        if (zeroTrustGateway) env["AETHER_GATEWAY"] = "1"
        extraEnv?.takeIf { it.isNotBlank() }?.let { raw ->
            raw.lines().map { it.trim() }.filter { it.contains("=") }.forEach { line ->
                val key = line.substringBefore("=").trim()
                val value = line.substringAfter("=").trim()
                if (key.startsWith("AETHER_") && value.isNotEmpty()) env[key] = value
            }
        }
        return env
    }

    companion object {
        /** Every AETHER_* key this config can own (for unsetting stale ones). */
        val OWNED_ENV_KEYS = listOf(
            "AETHER_PROTOCOL", "AETHER_SCAN", "AETHER_NOIZE", "AETHER_IP",
            "AETHER_QUICK_RECONNECT", "AETHER_MASQUE_HTTP2", "AETHER_QUIC_V2",
            "AETHER_MASQUE_H2_FRAGMENT", "AETHER_MASQUE_H2_FRAGMENT_SIZE",
            "AETHER_MASQUE_H2_FRAGMENT_DELAY", "AETHER_ECH", "AETHER_ECH_DOMAIN",
            "AETHER_MASQUE_SNI", "AETHER_MASQUE_H2_PEER", "AETHER_PEER",
            "AETHER_WG_PEER", "AETHER_GOOL_PEER", "AETHER_WIW_OUTER_PEER",
            "AETHER_WIW_INNER_PEER", "AETHER_MIM_OUTER_PEER", "AETHER_MIM_INNER_PEER",
            "AETHER_WG_KEEPALIVE", "AETHER_SOCKS", "AETHER_HTTP_PROXY",
            "AETHER_DNS", "AETHER_ROUTE_BLOCK", "AETHER_ROUTE_DIRECT",
            "AETHER_UPSTREAM", "AETHER_EXIT_LOC", "AETHER_TOR",
            "AETHER_TOR_BRIDGES", "AETHER_TOR_COUNTRY", "AETHER_TOR_BIND",
            "AETHER_PSIPHON", "AETHER_PSIPHON_REGION", "AETHER_PSIPHON_CDN_SNI",
            "AETHER_PSIPHON_BIN", "AETHER_PSIPHON_CONFIG",
            "AETHER_PSIPHON_SERVER_ENTRIES", "AETHER_TEAM", "AETHER_ACCESS_EMAIL",
            "AETHER_GATEWAY",
        )
    }

    // -- CLI arguments (whole-engine path) ----------------------------------

    fun toCliArgs(): List<String> {
        val args = mutableListOf<String>()
        when (transport) {
            Transport.MASQUE -> args += "--masque"
            Transport.WIREGUARD -> args += "--wg"
            Transport.GOOL -> args += "--gool"
            Transport.GOOL_CLASSIC -> args += "--gool-classic"
            Transport.MIM -> args += "--mim"
        }
        args += listOf("--scan", scanModeName())
        args += listOf("--noize", noizeName())
        args += listOf("--ip", scanIpEnv())
        if (quickReconnect) args += "--quick-reconnect" else args += "--no-quick-reconnect"
        if (masqueHttp2) args += "--h2" else args += "--h3"
        if (!quicV2) args += "--no-quic-v2"
        if (fragmentHello) {
            args += "--fragment"
            fragmentSize?.let { args += listOf("--fragment-size", it.toString()) }
            fragmentDelayMs?.let { args += listOf("--fragment-delay", it.toString()) }
        }
        if (ech) args += listOf("--ech", "auto")
        echDomain?.takeIf { it.isNotBlank() }?.let { args += listOf("--ech-domain", it) }
        masqueSni?.takeIf { it.isNotBlank() }?.let { args += listOf("--masque-sni", it) }
        masqueH2Peer?.takeIf { it.isNotBlank() }?.let { args += listOf("--h2-peer", it) }
        peer?.takeIf { it.isNotBlank() }?.let {
            if (transport == Transport.WIREGUARD) args += listOf("--wg-peer", it)
            else args += listOf("--peer", it)
        }
        goolPeer?.takeIf { it.isNotBlank() }?.let { args += listOf("--gool-peer", it) }
        outerPeer?.takeIf { it.isNotBlank() }?.let {
            when (transport) {
                Transport.GOOL_CLASSIC -> args += listOf("--wiw-outer", it)
                Transport.MIM -> args += listOf("--mim-outer", it)
                else -> Unit
            }
        }
        innerPeer?.takeIf { it.isNotBlank() }?.let {
            when (transport) {
                Transport.GOOL_CLASSIC -> args += listOf("--wiw-inner", it)
                Transport.MIM -> args += listOf("--mim-inner", it)
                else -> Unit
            }
        }
        scanPorts?.takeIf { it.isNotBlank() }?.let { /* ports are scan-payload only */ }
        args += listOf("--keepalive", keepaliveSecs.toString())
        args += listOf("--bind", "${AetherCore.SOCKS_ADDR}:${AetherCore.SOCKS_PORT}")
        httpProxyPort?.let { args += listOf("--http-proxy", "${AetherCore.SOCKS_ADDR}:$it") }
        inTunnelDns?.takeIf { it.isNotBlank() }?.let { args += listOf("--dns", it) }
        routeBlock?.takeIf { it.isNotBlank() }?.let { args += listOf("--route-block", it) }
        routeDirect?.takeIf { it.isNotBlank() }?.let { args += listOf("--route-direct", it) }
        upstreamProxy?.takeIf { it.isNotBlank() }?.let { args += listOf("--upstream", it) }
        exitLocation?.takeIf { it.isNotBlank() }?.let { args += listOf("--exit-loc", it) }
        when (torMode) {
            TorMode.OFF -> Unit
            TorMode.CHAIN -> args += "--tor"
            TorMode.REVERSE -> args += "--tor-reverse"
            TorMode.ONLY -> args += "--tor-only"
        }
        if (torMode != TorMode.OFF) {
            when (torBridges) {
                TorBridges.AUTO -> args += "--tor-bridges"
                TorBridges.OFF -> args += "--no-tor-bridges"
                TorBridges.CUSTOM ->
                    torCustomBridges?.takeIf { it.isNotBlank() }?.lines()
                        ?.map { it.trim() }?.filter { it.isNotBlank() }
                        ?.forEach { args += listOf("--tor-bridge", it) }
            }
            torCountry?.takeIf { it.isNotBlank() }?.let { /* AETHER_TOR_COUNTRY only */ }
            args += listOf("--tor-bind", "${AetherCore.SOCKS_ADDR}:${AetherCore.TOR_SOCKS_PORT}")
        }
        when (psiphonMode) {
            PsiphonMode.OFF -> Unit
            PsiphonMode.CHAIN -> args += "--psiphon"
            PsiphonMode.REVERSE -> args += "--psiphon-reverse"
            PsiphonMode.ONLY -> args += "--psiphon-only"
        }
        if (psiphonMode != PsiphonMode.OFF) {
            psiphonRegion?.takeIf { it.isNotBlank() }?.let { args += listOf("--psiphon-region", it) }
            psiphonCdnSni?.takeIf { it.isNotBlank() }?.let { args += listOf("--psiphon-cdn-sni", it) }
            psiphonBinPath?.takeIf { it.isNotBlank() }?.let { args += listOf("--psiphon-bin", it) }
            psiphonConfigPath?.takeIf { it.isNotBlank() }?.let { args += listOf("--psiphon-config", it) }
            psiphonServerEntriesPath?.takeIf { it.isNotBlank() }
                ?.let { args += listOf("--psiphon-server-entries", it) }
        }
        zeroTrustTeam?.takeIf { it.isNotBlank() }?.let { args += listOf("--team", it) }
        zeroTrustEmail?.takeIf { it.isNotBlank() }?.let { args += listOf("--access-email", it) }
        if (zeroTrustGateway) args += "--gateway"
        extraCliArgs?.takeIf { it.isNotBlank() }?.lines()
            ?.map { it.trim() }?.filter { it.isNotBlank() }
            ?.forEach { args += it }
        return args
    }

    // -- persistence ----------------------------------------------------------

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("transport", transport.name)
        o.put("peer", peer)
        o.put("outerPeer", outerPeer)
        o.put("innerPeer", innerPeer)
        o.put("goolPeer", goolPeer)
        o.put("scanMode", scanMode.name)
        o.put("scanIp", scanIp.name)
        o.put("scanPorts", scanPorts)
        o.put("excludedEndpoints", excludedEndpoints)
        o.put("quickReconnect", quickReconnect)
        o.put("noizeProfile", noizeProfile.name)
        o.put("fragmentHello", fragmentHello)
        fragmentSize?.let { o.put("fragmentSize", it) }
        fragmentDelayMs?.let { o.put("fragmentDelayMs", it) }
        o.put("ech", ech)
        o.put("echDomain", echDomain)
        o.put("masqueHttp2", masqueHttp2)
        o.put("quicV2", quicV2)
        o.put("masqueSni", masqueSni)
        o.put("masqueH2Peer", masqueH2Peer)
        o.put("torMode", torMode.name)
        o.put("torBridges", torBridges.name)
        o.put("torCustomBridges", torCustomBridges)
        o.put("torCountry", torCountry)
        o.put("psiphonMode", psiphonMode.name)
        o.put("psiphonRegion", psiphonRegion)
        o.put("psiphonCdnSni", psiphonCdnSni)
        o.put("psiphonBinPath", psiphonBinPath)
        o.put("psiphonConfigPath", psiphonConfigPath)
        o.put("psiphonServerEntriesPath", psiphonServerEntriesPath)
        o.put("zeroTrustTeam", zeroTrustTeam)
        o.put("zeroTrustEmail", zeroTrustEmail)
        o.put("zeroTrustGateway", zeroTrustGateway)
        o.put("routeBlock", routeBlock)
        o.put("routeDirect", routeDirect)
        o.put("inTunnelDns", inTunnelDns)
        o.put("upstreamProxy", upstreamProxy)
        o.put("exitLocation", exitLocation)
        o.put("keepaliveSecs", keepaliveSecs)
        httpProxyPort?.let { o.put("httpProxyPort", it) }
        o.put("killSwitchFailClosed", killSwitchFailClosed)
        o.put("extraCliArgs", extraCliArgs)
        o.put("extraEnv", extraEnv)
        o.put("tunnelEnabled", tunnelEnabled)
        o.put("lastEndpoint", lastEndpoint)
        o.put("lastScanAtMs", lastScanAtMs)
        o.put("backupSocks5Json", backupSocks5Json)
        return o
    }

    companion object {
        private const val PREFS = "aether"
        private const val KEY_CONFIG = "config_v1"

        fun load(context: Context): AetherConfig {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_CONFIG, null) ?: return AetherConfig()
            return try {
                fromJson(JSONObject(raw))
            } catch (e: Exception) {
                AetherConfig()
            }
        }

        fun fromJson(o: JSONObject): AetherConfig {
            val c = AetherConfig()
            c.transport = runCatching { Transport.valueOf(o.optString("transport", "MASQUE")) }.getOrDefault(Transport.MASQUE)
            c.peer = o.optString("peer").takeIf { o.has("peer") && it.isNotBlank() }
            c.outerPeer = o.optString("outerPeer").takeIf { o.has("outerPeer") && it.isNotBlank() }
            c.innerPeer = o.optString("innerPeer").takeIf { o.has("innerPeer") && it.isNotBlank() }
            c.goolPeer = o.optString("goolPeer").takeIf { o.has("goolPeer") && it.isNotBlank() }
            c.scanMode = runCatching { ScanMode.valueOf(o.optString("scanMode", "BALANCED")) }.getOrDefault(ScanMode.BALANCED)
            c.scanIp = runCatching { ScanIp.valueOf(o.optString("scanIp", "V4")) }.getOrDefault(ScanIp.V4)
            c.scanPorts = o.optString("scanPorts").takeIf { o.has("scanPorts") && it.isNotBlank() }
            c.excludedEndpoints = o.optString("excludedEndpoints").takeIf { o.has("excludedEndpoints") && it.isNotBlank() }
            c.quickReconnect = o.optBoolean("quickReconnect", true)
            c.noizeProfile = runCatching { NoizeProfile.valueOf(o.optString("noizeProfile", "FIREWALL")) }.getOrDefault(NoizeProfile.FIREWALL)
            c.fragmentHello = o.optBoolean("fragmentHello", false)
            if (o.has("fragmentSize")) c.fragmentSize = o.optInt("fragmentSize")
            if (o.has("fragmentDelayMs")) c.fragmentDelayMs = o.optInt("fragmentDelayMs")
            c.ech = o.optBoolean("ech", false)
            c.echDomain = o.optString("echDomain").takeIf { o.has("echDomain") && it.isNotBlank() }
            c.masqueHttp2 = o.optBoolean("masqueHttp2", false)
            c.quicV2 = o.optBoolean("quicV2", true)
            c.masqueSni = o.optString("masqueSni").takeIf { o.has("masqueSni") && it.isNotBlank() }
            c.masqueH2Peer = o.optString("masqueH2Peer").takeIf { o.has("masqueH2Peer") && it.isNotBlank() }
            c.torMode = runCatching { TorMode.valueOf(o.optString("torMode", "OFF")) }.getOrDefault(TorMode.OFF)
            c.torBridges = runCatching { TorBridges.valueOf(o.optString("torBridges", "AUTO")) }.getOrDefault(TorBridges.AUTO)
            c.torCustomBridges = o.optString("torCustomBridges").takeIf { o.has("torCustomBridges") && it.isNotBlank() }
            c.torCountry = o.optString("torCountry").takeIf { o.has("torCountry") && it.isNotBlank() }
            c.psiphonMode = runCatching { PsiphonMode.valueOf(o.optString("psiphonMode", "OFF")) }.getOrDefault(PsiphonMode.OFF)
            c.psiphonRegion = o.optString("psiphonRegion").takeIf { o.has("psiphonRegion") && it.isNotBlank() }
            c.psiphonCdnSni = o.optString("psiphonCdnSni").takeIf { o.has("psiphonCdnSni") && it.isNotBlank() }
            c.psiphonBinPath = o.optString("psiphonBinPath").takeIf { o.has("psiphonBinPath") && it.isNotBlank() }
            c.psiphonConfigPath = o.optString("psiphonConfigPath").takeIf { o.has("psiphonConfigPath") && it.isNotBlank() }
            c.psiphonServerEntriesPath = o.optString("psiphonServerEntriesPath").takeIf { o.has("psiphonServerEntriesPath") && it.isNotBlank() }
            c.zeroTrustTeam = o.optString("zeroTrustTeam").takeIf { o.has("zeroTrustTeam") && it.isNotBlank() }
            c.zeroTrustEmail = o.optString("zeroTrustEmail").takeIf { o.has("zeroTrustEmail") && it.isNotBlank() }
            c.zeroTrustGateway = o.optBoolean("zeroTrustGateway", false)
            c.routeBlock = o.optString("routeBlock").takeIf { o.has("routeBlock") && it.isNotBlank() }
            c.routeDirect = o.optString("routeDirect").takeIf { o.has("routeDirect") && it.isNotBlank() }
            c.inTunnelDns = o.optString("inTunnelDns").takeIf { o.has("inTunnelDns") && it.isNotBlank() }
            c.upstreamProxy = o.optString("upstreamProxy").takeIf { o.has("upstreamProxy") && it.isNotBlank() }
            c.exitLocation = o.optString("exitLocation").takeIf { o.has("exitLocation") && it.isNotBlank() }
            c.keepaliveSecs = o.optInt("keepaliveSecs", 5)
            if (o.has("httpProxyPort")) c.httpProxyPort = o.optInt("httpProxyPort")
            c.killSwitchFailClosed = o.optBoolean("killSwitchFailClosed", true)
            c.extraCliArgs = o.optString("extraCliArgs").takeIf { o.has("extraCliArgs") && it.isNotBlank() }
            c.extraEnv = o.optString("extraEnv").takeIf { o.has("extraEnv") && it.isNotBlank() }
            c.tunnelEnabled = o.optBoolean("tunnelEnabled", false)
            c.lastEndpoint = o.optString("lastEndpoint").takeIf { o.has("lastEndpoint") && it.isNotBlank() }
            c.lastScanAtMs = o.optLong("lastScanAtMs", 0L)
            c.backupSocks5Json = o.optString("backupSocks5Json").takeIf { o.has("backupSocks5Json") && it.isNotBlank() }
            return c
        }
    }

    fun save(context: Context) {
        val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONFIG, toJson().toString()).apply()
    }
}
