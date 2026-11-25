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
 * NOTE: this file talks to the vendored Aether engine (native/aether,
 * AGPL-3.0, CluvexStudio) through OUR OWN JNI bridge (scripts/aether-jni.c).
 * The bridge forwards to the engine's plain-C JSON FFI (aether/src/ffi.rs).
 * Every native call returns a JSON string: {"ok":true,…} or
 * {"ok":false,"error":"…"}.
 */
package com.celzero.bravedns.aether

import android.os.Build
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
import org.json.JSONObject

object AetherCore {

    private const val TAG = "AetherCore"

    /** The engine is built for NDK API 26 (see scripts/build-aether.sh). */
    const val MIN_API = 26

    /** SOCKS5 listen address the engine serves (both invocation paths). */
    const val SOCKS_ADDR = "127.0.0.1"
    const val SOCKS_PORT = 1819

    /** Second SOCKS5 listener the engine opens when Tor rides inside the tunnel. */
    const val TOR_SOCKS_PORT = 1820

    /** True when this device can run the engine at all (ABI + API level). */
    fun isSupported(): Boolean {
        if (Build.VERSION.SDK_INT < MIN_API) return false
        val abis = Build.SUPPORTED_ABIS ?: return false
        return abis.any { it == "arm64-v8a" || it == "armeabi-v7a" }
    }

    /** True once libaether.so + libaetherjni.so loaded successfully. */
    var available: Boolean = false
        private set

    /** True when the loaded engine was built with the `tor` cargo feature. */
    var hasTor: Boolean = false
        private set

    init {
        if (isSupported()) {
            try {
                // Order matters: the bridge DT_NEEDEDs libaether.so.
                System.loadLibrary("aether")
                System.loadLibrary("aetherjni")
                available = true
                hasTor = nativeHasTor()
                Logger.i(LOG_TAG_VPN, "$TAG native libs loaded (hasTor=$hasTor)")
            } catch (e: UnsatisfiedLinkError) {
                available = false
                Logger.e(LOG_TAG_VPN, "$TAG native libs unavailable: ${e.message}")
            } catch (e: Exception) {
                available = false
                Logger.e(LOG_TAG_VPN, "$TAG native libs failed to load", e)
            }
        } else {
            Logger.i(
                LOG_TAG_VPN,
                "$TAG not supported on this device (api=${Build.VERSION.SDK_INT}, " +
                    "abis=${Build.SUPPORTED_ABIS?.joinToString()})"
            )
        }
    }

    // -- JNI (implemented in scripts/aether-jni.c; names must match exactly) --

    private external fun nativeVersion(): String
    private external fun nativeSetEnv(key: String, value: String): Boolean
    private external fun nativeUnsetEnv(key: String): Boolean
    private external fun nativeHasTor(): Boolean
    private external fun nativeIdentityOpen(payload: String): String
    private external fun nativeIdentitySummary(id: Long): String
    private external fun nativeIdentityFree(id: Long): String
    private external fun nativeScanStart(identity: Long, payload: String): String
    private external fun nativeVerifyStart(identity: Long, payload: String): String
    private external fun nativeTunnelStart(identity: Long, payload: String): String
    private external fun nativeCoreStart(arguments: String): String
    private external fun nativeJobPoll(id: Long): String
    private external fun nativeJobCancel(id: Long): String
    private external fun nativeJobFree(id: Long): String
    private external fun nativeTeamSignIn(payload: String): String
    private external fun nativeTeamCodeRequest(payload: String): String
    private external fun nativeTeamCodeResend(session: Long): String
    private external fun nativeTeamCodeSubmit(session: Long, code: String): String
    private external fun nativeTeamSessionFree(id: Long): String
    private external fun nativeTeamTokenClear(): String

    // -- small JSON helpers -------------------------------------------------

    /** Parses an FFI reply; returns null and logs when the reply is not JSON. */
    fun parseReply(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG unparsable engine reply: $raw", e)
            null
        }
    }

    /** True when the FFI reply carries "ok":true. */
    fun isOk(reply: JSONObject?): Boolean = reply?.optBoolean("ok", false) == true

    /** The "error" string of a failed reply, or a generic message. */
    fun errorOf(reply: JSONObject?): String =
        reply?.optString("error")?.takeIf { it.isNotBlank() }
            ?: "unknown engine error"

    // -- env ----------------------------------------------------------------
    // The engine reads AETHER_* options from the process environment at call
    // time, so the Kotlin layer applies the whole AetherConfig as env vars
    // immediately before opening/scan/tunnel calls (see AetherEngine).

    fun setEnv(key: String, value: String): Boolean {
        if (!available) return false
        return try {
            nativeSetEnv(key, value)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG setEnv($key) failed", e)
            false
        }
    }

    fun unsetEnv(key: String): Boolean {
        if (!available) return false
        return try {
            nativeUnsetEnv(key)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG unsetEnv($key) failed", e)
            false
        }
    }

    // -- wrapped FFI calls (all return the raw parsed reply) ---------------

    fun version(): JSONObject? {
        if (!available) return null
        return runCatching { parseReply(nativeVersion()) }.getOrNull()
    }

    fun engineVersionString(): String? = version()?.optString("version")

    fun identityOpen(payload: JSONObject): JSONObject? {
        if (!available) return null
        return runCatching { parseReply(nativeIdentityOpen(payload.toString())) }.getOrNull()
    }

    fun identitySummary(id: Long): JSONObject? {
        if (!available) return null
        return runCatching { parseReply(nativeIdentitySummary(id)) }.getOrNull()
    }

    fun identityFree(id: Long) {
        if (!available) return
        runCatching { nativeIdentityFree(id) }
    }

    /** Starts an endpoint scan job; reply is {"job":N} on success. */
    fun scanStart(identity: Long, payload: JSONObject): JSONObject? {
        if (!available) return null
        return runCatching { parseReply(nativeScanStart(identity, payload.toString())) }.getOrNull()
    }

    /** Verifies one endpoint (data-plane check); reply is {"job":N}. */
    fun verifyStart(identity: Long, payload: JSONObject): JSONObject? {
        if (!available) return null
        return runCatching { parseReply(nativeVerifyStart(identity, payload.toString())) }.getOrNull()
    }

    /** Starts the tunnel job; reply is {"job":N}. The job stays "running"
     *  until the tunnel stops or is cancelled — poll it to watch for exit. */
    fun tunnelStart(identity: Long, payload: JSONObject): JSONObject? {
        if (!available) return null
        return runCatching { parseReply(nativeTunnelStart(identity, payload.toString())) }.getOrNull()
    }

    /**
     * Runs the WHOLE CLI engine with the given argument list (JSON array of
     * strings, e.g. ["--masque","--gool"]). This is the only path that
     * supports nested transports (gool / gool-classic / mim), Tor-only /
     * Tor-reverse and Psiphon-only / Psiphon-reverse — the structured FFI
     * tunnel only does plain MASQUE / WireGuard. Reply is {"job":N}.
     */
    fun coreStart(arguments: List<String>): JSONObject? {
        if (!available) return null
        val json = org.json.JSONArray(arguments).toString()
        return runCatching { parseReply(nativeCoreStart(json)) }.getOrNull()
    }

    /** Polls a job: {"state":"running"} or {"state":"done","result":{…}}. */
    fun jobPoll(id: Long): JSONObject? {
        if (!available) return null
        return runCatching { parseReply(nativeJobPoll(id)) }.getOrNull()
    }

    fun jobCancel(id: Long): JSONObject? {
        if (!available) return null
        return runCatching { parseReply(nativeJobCancel(id)) }.getOrNull()
    }

    fun jobFree(id: Long) {
        if (!available) return
        runCatching { nativeJobFree(id) }
    }

    // -- Zero Trust team (email-code flow only; no secrets are stored here) --

    fun teamSignIn(payload: JSONObject): JSONObject? {
        if (!available) return null
        return runCatching { parseReply(nativeTeamSignIn(payload.toString())) }.getOrNull()
    }

    fun teamCodeRequest(payload: JSONObject): JSONObject? {
        if (!available) return null
        return runCatching { parseReply(nativeTeamCodeRequest(payload.toString())) }.getOrNull()
    }

    fun teamCodeResend(session: Long): JSONObject? {
        if (!available) return null
        return runCatching { parseReply(nativeTeamCodeResend(session)) }.getOrNull()
    }

    fun teamCodeSubmit(session: Long, code: String): JSONObject? {
        if (!available) return null
        return runCatching { parseReply(nativeTeamCodeSubmit(session, code)) }.getOrNull()
    }

    fun teamSessionFree(id: Long) {
        if (!available) return
        runCatching { nativeTeamSessionFree(id) }
    }

    fun teamTokenClear(): JSONObject? {
        if (!available) return null
        return runCatching { parseReply(nativeTeamTokenClear()) }.getOrNull()
    }
}
