/*
 * Copyright 2023 RethinkDNS and its authors
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
 */
package com.celzero.bravedns.ui.activity

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityWgConfigEditorBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.activity.WgConfigDetailActivity.Companion.INTENT_EXTRA_WG_TYPE
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_PROXY
import com.celzero.bravedns.util.Logger.throwableToException
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils.clipboardCopy
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.util.SnackbarHelper.applySystemBarEdgeInsets
import com.celzero.bravedns.wireguard.AmneziaPresets
import com.celzero.bravedns.wireguard.AmneziaValues
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.WgInterface
import com.celzero.bravedns.wireguard.util.ErrorMessages
import com.celzero.firestack.backend.Backend
import dev.androidbroadcast.vbpd.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class WgConfigEditorActivity : BaseActivity(R.layout.activity_wg_config_editor) {
    private val b by viewBinding(ActivityWgConfigEditorBinding::bind)
    private val persistentState by inject<PersistentState>()

    private var wgConfig: Config? = null
    private var wgInterface: WgInterface? = null
    private var configId: Int = -1
    private var wgType: WgConfigDetailActivity.WgType = WgConfigDetailActivity.WgType.DEFAULT
    private var isRestoredFromState = false
    private var showAmneziaSection = false

    companion object {
        const val INTENT_EXTRA_WG_ID = "WIREGUARD_TUNNEL_ID"
        private const val CLIPBOARD_PUBLIC_KEY_LBL = "Public Key"
        private const val DEFAULT_MTU = "-1"
        private const val DEFAULT_LISTEN_PORT = "0"
        private const val KEY_SHOW_AMNEZIA_SECTION = "show_amnezia_section"
        // PopupMenu item ids for the Amnezia overflow menu.
        private const val MENU_AMNEZIA_TOGGLE = 1
        private const val MENU_AMNEZIA_COMPAT = 2
        private const val MENU_MIMIC_QUIC = 3
        private const val MENU_MIMIC_DNS = 4
        private const val MENU_MIMIC_SIP = 5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        b.root.applySystemBarEdgeInsets()

        handleFrostEffectIfNeeded(persistentState.theme)

        isRestoredFromState = savedInstanceState != null
        showAmneziaSection =
            savedInstanceState?.getBoolean(KEY_SHOW_AMNEZIA_SECTION) ?: false

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        configId = intent.getIntExtra(INTENT_EXTRA_WG_ID, WireguardManager.INVALID_CONF_ID)
        wgType =
            WgConfigDetailActivity.WgType.fromInt(
                intent.getIntExtra(
                    INTENT_EXTRA_WG_TYPE,
                    WgConfigDetailActivity.WgType.DEFAULT.value
                )
            )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SHOW_AMNEZIA_SECTION, showAmneziaSection)
    }

    override fun onResume() {
        super.onResume()
        init()
        setupClickListeners()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun init() {
        io {
            wgConfig = WireguardManager.getConfigById(configId)
            wgInterface = wgConfig?.getInterface()

            uiCtx {
                if (!isRestoredFromState) {
                    b.interfaceNameText.setText(wgConfig?.getName())

                    b.privateKeyText.setText(wgInterface?.getKeyPair()?.getPrivateKey()?.base64())
                    b.publicKeyText.setText(wgInterface?.getKeyPair()?.getPublicKey()?.base64())
                    var dns = wgInterface?.dnsServers?.joinToString { it.hostAddress ?: "" }
                    val searchDomains = wgInterface?.dnsSearchDomains?.joinToString { it }
                    dns =
                        if (!searchDomains.isNullOrEmpty()) {
                            "$dns,$searchDomains"
                        } else {
                            dns
                        }
                    b.dnsServersText.setText(dns)
                    if (wgInterface?.getAddresses()?.isEmpty() != true) {
                        b.addressesLabelText.setText(
                            wgInterface?.getAddresses()?.joinToString { it.toString() }
                        )
                    }
                    if (showListenPort()) {
                        b.listenPortText.setText(wgInterface?.listenPort?.get().toString())
                    }
                    if (wgInterface?.mtu?.isPresent == true) {
                        b.mtuText.setText(wgInterface?.mtu?.get().toString())
                    }
                    // Show the Amnezia section right away for configs that already
                    // carry Amnezia properties (e.g. imported Amnezia configs).
                    showAmneziaSection = wgInterface?.isAmnezia() == true
                    populateAmneziaFields()
                }
                // Always restore the section visibility (not automatically saved by
                // View for non-EditText views) and the summary line.
                setAmneziaSectionVisible(showAmneziaSection)
            }
        }
    }

    private fun showListenPort(): Boolean {
        val isPresent = wgInterface?.listenPort?.isPresent == true && wgInterface?.listenPort?.get() != 1
        val byType = wgType.isOneWg() || (!persistentState.randomizeListenPort && wgType.isDefault())
        return isPresent && byType
    }

    private fun setupClickListeners() {
        b.amzMenuBtn.setOnClickListener { showAmneziaMenu() }

        b.privateKeyTextLayout.setEndIconOnClickListener {
            val key = Backend.newWgPrivateKey()
            val privateKey = key.base64()
            val publicKey = key.mult().base64()
            b.privateKeyText.setText(privateKey.toString())
            b.publicKeyText.setText(publicKey.toString())
        }

        b.saveTunnel.setOnClickListener {
            val name = b.interfaceNameText.text.toString()
            val addresses = b.addressesLabelText.text.toString()
            val mtu = b.mtuText.text.toString().ifEmpty { DEFAULT_MTU }
            val listenPort = b.listenPortText.text.toString().ifEmpty { DEFAULT_LISTEN_PORT }
            val dnsServers = b.dnsServersText.text.toString().ifEmpty { WgInterface.DEFAULT_DNS }
            val privateKey = b.privateKeyText.text.toString()
            val amnezia = readAmneziaValues()
            io {
                val isInterfaceAdded =
                    addWgInterface(name, addresses, mtu, listenPort, dnsServers, privateKey, amnezia)
                if (isInterfaceAdded != null) {
                    uiCtx {
                        Utilities.showToastUiCentered(
                            this,
                            getString(R.string.config_add_success_toast),
                            Toast.LENGTH_LONG
                        )
                        finish()
                    }
                } else {
                    // no-op, addWgInterface() will show the error message
                }
            }
        }

        b.dismissBtn.setOnClickListener { finish() }

        b.publicKeyLabelLayout.setOnClickListener {
            val copied = clipboardCopy(this, b.publicKeyText.text.toString(), CLIPBOARD_PUBLIC_KEY_LBL)
            Utilities.showToastUiCentered(
                this,
                getString(if (copied) R.string.public_key_copy_toast_msg else R.string.copy_failed_toast),
                Toast.LENGTH_SHORT
            )
        }

        b.publicKeyText.setOnClickListener {
            val copied = clipboardCopy(this, b.publicKeyText.text.toString(), CLIPBOARD_PUBLIC_KEY_LBL)
            Utilities.showToastUiCentered(
                this,
                getString(if (copied) R.string.public_key_copy_toast_msg else R.string.copy_failed_toast),
                Toast.LENGTH_SHORT
            )
        }
    }

    /**
     * Overflow menu for the Amnezia one-tap presets, mirroring WG Tunnel's
     * InterfaceDropdown + AmneziaToolMenuItems: show/hide the Amnezia fields
     * section, enable/remove Amnezia compatibility, and the QUIC/DNS/SIP
     * mimic presets. The menu is rebuilt on every tap so the toggle titles
     * always reflect the current state.
     */
    private fun showAmneziaMenu() {
        val popup = PopupMenu(this, b.amzMenuBtn)
        val values = readAmneziaValues()
        popup.menu.add(
            Menu.NONE,
            MENU_AMNEZIA_TOGGLE,
            Menu.NONE,
            getString(
                if (showAmneziaSection) R.string.hide_amnezia_properties
                else R.string.show_amnezia_properties
            )
        )
        popup.menu.add(
            Menu.NONE,
            MENU_AMNEZIA_COMPAT,
            Menu.NONE,
            getString(
                if (AmneziaPresets.isCompatibilitySet(values)) R.string.remove_amnezia_compatibility
                else R.string.enable_amnezia_compatibility
            )
        )
        popup.menu.add(Menu.NONE, MENU_MIMIC_QUIC, Menu.NONE, getString(R.string.mimic_quic))
        popup.menu.add(Menu.NONE, MENU_MIMIC_DNS, Menu.NONE, getString(R.string.mimic_dns))
        popup.menu.add(Menu.NONE, MENU_MIMIC_SIP, Menu.NONE, getString(R.string.mimic_sip))
        popup.setOnMenuItemClickListener { item ->
            val current = readAmneziaValues()
            when (item.itemId) {
                MENU_AMNEZIA_TOGGLE -> setAmneziaSectionVisible(!showAmneziaSection)
                MENU_AMNEZIA_COMPAT -> {
                    if (AmneziaPresets.isCompatibilitySet(current)) {
                        // Mirror WG Tunnel's resetAmneziaProperties(): clear everything.
                        applyAmneziaValues(current.cleared(), false)
                    } else {
                        // Mirror WG Tunnel's applyAmneziaDefaults(): fill base params.
                        applyAmneziaValues(current.withDefaults(), true)
                    }
                }
                MENU_MIMIC_QUIC -> applyAmneziaValues(current.withQuicMimic(), true)
                MENU_MIMIC_DNS -> applyAmneziaValues(current.withDnsMimic(), true)
                MENU_MIMIC_SIP -> applyAmneziaValues(current.withSipMimic(), true)
            }
            true
        }
        popup.show()
    }

    private fun readAmneziaValues(): AmneziaValues {
        return AmneziaValues(
            jc = b.amzJcText.text.toString().trim(),
            jmin = b.amzJminText.text.toString().trim(),
            jmax = b.amzJmaxText.text.toString().trim(),
            s1 = b.amzS1Text.text.toString().trim(),
            s2 = b.amzS2Text.text.toString().trim(),
            s3 = b.amzS3Text.text.toString().trim(),
            s4 = b.amzS4Text.text.toString().trim(),
            h1 = b.amzH1Text.text.toString().trim(),
            h2 = b.amzH2Text.text.toString().trim(),
            h3 = b.amzH3Text.text.toString().trim(),
            h4 = b.amzH4Text.text.toString().trim(),
            i1 = b.amzI1Text.text.toString().trim(),
            i2 = b.amzI2Text.text.toString().trim(),
            i3 = b.amzI3Text.text.toString().trim(),
            i4 = b.amzI4Text.text.toString().trim(),
            i5 = b.amzI5Text.text.toString().trim(),
        )
    }

    private fun applyAmneziaValues(values: AmneziaValues, showSection: Boolean) {
        b.amzJcText.setText(values.jc)
        b.amzJminText.setText(values.jmin)
        b.amzJmaxText.setText(values.jmax)
        b.amzS1Text.setText(values.s1)
        b.amzS2Text.setText(values.s2)
        b.amzS3Text.setText(values.s3)
        b.amzS4Text.setText(values.s4)
        b.amzH1Text.setText(values.h1)
        b.amzH2Text.setText(values.h2)
        b.amzH3Text.setText(values.h3)
        b.amzH4Text.setText(values.h4)
        b.amzI1Text.setText(values.i1)
        b.amzI2Text.setText(values.i2)
        b.amzI3Text.setText(values.i3)
        b.amzI4Text.setText(values.i4)
        b.amzI5Text.setText(values.i5)
        setAmneziaSectionVisible(showSection)
    }

    private fun populateAmneziaFields() {
        val values = wgInterface?.let { AmneziaValues.fromInterface(it) } ?: AmneziaValues()
        applyAmneziaValues(values, showAmneziaSection)
    }

    private fun setAmneziaSectionVisible(visible: Boolean) {
        showAmneziaSection = visible
        b.amzSection.visibility = if (visible) View.VISIBLE else View.GONE
        updateAmzSummary()
    }

    private fun updateAmzSummary() {
        val values = readAmneziaValues()
        if (showAmneziaSection || !values.isAnySet()) {
            b.amzProps.visibility = View.GONE
        } else {
            b.amzProps.visibility = View.VISIBLE
            b.amzProps.text = values.toSummaryString()
        }
    }

    private suspend fun addWgInterface(
        name: String,
        addresses: String,
        mtu: String,
        listenPort: String,
        dnsServers: String,
        privateKey: String,
        amnezia: AmneziaValues
    ): Config? {
        try {
            // parse the wg interface to check for errors
            val builder =
                WgInterface.Builder()
                    .parsePrivateKey(privateKey)
                    .parseAddresses(addresses)
                    .parseListenPort(listenPort)
                    .parseDnsServers(dnsServers)
                    .parseMtu(mtu)
            // Carry the Amnezia properties through: without these, saving
            // would silently drop them (the interface is rebuilt on save).
            // Blank means "not set"; a malformed number throws
            // BadConfigException and is reported like any other parse error.
            if (amnezia.jc.isNotBlank()) builder.parseJc(amnezia.jc)
            if (amnezia.jmin.isNotBlank()) builder.parseJmin(amnezia.jmin)
            if (amnezia.jmax.isNotBlank()) builder.parseJmax(amnezia.jmax)
            if (amnezia.s1.isNotBlank()) builder.parseS1(amnezia.s1)
            if (amnezia.s2.isNotBlank()) builder.parseS2(amnezia.s2)
            if (amnezia.s3.isNotBlank()) builder.parseS3(amnezia.s3)
            if (amnezia.s4.isNotBlank()) builder.parseS4(amnezia.s4)
            if (amnezia.h1.isNotBlank()) builder.parseH1(amnezia.h1)
            if (amnezia.h2.isNotBlank()) builder.parseH2(amnezia.h2)
            if (amnezia.h3.isNotBlank()) builder.parseH3(amnezia.h3)
            if (amnezia.h4.isNotBlank()) builder.parseH4(amnezia.h4)
            if (amnezia.i1.isNotBlank()) builder.setI1(amnezia.i1)
            if (amnezia.i2.isNotBlank()) builder.setI2(amnezia.i2)
            if (amnezia.i3.isNotBlank()) builder.setI3(amnezia.i3)
            if (amnezia.i4.isNotBlank()) builder.setI4(amnezia.i4)
            if (amnezia.i5.isNotBlank()) builder.setI5(amnezia.i5)
            val wgInterface = builder.build()
            wgConfig = WireguardManager.addOrUpdateInterface(configId, name, wgInterface)
            return wgConfig
        } catch (e: Throwable) {
            val error = ErrorMessages[this, e]
            val ex = throwableToException(e)
            Logger.e(LOG_TAG_PROXY, "err while parsing wg interface: $error", ex)
            uiCtx { Utilities.showToastUiCentered(this, error, Toast.LENGTH_LONG) }
            return null
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (!isFinishing && !isDestroyed) {
                f()
            }
        }
    }
}
