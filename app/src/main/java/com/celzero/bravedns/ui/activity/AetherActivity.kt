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
 * The Aether tunnel settings screen. One scrolling screen, one section per
 * engine feature area (transport, scan, noise, MASQUE/QUIC, Tor, Psiphon,
 * Zero Trust, routing/DNS, kill-switch, advanced). Every control writes
 * straight into AetherConfig (persisted in app-private SharedPreferences);
 * the engine itself is driven by AetherController / AetherEngine.
 */
package com.celzero.bravedns.ui.activity

import android.content.Intent
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.aether.AetherConfig
import com.celzero.bravedns.aether.AetherController
import com.celzero.bravedns.aether.AetherCore
import com.celzero.bravedns.aether.AetherEngine
import com.celzero.bravedns.aether.AetherState
import com.celzero.bravedns.databinding.ActivityAetherBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.SnackbarHelper.applySystemBarEdgeInsets
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import dev.androidbroadcast.vbpd.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.inject

class AetherActivity : BaseActivity(R.layout.activity_aether) {
    private val b by viewBinding(ActivityAetherBinding::bind)

    private val persistentState by inject<PersistentState>()

    private var config: AetherConfig = AetherConfig()
    private var teamSession: Long? = null
    private var busy: Boolean = false

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        b.root.applySystemBarEdgeInsets()
        handleFrostEffectIfNeeded(persistentState.theme)

        config = AetherConfig.load(this)
        setupSpinners()
        populateUi()
        wireButtons()
        observeEngine()

        val version = AetherEngine.versionString()
        b.aetherEngineVersion.text =
            if (version != null) getString(R.string.aether_engine_version, version)
            else getString(R.string.aether_engine_unavailable)
        if (!AetherCore.isSupported()) {
            b.aetherUnsupported.visibility = View.VISIBLE
            b.btnToggle.isEnabled = false
            b.btnScan.isEnabled = false
            b.btnVerify.isEnabled = false
            b.btnCleanIp.isEnabled = false
        }
        if (AetherCore.available && !AetherCore.hasTor) {
            b.tvTorUnavailable.visibility = View.VISIBLE
        }
        refreshToggle()
    }

    // -- spinners -------------------------------------------------------------

    private fun spinnerEntries(vararg resIds: Int): ArrayAdapter<String> {
        val items = resIds.map { getString(it) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return adapter
    }

    private fun setupSpinners() {
        b.spTransport.adapter =
            spinnerEntries(
                R.string.aether_transport_masque,
                R.string.aether_transport_wg,
                R.string.aether_transport_gool,
                R.string.aether_transport_gool_classic,
                R.string.aether_transport_mim,
            )
        b.spScanMode.adapter =
            spinnerEntries(
                R.string.aether_scan_turbo,
                R.string.aether_scan_balanced,
                R.string.aether_scan_thorough,
                R.string.aether_scan_verified,
                R.string.aether_scan_ironclad,
            )
        b.spScanIp.adapter =
            spinnerEntries(
                R.string.aether_scan_ip_v4,
                R.string.aether_scan_ip_v6,
                R.string.aether_scan_ip_dual,
            )
        b.spNoize.adapter =
            spinnerEntries(
                R.string.aether_noize_off,
                R.string.aether_noize_light,
                R.string.aether_noize_firewall,
                R.string.aether_noize_balanced,
                R.string.aether_noize_aggressive,
            )
        b.spTorMode.adapter =
            spinnerEntries(
                R.string.aether_tor_off,
                R.string.aether_tor_chain,
                R.string.aether_tor_reverse,
                R.string.aether_tor_only,
            )
        b.spTorBridges.adapter =
            spinnerEntries(
                R.string.aether_tor_bridges_auto,
                R.string.aether_tor_bridges_off,
                R.string.aether_tor_bridges_custom,
            )
        b.spPsiphonMode.adapter =
            spinnerEntries(
                R.string.aether_psiphon_off,
                R.string.aether_psiphon_chain,
                R.string.aether_psiphon_reverse,
                R.string.aether_psiphon_only,
            )
    }

    // -- ui <-> config ---------------------------------------------------------

    private fun textOf(edit: android.widget.EditText): String? =
        edit.text?.toString()?.trim().takeIf { !it.isNullOrBlank() }

    private fun intOf(edit: android.widget.EditText): Int? =
        edit.text?.toString()?.trim()?.toIntOrNull()

    private fun populateUi() {
        b.spTransport.setSelection(config.transport.ordinal)
        b.etPeer.setText(config.peer ?: "")
        b.etOuterPeer.setText(config.outerPeer ?: "")
        b.etInnerPeer.setText(config.innerPeer ?: "")
        b.etGoolPeer.setText(config.goolPeer ?: "")
        b.spScanMode.setSelection(config.scanMode.ordinal)
        b.spScanIp.setSelection(config.scanIp.ordinal)
        b.etScanPorts.setText(config.scanPorts ?: "")
        b.etExcluded.setText(config.excludedEndpoints ?: "")
        b.cbQuickReconnect.isChecked = config.quickReconnect
        b.spNoize.setSelection(config.noizeProfile.ordinal)
        b.cbFragment.isChecked = config.fragmentHello
        b.etFragmentSize.setText(config.fragmentSize?.toString() ?: "")
        b.etFragmentDelay.setText(config.fragmentDelayMs?.toString() ?: "")
        b.cbEch.isChecked = config.ech
        b.etEchDomain.setText(config.echDomain ?: "")
        b.cbHttp2.isChecked = config.masqueHttp2
        b.cbQuicV2.isChecked = config.quicV2
        b.etMasqueSni.setText(config.masqueSni ?: "")
        b.etMasqueH2Peer.setText(config.masqueH2Peer ?: "")
        b.etKeepalive.setText(config.keepaliveSecs.toString())
        b.spTorMode.setSelection(config.torMode.ordinal)
        b.spTorBridges.setSelection(config.torBridges.ordinal)
        b.etTorCustomBridges.setText(config.torCustomBridges ?: "")
        b.etTorCountry.setText(config.torCountry ?: "")
        b.spPsiphonMode.setSelection(config.psiphonMode.ordinal)
        b.etPsiphonRegion.setText(config.psiphonRegion ?: "")
        b.etPsiphonCdnSni.setText(config.psiphonCdnSni ?: "")
        b.etPsiphonBin.setText(config.psiphonBinPath ?: "")
        b.etPsiphonConfig.setText(config.psiphonConfigPath ?: "")
        b.etPsiphonEntries.setText(config.psiphonServerEntriesPath ?: "")
        b.etZtTeam.setText(config.zeroTrustTeam ?: "")
        b.etZtEmail.setText(config.zeroTrustEmail ?: "")
        b.cbZtGateway.isChecked = config.zeroTrustGateway
        b.etRouteBlock.setText(config.routeBlock ?: "")
        b.etRouteDirect.setText(config.routeDirect ?: "")
        b.etInTunnelDns.setText(config.inTunnelDns ?: "")
        b.etUpstream.setText(config.upstreamProxy ?: "")
        b.etExitLoc.setText(config.exitLocation ?: "")
        b.etHttpProxyPort.setText(config.httpProxyPort?.toString() ?: "")
        b.cbKillswitch.isChecked = config.killSwitchFailClosed
        b.etExtraCli.setText(config.extraCliArgs ?: "")
        b.etExtraEnv.setText(config.extraEnv ?: "")
        b.tvScanResult.text =
            config.lastEndpoint?.let { getString(R.string.aether_last_endpoint, it) } ?: ""
    }

    /** Reads every control back into [config] and persists it. */
    private fun readConfigFromUi(): AetherConfig {
        config.transport =
            AetherConfig.Transport.values().getOrElse(b.spTransport.selectedItemPosition) {
                AetherConfig.Transport.MASQUE
            }
        config.peer = textOf(b.etPeer)
        config.outerPeer = textOf(b.etOuterPeer)
        config.innerPeer = textOf(b.etInnerPeer)
        config.goolPeer = textOf(b.etGoolPeer)
        config.scanMode =
            AetherConfig.ScanMode.values().getOrElse(b.spScanMode.selectedItemPosition) {
                AetherConfig.ScanMode.BALANCED
            }
        config.scanIp =
            AetherConfig.ScanIp.values().getOrElse(b.spScanIp.selectedItemPosition) {
                AetherConfig.ScanIp.V4
            }
        config.scanPorts = textOf(b.etScanPorts)
        config.excludedEndpoints = textOf(b.etExcluded)
        config.quickReconnect = b.cbQuickReconnect.isChecked
        config.noizeProfile =
            AetherConfig.NoizeProfile.values().getOrElse(b.spNoize.selectedItemPosition) {
                AetherConfig.NoizeProfile.FIREWALL
            }
        config.fragmentHello = b.cbFragment.isChecked
        config.fragmentSize = intOf(b.etFragmentSize)
        config.fragmentDelayMs = intOf(b.etFragmentDelay)
        config.ech = b.cbEch.isChecked
        config.echDomain = textOf(b.etEchDomain)
        config.masqueHttp2 = b.cbHttp2.isChecked
        config.quicV2 = b.cbQuicV2.isChecked
        config.masqueSni = textOf(b.etMasqueSni)
        config.masqueH2Peer = textOf(b.etMasqueH2Peer)
        config.keepaliveSecs = intOf(b.etKeepalive) ?: 5
        config.torMode =
            AetherConfig.TorMode.values().getOrElse(b.spTorMode.selectedItemPosition) {
                AetherConfig.TorMode.OFF
            }
        config.torBridges =
            AetherConfig.TorBridges.values().getOrElse(b.spTorBridges.selectedItemPosition) {
                AetherConfig.TorBridges.AUTO
            }
        config.torCustomBridges = textOf(b.etTorCustomBridges)
        config.torCountry = textOf(b.etTorCountry)
        config.psiphonMode =
            AetherConfig.PsiphonMode.values().getOrElse(b.spPsiphonMode.selectedItemPosition) {
                AetherConfig.PsiphonMode.OFF
            }
        config.psiphonRegion = textOf(b.etPsiphonRegion)
        config.psiphonCdnSni = textOf(b.etPsiphonCdnSni)
        config.psiphonBinPath = textOf(b.etPsiphonBin)
        config.psiphonConfigPath = textOf(b.etPsiphonConfig)
        config.psiphonServerEntriesPath = textOf(b.etPsiphonEntries)
        config.zeroTrustTeam = textOf(b.etZtTeam)
        config.zeroTrustEmail = textOf(b.etZtEmail)
        config.zeroTrustGateway = b.cbZtGateway.isChecked
        config.routeBlock = textOf(b.etRouteBlock)
        config.routeDirect = textOf(b.etRouteDirect)
        config.inTunnelDns = textOf(b.etInTunnelDns)
        config.upstreamProxy = textOf(b.etUpstream)
        config.exitLocation = textOf(b.etExitLoc)
        config.httpProxyPort = intOf(b.etHttpProxyPort)
        config.killSwitchFailClosed = b.cbKillswitch.isChecked
        config.extraCliArgs = textOf(b.etExtraCli)
        config.extraEnv = textOf(b.etExtraEnv)
        config.save(this)
        return config
    }

    // -- wiring -----------------------------------------------------------------

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        // Called from IO coroutines: post view work to the main thread.
        b.root.post {
            b.btnToggle.isEnabled = !value && AetherCore.isSupported()
            b.btnScan.isEnabled = !value
            b.btnVerify.isEnabled = !value
            b.btnCleanIp.isEnabled = !value
        }
    }

    private fun refreshToggle() {
        val running = AetherEngine.isRunning() || config.tunnelEnabled
        b.btnToggle.setText(if (running) R.string.aether_disable else R.string.aether_enable)
    }

    private fun observeEngine() {
        lifecycleScope.launch {
            AetherEngine.state.collect { state ->
                when (state) {
                    is AetherState.Idle -> {
                        b.aetherStatus.setText(R.string.aether_status_idle)
                        refreshToggle()
                    }
                    is AetherState.Scanning,
                    is AetherState.OpeningIdentity,
                    is AetherState.Verifying,
                    is AetherState.Connecting -> {
                        b.aetherStatus.setText(R.string.aether_enabling)
                    }
                    is AetherState.Connected -> {
                        b.aetherStatus.setText(R.string.aether_status_running)
                        b.aetherEndpoint.text =
                            getString(R.string.aether_endpoint, state.endpoint) +
                                "\n" + getString(R.string.aether_transport, state.transport) +
                                "\n" + getString(
                                    if (state.cliMode) R.string.aether_mode_cli
                                    else R.string.aether_mode_managed
                                )
                        refreshToggle()
                    }
                    is AetherState.Stopped -> {
                        b.aetherStatus.setText(R.string.aether_status_idle)
                        refreshToggle()
                    }
                    is AetherState.Error -> {
                        b.aetherStatus.text =
                            getString(R.string.aether_status_error, state.message)
                        refreshToggle()
                    }
                }
            }
        }
    }

    private fun wireButtons() {
        b.btnToggle.setOnClickListener {
            if (busy) return@setOnClickListener
            readConfigFromUi()
            lifecycleScope.launch(Dispatchers.IO) {
                setBusy(true)
                try {
                    if (AetherEngine.isRunning() || config.tunnelEnabled) {
                        withContext(Dispatchers.Main) {
                            toast(getString(R.string.aether_disabling))
                        }
                        AetherController.disableTunnel(this@AetherActivity)
                        withContext(Dispatchers.Main) {
                            toast(getString(R.string.aether_disabled))
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            toast(getString(R.string.aether_enabling))
                        }
                        val err =
                            AetherController.enableTunnel(this@AetherActivity)
                        withContext(Dispatchers.Main) {
                            if (err == null) toast(getString(R.string.aether_enabled))
                            else toast(err)
                        }
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        config = AetherConfig.load(this@AetherActivity)
                        setBusy(false)
                        refreshToggle()
                    }
                }
            }
        }

        b.btnScan.setOnClickListener {
            if (busy) return@setOnClickListener
            readConfigFromUi()
            lifecycleScope.launch(Dispatchers.IO) {
                setBusy(true)
                withContext(Dispatchers.Main) {
                    b.tvScanResult.setText(R.string.aether_scan_running)
                }
                try {
                    val peer = AetherController.scanNow(this@AetherActivity)
                    withContext(Dispatchers.Main) {
                        if (peer != null) {
                            b.tvScanResult.text =
                                getString(R.string.aether_scan_found, peer)
                        } else {
                            b.tvScanResult.text =
                                getString(
                                    R.string.aether_scan_failed,
                                    AetherEngine.lastError() ?: "?"
                                )
                        }
                    }
                } finally {
                    withContext(Dispatchers.Main) { setBusy(false) }
                }
            }
        }

        b.btnVerify.setOnClickListener {
            if (busy) return@setOnClickListener
            readConfigFromUi()
            lifecycleScope.launch(Dispatchers.IO) {
                setBusy(true)
                try {
                    val peer =
                        config.peer?.takeIf { it.isNotBlank() }
                            ?: config.lastEndpoint
                    if (peer.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            toast(getString(R.string.aether_scan_failed, "no endpoint yet"))
                        }
                        return@launch
                    }
                    val ok = AetherController.verifyNow(this@AetherActivity, peer)
                    withContext(Dispatchers.Main) {
                        b.tvScanResult.text =
                            getString(
                                if (ok) R.string.aether_verify_ok
                                else R.string.aether_verify_fail,
                                peer
                            )
                    }
                } finally {
                    withContext(Dispatchers.Main) { setBusy(false) }
                }
            }
        }

        b.btnCleanIp.setOnClickListener {
            if (busy) return@setOnClickListener
            readConfigFromUi()
            lifecycleScope.launch(Dispatchers.IO) {
                setBusy(true)
                withContext(Dispatchers.Main) {
                    b.tvScanResult.setText(R.string.aether_scan_running)
                }
                try {
                    val (peer, reachable) =
                        AetherController.cleanIpScan(this@AetherActivity)
                    withContext(Dispatchers.Main) {
                        b.tvScanResult.text =
                            when {
                                peer == null ->
                                    getString(
                                        R.string.aether_scan_failed,
                                        AetherEngine.lastError() ?: "?"
                                    )
                                reachable ->
                                    getString(R.string.aether_verify_ok, peer)
                                else ->
                                    getString(R.string.aether_verify_fail, peer)
                            }
                    }
                } finally {
                    withContext(Dispatchers.Main) { setBusy(false) }
                }
            }
        }

        b.btnZtRequestCode.setOnClickListener {
            readConfigFromUi()
            val team = config.zeroTrustTeam
            val email = config.zeroTrustEmail
            if (team.isNullOrBlank() || email.isNullOrBlank()) {
                toast(getString(R.string.aether_zt_need_team_email))
                return@setOnClickListener
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val session =
                    AetherEngine.teamCodeRequest(this@AetherActivity, config, team, email)
                withContext(Dispatchers.Main) {
                    if (session != null) {
                        teamSession = session
                        b.tvZtStatus.text =
                            getString(R.string.aether_zt_code_sent, email)
                    } else {
                        b.tvZtStatus.text =
                            getString(
                                R.string.aether_zt_failed,
                                AetherEngine.lastError() ?: "?"
                            )
                    }
                }
            }
        }

        b.btnZtSubmitCode.setOnClickListener {
            val session = teamSession
            val code = b.etZtCode.text?.toString()?.trim()
            if (session == null || code.isNullOrBlank()) {
                toast(getString(R.string.aether_zt_need_team_email))
                return@setOnClickListener
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = AetherEngine.teamCodeSubmit(session, code)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        b.tvZtStatus.setText(R.string.aether_zt_signed_in)
                        teamSession = null
                    } else {
                        b.tvZtStatus.text =
                            getString(
                                R.string.aether_zt_failed,
                                AetherEngine.lastError() ?: "?"
                            )
                    }
                }
            }
        }

        b.btnZtResend.setOnClickListener {
            val session = teamSession ?: return@setOnClickListener
            lifecycleScope.launch(Dispatchers.IO) {
                AetherEngine.teamCodeResend(session)
            }
        }

        b.btnZtSignOut.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                teamSession?.let { AetherEngine.teamSessionFree(it) }
                teamSession = null
                AetherEngine.teamTokenClear()
                withContext(Dispatchers.Main) {
                    b.tvZtStatus.text = ""
                }
            }
        }

        b.btnOpenProxySettings.setOnClickListener {
            readConfigFromUi()
            toast(getString(R.string.aether_saved))
            startActivity(Intent(this, ProxySettingsActivity::class.java))
        }
    }

    override fun onPause() {
        // Persist whatever is on screen even if the user leaves without
        // pressing any button — the toggle/scan actions re-read anyway.
        readConfigFromUi()
        super.onPause()
    }
}
