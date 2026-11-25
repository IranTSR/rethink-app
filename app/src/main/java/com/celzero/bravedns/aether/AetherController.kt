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
 * AetherController is the seam between the Aether engine and Rethink's
 * VPN/proxy system.
 *
 * HOW TRAFFIC FLOWS (primary path — works with the firestack AAR as it
 * ships today, no new tunnel plumbing):
 *   1. The engine serves a local SOCKS5 proxy on 127.0.0.1:1819.
 *   2. This controller makes that listener Rethink's selected custom
 *      SOCKS5 endpoint (proxyMode 0, the single custom-SOCKS5 slot):
 *      AppConfig.updateCustomSocks5Proxy() — the exact seam the proxy
 *      settings UI uses — so the next VPN (re)start registers it with
 *      firestack under id "S5" and the TUN egress goes through the engine.
 *   3. While the VPN is already running, VpnController.readdSocks5Proxy()
 *      re-registers the slot without restarting the tunnel.
 *   4. The engine is ALSO registered under the distinct firestack proxy
 *      id "AETHER" (VpnController.addAetherProxy(), scheme "aether://",
 *      falling back to "socks5://" on a firestack build that predates the
 *      new type), so per-app proxy rules and future tunnel modes can
 *      address the Aether proxy directly.
 *
 * FAIL-CLOSED: a dead SOCKS5 listener refuses connections, so TUN traffic
 * cannot silently leak out direct when the engine dies — the proxy itself
 * is the kill-switch. On top of that, when killSwitchFailClosed is on,
 * an unexpected engine death also stops the VPN outright
 * (VpnController.stop). Rethink's always-on/lockdown mode (if the user
 * enabled it in system settings) is untouched and keeps applying.
 *
 * BACKUP/RESTORE: the custom-SOCKS5 slot holds exactly one endpoint, so
 * enabling Aether backs the user's previous endpoint up into the
 * Aether prefs (host/port/name/credentials — app-private storage, the
 * same trust boundary as Rethink's own proxy database) and disabling
 * Aether restores it verbatim. If there was no previous endpoint, the
 * slot is cleared again on disable.
 */
package com.celzero.bravedns.aether

import android.content.Context
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.database.ProxyEndpointRepository
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_PROXY
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object AetherController : KoinComponent {

    private const val TAG = "AetherController"

    const val PROXY_NAME = "Aether"
    const val PROXY_HOST = "127.0.0.1"

    private val appConfig: AppConfig by inject()
    private val proxyEndpointRepository: ProxyEndpointRepository by inject()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** True when Aether is the active tunnel proxy (engine + SOCKS5 slot). */
    fun isTunnelEnabled(context: Context): Boolean =
        AetherConfig.load(context).tunnelEnabled && AetherEngine.isRunning()

    private fun isAetherEndpoint(e: ProxyEndpoint?): Boolean =
        e != null && e.proxyName == PROXY_NAME && e.proxyIP == PROXY_HOST &&
            e.proxyPort == AetherCore.SOCKS_PORT

    private fun backupJson(e: ProxyEndpoint): String =
        JSONObject()
            .put("name", e.proxyName)
            .put("ip", e.proxyIP)
            .put("port", e.proxyPort)
            .put("user", e.userName)
            .put("pass", e.password)
            .put("isUDP", e.isUDP)
            .toString()

    private fun restoreEndpoint(json: String, id: Int): ProxyEndpoint? {
        return try {
            val o = JSONObject(json)
            ProxyEndpoint(
                id,
                o.optString("name", "SOCKS5"),
                ProxyManager.ProxyMode.SOCKS5.value,
                ProxyEndpoint.DEFAULT_PROXY_TYPE,
                null,
                o.optString("ip"),
                o.optInt("port", 0),
                o.optString("user").takeIf { it.isNotEmpty() },
                o.optString("pass").takeIf { it.isNotEmpty() },
                isSelected = true,
                isCustom = true,
                isUDP = o.optBoolean("isUDP", false),
                modifiedDataTime = 0L,
                latency = 0,
            )
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG cannot restore backed-up SOCKS5 endpoint", e)
            null
        }
    }

    /**
     * Takes over the custom-SOCKS5 slot for the engine. Backs the user's
     * previous endpoint up first (unless the slot already IS Aether's).
     */
    suspend fun claimSocks5Slot(context: Context): Boolean {
        return try {
            val config = AetherConfig.load(context)
            val current = appConfig.getSocks5ProxyDetails()
            if (isAetherEndpoint(current)) {
                Logger.i(LOG_TAG_VPN, "$TAG SOCKS5 slot already Aether's")
                return true
            }
            if (current != null) {
                config.backupSocks5Json = backupJson(current)
                config.save(context)
                Logger.i(
                    LOG_TAG_VPN,
                    "$TAG backed up previous SOCKS5 endpoint ${current.proxyName} " +
                        "${current.proxyIP}:${current.proxyPort}"
                )
            }
            val endpoint =
                ProxyEndpoint(
                    current?.id ?: 0,
                    PROXY_NAME,
                    ProxyManager.ProxyMode.SOCKS5.value,
                    ProxyEndpoint.DEFAULT_PROXY_TYPE,
                    null,
                    PROXY_HOST,
                    AetherCore.SOCKS_PORT,
                    null,
                    null,
                    isSelected = true,
                    isCustom = true,
                    isUDP = false,
                    modifiedDataTime = 0L,
                    latency = 0,
                )
            if (current == null) {
                // No SOCKS5 row exists yet: insert, then re-fetch so the
                // generated id is known before updating.
                proxyEndpointRepository.insert(endpoint)
                val inserted = appConfig.getSocks5ProxyDetails()
                if (inserted == null) {
                    Logger.e(LOG_TAG_VPN, "$TAG SOCKS5 row insert did not stick")
                    return false
                }
                inserted.proxyName = PROXY_NAME
                inserted.proxyIP = PROXY_HOST
                inserted.proxyPort = AetherCore.SOCKS_PORT
                inserted.isSelected = true
                appConfig.updateCustomSocks5Proxy(inserted)
            } else {
                appConfig.updateCustomSocks5Proxy(endpoint)
            }
            Logger.i(LOG_TAG_VPN, "$TAG SOCKS5 slot claimed for Aether")
            true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG claim SOCKS5 slot failed", e)
            false
        }
    }

    /** Gives the custom-SOCKS5 slot back: restores the backed-up endpoint,
     *  or clears the proxy entirely when there was none. */
    suspend fun releaseSocks5Slot(context: Context) {
        try {
            val config = AetherConfig.load(context)
            val current = appConfig.getSocks5ProxyDetails()
            val backup = config.backupSocks5Json
            config.backupSocks5Json = null
            config.save(context)
            if (backup.isNullOrBlank()) {
                if (isAetherEndpoint(current)) {
                    proxyEndpointRepository.removeConnectionStatus()
                    appConfig.removeAllProxies()
                    Logger.i(LOG_TAG_VPN, "$TAG SOCKS5 slot cleared (no backup)")
                }
                return
            }
            val restored = restoreEndpoint(backup, current?.id ?: 0)
            if (restored == null) return
            if (current == null) {
                proxyEndpointRepository.insert(restored)
                val inserted = appConfig.getSocks5ProxyDetails()
                if (inserted != null) appConfig.updateCustomSocks5Proxy(inserted)
            } else {
                appConfig.updateCustomSocks5Proxy(restored)
            }
            Logger.i(LOG_TAG_VPN, "$TAG previous SOCKS5 endpoint restored")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG release SOCKS5 slot failed", e)
        }
    }

    /**
     * Full enable: claim the SOCKS5 slot, scan (unless a manual peer is
     * set), start the engine, register the distinct AETHER proxy id, and
     * re-register the slot with a running VPN (or start the VPN).
     * Returns an error string, or null on success.
     */
    suspend fun enableTunnel(context: Context, startVpnIfDown: Boolean = true): String? {
        if (!AetherCore.isSupported()) {
            return "Aether needs Android 8 (API 26) or newer on arm64 / armv7"
        }
        if (!AetherCore.available) {
            return "Aether engine failed to load (see log)"
        }
        val config = AetherConfig.load(context)
        if (!claimSocks5Slot(context)) {
            return "Could not take over the SOCKS5 proxy slot"
        }
        // Scan first (skipped automatically when a manual peer is set).
        // CLI mode (nested transports / Tor / Psiphon) does its own
        // scanning inside the whole-engine run — no FFI scan needed.
        val peer =
            if (config.requiresCliMode()) {
                config.peer?.takeIf { it.isNotBlank() } ?: "auto"
            } else {
                AetherEngine.scanEndpoint(context, config)
                    ?: run {
                        releaseSocks5Slot(context)
                        return AetherEngine.lastError() ?: "Endpoint scan failed"
                    }
            }
        config.lastEndpoint = peer
        config.lastScanAtMs = System.currentTimeMillis()
        config.tunnelEnabled = true
        config.save(context)
        val started =
            AetherEngine.startTunnel(context, config, peer) { msg ->
                onTunnelDied(context, msg)
            }
        if (!started) {
            config.tunnelEnabled = false
            config.save(context)
            releaseSocks5Slot(context)
            return AetherEngine.lastError() ?: "Tunnel failed to start"
        }
        // Distinct firestack id (best-effort) + live re-registration.
        scope.launch {
            try {
                VpnController.addAetherProxy()
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG addAetherProxy failed", e)
            }
            try {
                VpnController.readdSocks5Proxy()
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG readdSocks5Proxy failed", e)
            }
        }
        if (startVpnIfDown) {
            VpnController.start(context)
        }
        return null
    }

    /** Full disable: stop the engine, drop the AETHER id, restore the slot. */
    suspend fun disableTunnel(context: Context) {
        val config = AetherConfig.load(context)
        config.tunnelEnabled = false
        config.save(context)
        AetherEngine.stopTunnel()
        try {
            VpnController.removeAetherProxy()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG removeAetherProxy failed", e)
        }
        releaseSocks5Slot(context)
        try {
            VpnController.readdSocks5Proxy()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG readdSocks5Proxy after disable failed", e)
        }
    }

    /** Fail-closed hook: the engine died unexpectedly while enabled. */
    private fun onTunnelDied(context: Context, message: String) {
        val config = AetherConfig.load(context)
        if (!config.tunnelEnabled) return
        Logger.e(LOG_TAG_VPN, "$TAG engine died: $message")
        if (!config.killSwitchFailClosed) {
            Logger.i(LOG_TAG_VPN, "$TAG fail-closed is off; leaving VPN state alone")
            return
        }
        // The dead SOCKS5 listener already refuses traffic (fail-closed by
        // construction); stopping the VPN as well makes the failure visible
        // instead of leaving a VPN up that cannot carry anything.
        scope.launch {
            try {
                VpnController.stop("aether engine died: $message", context)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG stop VPN after engine death failed", e)
            }
        }
    }

    // -- one-shot actions (used by the UI's scan / verify buttons) -----------

    /** Plain endpoint scan (current config). Persists the result. */
    suspend fun scanNow(context: Context): String? {
        val config = AetherConfig.load(context)
        val peer = AetherEngine.scanEndpoint(context, config)
        if (peer != null) {
            config.lastEndpoint = peer
            config.lastScanAtMs = System.currentTimeMillis()
            config.save(context)
        }
        return peer
    }

    /**
     * "Clean-IP" scan: the strictest scan mode (ironclad — a real tunnel
     * and a real HTTP request per candidate) followed by the data-plane
     * verify. Returns (endpoint, reachable).
     */
    suspend fun cleanIpScan(context: Context): Pair<String?, Boolean> {
        val config = AetherConfig.load(context)
        val strict = config.copy(scanMode = AetherConfig.ScanMode.IRONCLAD)
        val peer = AetherEngine.scanEndpoint(context, strict)
        if (peer == null) return Pair(null, false)
        val reachable = AetherEngine.verifyEndpoint(context, strict, peer)
        if (reachable) {
            config.lastEndpoint = peer
            config.lastScanAtMs = System.currentTimeMillis()
            config.save(context)
        }
        return Pair(peer, reachable)
    }

    /** Data-plane verify of one endpoint (handshake alone is not trusted). */
    suspend fun verifyNow(context: Context, peer: String): Boolean {
        val config = AetherConfig.load(context)
        return AetherEngine.verifyEndpoint(context, config, peer)
    }
}
