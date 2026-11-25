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
 * AetherEngine drives the vendored Aether core (native/aether) through the
 * JSON FFI in AetherCore. Two invocation paths, by design:
 *
 *  MANAGED (default) — structured FFI jobs:
 *    identity open -> endpoint scan -> data-plane verify -> tunnel start.
 *    Serves SOCKS5 on 127.0.0.1:1819. Supports plain MASQUE (HTTP/3 or
 *    HTTP/2) and WireGuard only.
 *
 *  CLI (whole-engine) — aether_core_start with the full CLI argument list:
 *    the only path for nested transports (gool / gool-classic / mim),
 *    Tor-only / Tor-reverse and Psiphon-only / Psiphon-reverse.
 *    AetherConfig.requiresCliMode() picks it automatically.
 *
 * All engine calls are serialised through one Mutex: the core reads its
 * AETHER_* options from the process environment at call time, and
 * applyEnv() rewrites that environment per config, so two overlapping
 * calls could otherwise run with each other's options.
 *
 * Identity files live in <filesDir>/aether/ and are the core's own
 * business (WARP device registration). Losing them only means a fresh
 * device registration (Cloudflare rate-limits rapid re-registration, so
 * the directory is never wiped by this app).
 */
package com.celzero.bravedns.aether

import android.content.Context
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed class AetherState {
    object Idle : AetherState()
    object OpeningIdentity : AetherState()
    object Scanning : AetherState()
    object Verifying : AetherState()
    object Connecting : AetherState()
    data class Connected(val endpoint: String, val transport: String, val cliMode: Boolean) :
        AetherState()

    object Stopped : AetherState()
    data class Error(val message: String) : AetherState()
}

object AetherEngine {

    private const val TAG = "AetherEngine"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    private val _state = MutableStateFlow<AetherState>(AetherState.Idle)
    val state: StateFlow<AetherState> = _state.asStateFlow()

    private var identityId: Long? = null
    private var tunnelJobId: Long? = null
    private var watcherJob: Job? = null
    private var lastError: String? = null

    fun lastError(): String? = lastError

    fun isRunning(): Boolean =
        _state.value is AetherState.Connected ||
            _state.value is AetherState.Connecting

    // -- environment ---------------------------------------------------------

    /** Applies the config as AETHER_* env vars; unsets owned keys the
     *  config does not set, so a stale value can never leak into a run. */
    fun applyEnv(config: AetherConfig) {
        val env = config.toEnvMap()
        for (key in AetherConfig.OWNED_ENV_KEYS) {
            val value = env[key]
            if (value != null) AetherCore.setEnv(key, value) else AetherCore.unsetEnv(key)
        }
        Logger.i(LOG_TAG_VPN, "$TAG env applied (${env.size} vars)")
    }

    private fun identityBasePath(context: Context): String {
        val dir = File(context.filesDir, "aether")
        if (!dir.exists()) dir.mkdirs()
        // The core derives per-transport/team siblings from this base path
        // (api::identity_path); WireGuard uses it as-is.
        return File(dir, "identity.json").absolutePath
    }

    // -- job polling -----------------------------------------------------------

    /**
     * Polls a core job until it finishes, fails, or [timeoutMs] elapses.
     * Returns the job's "result" object on success, null on error/timeout
     * (with lastError set). The job is freed before returning.
     */
    private suspend fun pollJob(jobId: Long, timeoutMs: Long): JSONObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (System.currentTimeMillis() < deadline) {
                val reply = withContext(Dispatchers.IO) { AetherCore.jobPoll(jobId) }
                if (reply == null) {
                    lastError = "engine did not answer job poll"
                    return null
                }
                if (!AetherCore.isOk(reply)) {
                    lastError = AetherCore.errorOf(reply)
                    return null
                }
                val state = reply.optString("state")
                if (state == "done") {
                    val result = reply.optJSONObject("result")
                    if (result == null) {
                        lastError = "engine job finished without a result"
                        return null
                    }
                    if (!AetherCore.isOk(result)) {
                        lastError = AetherCore.errorOf(result)
                        return null
                    }
                    return result
                }
                delay(500)
            }
            lastError = "engine job timed out"
            withContext(Dispatchers.IO) { AetherCore.jobCancel(jobId) }
            return null
        } finally {
            withContext(Dispatchers.IO) { AetherCore.jobFree(jobId) }
        }
    }

    private fun startJobId(reply: JSONObject?): Long? {
        if (reply == null || !AetherCore.isOk(reply)) {
            lastError = reply?.let { AetherCore.errorOf(it) } ?: "engine did not answer"
            return null
        }
        val job = reply.optLong("job", -1L)
        if (job < 0) {
            lastError = "engine reply carried no job id: $reply"
            return null
        }
        return job
    }

    // -- identity ----------------------------------------------------------------

    /**
     * Opens (loads or registers) the WARP identity for this config.
     * Returns the identity handle, or null on failure (see lastError()).
     */
    suspend fun openIdentity(context: Context, config: AetherConfig): Long? =
        mutex.withLock {
            identityId?.let { return@withLock it }
            if (!AetherCore.available) {
                lastError = "Aether engine is not available on this device"
                _state.value = AetherState.Error(lastError!!)
                return@withLock null
            }
            _state.value = AetherState.OpeningIdentity
            applyEnv(config)
            val payload = config.toIdentityPayload(identityBasePath(context))
            val reply = withContext(Dispatchers.IO) { AetherCore.identityOpen(payload) }
            val jobId = startJobId(reply)
            if (jobId == null) {
                _state.value = AetherState.Error(lastError!!)
                return@withLock null
            }
            // Registration talks to the WARP API; allow up to 3 minutes.
            val result = pollJob(jobId, 180_000L)
            if (result == null) {
                _state.value = AetherState.Error(lastError!!)
                return@withLock null
            }
            val id = result.optLong("identity", -1L)
            if (id < 0) {
                lastError = "engine identity reply carried no handle: $result"
                _state.value = AetherState.Error(lastError!!)
                return@withLock null
            }
            identityId = id
            val summary = result.optJSONObject("summary")
            Logger.i(LOG_TAG_VPN, "$TAG identity opened (handle=$id, summary=$summary)")
            _state.value = AetherState.Idle
            id
        }

    // -- scan / verify ------------------------------------------------------------

    /**
     * Scans for a reachable WARP endpoint. Returns the endpoint as a
     * display string (ip:port) or null on failure. When config.peer is set
     * the scan is skipped and the peer is returned as-is.
     */
    suspend fun scanEndpoint(context: Context, config: AetherConfig): String? {
        config.peer?.takeIf { it.isNotBlank() }?.let { peer ->
            Logger.i(LOG_TAG_VPN, "$TAG manual peer, skipping scan: $peer")
            return peer.trim()
        }
        val identity = openIdentity(context, config) ?: return null
        return mutex.withLock {
            _state.value = AetherState.Scanning
            applyEnv(config)
            val reply =
                withContext(Dispatchers.IO) {
                    AetherCore.scanStart(identity, config.toScanPayload())
                }
            val jobId = startJobId(reply)
            if (jobId == null) {
                _state.value = AetherState.Error(lastError!!)
                return@withLock null
            }
            // Thorough / ironclad scans sweep whole ranges; allow 10 minutes.
            val result = pollJob(jobId, 600_000L)
            if (result == null) {
                _state.value = AetherState.Error(lastError!!)
                return@withLock null
            }
            val endpoint = endpointToString(result.opt("endpoint"))
            if (endpoint == null) {
                lastError = "scan finished without an endpoint: $result"
                _state.value = AetherState.Error(lastError!!)
                return@withLock null
            }
            Logger.i(LOG_TAG_VPN, "$TAG scan found endpoint $endpoint")
            _state.value = AetherState.Idle
            endpoint
        }
    }

    /**
     * Runs the core's end-to-end data-plane check against one endpoint
     * (the "clean-IP" / verify step: a handshake alone is not trusted).
     * Returns true when the endpoint actually carries traffic.
     */
    suspend fun verifyEndpoint(context: Context, config: AetherConfig, peer: String): Boolean {
        val identity = openIdentity(context, config) ?: return false
        return mutex.withLock {
            _state.value = AetherState.Verifying
            applyEnv(config)
            val reply =
                withContext(Dispatchers.IO) {
                    AetherCore.verifyStart(identity, config.toVerifyPayload(peer))
                }
            val jobId = startJobId(reply)
            if (jobId == null) {
                _state.value = AetherState.Error(lastError!!)
                return@withLock false
            }
            val result = pollJob(jobId, 120_000L)
            if (result == null) {
                _state.value = AetherState.Error(lastError!!)
                return@withLock false
            }
            val reachable = result.optBoolean("reachable", false)
            Logger.i(LOG_TAG_VPN, "$TAG verify $peer reachable=$reachable")
            _state.value = AetherState.Idle
            reachable
        }
    }

    /** Best-effort rendering of the core's Endpoint JSON into "ip:port". */
    fun endpointToString(raw: Any?): String? {
        when (raw) {
            null -> return null
            is String -> return raw.trim().takeIf { it.isNotBlank() }
            is JSONObject -> {
                for (key in listOf("addr", "socket", "ip_port", "endpoint", "address")) {
                    val v = raw.optString(key)
                    if (v.isNotBlank()) return v
                }
                val ip = raw.optString("ip")
                val port = raw.optInt("port", -1)
                if (ip.isNotBlank() && port > 0) return "$ip:$port"
                return raw.toString()
            }
            else -> return raw.toString()
        }
    }

    // -- tunnel ---------------------------------------------------------------------

    /**
     * Starts the tunnel for [peer] (use scanEndpoint() first, unless the
     * config names a manual peer). Returns true when the SOCKS5 listener
     * is up. The tunnel job is watched: if it ends on its own (error),
     * the state becomes Error and [onTunnelDied] runs — AetherController
     * uses that for the fail-closed kill-switch.
     */
    suspend fun startTunnel(
        context: Context,
        config: AetherConfig,
        peer: String,
        onTunnelDied: ((String) -> Unit)? = null,
    ): Boolean {
        if (config.requiresCliMode()) {
            return startCliMode(context, config, onTunnelDied)
        }
        val identity = openIdentity(context, config) ?: return false
        return mutex.withLock {
            _state.value = AetherState.Connecting
            applyEnv(config)
            val reply =
                withContext(Dispatchers.IO) {
                    AetherCore.tunnelStart(identity, config.toTunnelPayload(peer))
                }
            val jobId = startJobId(reply)
            if (jobId == null) {
                _state.value = AetherState.Error(lastError!!)
                return@withLock false
            }
            tunnelJobId = jobId
            _state.value =
                AetherState.Connected(
                    endpoint = peer,
                    transport = config.transport.name,
                    cliMode = false,
                )
            Logger.i(
                LOG_TAG_VPN,
                "$TAG tunnel job $jobId up (SOCKS ${AetherCore.SOCKS_ADDR}:${AetherCore.SOCKS_PORT}, peer $peer)"
            )
            watchTunnelJob(jobId, peer, config.transport.name, false, onTunnelDied)
            true
        }
    }

    /**
     * Whole-engine path: runs the CLI engine with the config's argument
     * list. Used for gool / gool-classic / mim, Tor-only / Tor-reverse and
     * Psiphon modes, which the structured FFI tunnel cannot express.
     */
    suspend fun startCliMode(
        context: Context,
        config: AetherConfig,
        onTunnelDied: ((String) -> Unit)? = null,
    ): Boolean {
        // The CLI engine registers/loads its own identity from the same
        // filesDir base; still apply the env first (tor country, bridges…).
        applyEnv(config)
        val args = config.toCliArgs()
        Logger.i(LOG_TAG_VPN, "$TAG cli-mode args: ${args.joinToString(" ")}")
        return mutex.withLock {
            _state.value = AetherState.Connecting
            val reply = withContext(Dispatchers.IO) { AetherCore.coreStart(args) }
            val jobId = startJobId(reply)
            if (jobId == null) {
                _state.value = AetherState.Error(lastError!!)
                return@withLock false
            }
            tunnelJobId = jobId
            val endpoint = config.peer ?: config.lastEndpoint ?: "auto (scan)"
            _state.value =
                AetherState.Connected(
                    endpoint = endpoint,
                    transport = config.transport.name,
                    cliMode = true,
                )
            Logger.i(LOG_TAG_VPN, "$TAG cli-mode job $jobId up")
            watchTunnelJob(jobId, endpoint, config.transport.name, true, onTunnelDied)
            true
        }
    }

    /** Watches a tunnel job; a job that finishes on its own means the
     *  tunnel died (error) or was stopped cleanly — never silently. */
    private fun watchTunnelJob(
        jobId: Long,
        endpoint: String,
        transport: String,
        cliMode: Boolean,
        onTunnelDied: ((String) -> Unit)?,
    ) {
        watcherJob?.cancel()
        watcherJob = scope.launch {
            while (true) {
                delay(2000)
                if (tunnelJobId != jobId) return@launch // superseded / stopped
                val reply = withContext(Dispatchers.IO) { AetherCore.jobPoll(jobId) }
                if (reply == null) continue // transient; keep watching
                if (AetherCore.isOk(reply) && reply.optString("state") == "done") {
                    val result = reply.optJSONObject("result")
                    val stoppedCleanly =
                        result?.optString("state") == "stopped" ||
                            result?.optString("state") == "closed"
                    tunnelJobId = null
                    withContext(Dispatchers.IO) { AetherCore.jobFree(jobId) }
                    if (stoppedCleanly) {
                        _state.value = AetherState.Stopped
                        Logger.i(LOG_TAG_VPN, "$TAG tunnel job $jobId ended cleanly")
                    } else {
                        val msg =
                            result?.let { AetherCore.errorOf(it) }
                                ?: "tunnel ended unexpectedly"
                        lastError = msg
                        _state.value = AetherState.Error(msg)
                        Logger.e(LOG_TAG_VPN, "$TAG tunnel job $jobId died: $msg")
                        onTunnelDied?.invoke(msg)
                    }
                    return@launch
                }
            }
        }
    }

    /** Stops the tunnel job (if any) and frees the identity handle. */
    suspend fun stopTunnel() {
        mutex.withLock {
            watcherJob?.cancel()
            watcherJob = null
            tunnelJobId?.let { jobId ->
                withContext(Dispatchers.IO) {
                    AetherCore.jobCancel(jobId)
                    AetherCore.jobFree(jobId)
                }
                tunnelJobId = null
            }
            identityId?.let { id ->
                withContext(Dispatchers.IO) { AetherCore.identityFree(id) }
                identityId = null
            }
            _state.value = AetherState.Stopped
            Logger.i(LOG_TAG_VPN, "$TAG tunnel stopped")
        }
    }

    // -- Zero Trust (email-code flow) --------------------------------------------------
    // The token the core obtains stays in the core's own storage; the app
    // never persists it. These helpers only drive the FFI calls.

    /** Starts an email-code sign-in; returns the session handle, or null. */
    suspend fun teamCodeRequest(
        context: Context,
        config: AetherConfig,
        team: String,
        email: String,
    ): Long? {
        if (!AetherCore.available) return null
        applyEnv(config)
        val payload = JSONObject()
        payload.put("team", team)
        payload.put("email", email)
        val reply = withContext(Dispatchers.IO) { AetherCore.teamCodeRequest(payload) }
        val jobId = startJobId(reply) ?: return null
        val result = pollJob(jobId, 120_000L) ?: return null
        return result.optLong("session", -1L).takeIf { it >= 0 }
    }

    /** Submits the emailed code; returns true when the team token is stored. */
    suspend fun teamCodeSubmit(session: Long, code: String): Boolean {
        if (!AetherCore.available) return false
        val reply = withContext(Dispatchers.IO) { AetherCore.teamCodeSubmit(session, code) }
        val jobId = startJobId(reply) ?: return false
        val result = pollJob(jobId, 120_000L) ?: return false
        return result.optBoolean("signed_in", false)
    }

    suspend fun teamCodeResend(session: Long): Boolean {
        if (!AetherCore.available) return false
        val reply = withContext(Dispatchers.IO) { AetherCore.teamCodeResend(session) }
        val jobId = startJobId(reply) ?: return false
        return pollJob(jobId, 60_000L) != null
    }

    fun teamSessionFree(session: Long) {
        if (!AetherCore.available) return
        AetherCore.teamSessionFree(session)
    }

    /** Forgets the stored Zero Trust team token (sign-out). */
    suspend fun teamTokenClear(): Boolean {
        if (!AetherCore.available) return false
        val reply = withContext(Dispatchers.IO) { AetherCore.teamTokenClear() }
        return reply != null && AetherCore.isOk(reply)
    }

    fun versionString(): String? = AetherCore.engineVersionString()
}
