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
 */
package com.celzero.bravedns.wireguard

/**
 * One-tap AmneziaWG presets for the WireGuard tunnel editor, ported from
 * WG Tunnel (MIT, com.zaneschepke.wireguardautotunnel).
 *
 * Base parameters are WG Tunnel's Amnezia 2.0 compatibility defaults
 * (AmneziaConfigNormalizer.compatibilityDefaults and
 * EditableInterface.applyAmneziaDefaults): Jc=4, Jmin=40, Jmax=70,
 * S1=S2=S3=S4=0, H1=1, H2=2, H3=3, H4=4.
 *
 * Mimic presets are WG Tunnel's EditableInterface.setQuicMimic /
 * setDnsMimic / setSipMimic junk-packet (I1/I2) values, copied verbatim.
 * They are only meaningful together with the base parameters, so every
 * mimic preset also fills in any blank base parameter (mirroring WG
 * Tunnel's ensureAmneziaBaseParameters()).
 */
object AmneziaPresets {
    // Amnezia 2.0 base parameters.
    const val JC = 4
    const val JMIN = 40
    const val JMAX = 70
    const val S1 = 0
    const val S2 = 0
    const val S3 = 0
    const val S4 = 0
    const val H1 = 1L
    const val H2 = 2L
    const val H3 = 3L
    const val H4 = 4L

    // Mimic QUIC (HTTP/3): I1 is a QUIC initial packet, I2 a follow-up frame.
    const val QUIC_I1 = "<b 0xc1ff000012508394c8f03e51570800449f0dbc195a0000f3a694c75775b4e546172ce9e047cd0b5bee5181648c727adc87f7eae54473ec6cba6bdad4f59823174b769f12358abd292d4f3286934484fb8b239c38732e1f3bbbc6a003056487eb8b5c88b9fd9279ffff3b0f4ecf95c4624db6d65d4113329ee9b0bf8cdd7c8a8d72806d55df25ecb66488bc119d7c9a29abaf99bb33c56b08ad8c26995f838bb3b7a3d5c1858b8ec06b839db2dcf918d5ea9317f1acd6b663cc8925868e2f6a1bda546695f3c3f33175944db4a11a346afb07e78489e509b02add51b7b203eda5c330b03641179a31fbba9b56ce00f3d5b5e3d7d9c5429aebb9576f2f7eacbe27bc1b8082aaf68fb69c921aa5d33ec0c8510410865a178d86d7e54122d55ef2c2bbc040be46d7fece73fe8a1b24495ec160df2da9b20a7ba2f26dfa2a44366dbc63de5cd7d7c94c57172fe6d79c901f025c0010b02c89b395402c009f62dc053b8067a1e0ed0a1e0cf5087d7f78cbd94afe0c3dd55d2d4b1a5cfe2b68b86264e351d1dcd858783a240f893f008ceed743d969b8f735a1677ead960b1fb1ecc5ac83c273b49288d02d7286207e663c45e1a7baf50640c91e762941cf380ce8d79f3e86767fbbcd25b42ef70ec334835a3a6d792e170a432ce0cb7bde9aaa1e75637c1c34ae5fef4338f53db8b13a4d2df594efbfa08784543815c9c0d487bddfa1539bc252cf43ec3686e9802d651cfd2a829a06a9f332a733a4a8aed80efe3478093fbc69c8608146b3f16f1a5c4eac9320da49f1afa5f538ddecbbe7888f435512d0dd74fd9b8c99e3145ba84410d8ca9a36dd884109e76e5fb8222a52e1473da168519ce7a8a3c32e9149671b16724c6c5c51bb5cd64fb591e567fb78b10f9f6fee62c276f282a7df6bcf7c17747bc9a81e6c9c3b032fdd0e1c3ac9eaa5077de3ded18b2ed4faf328f49875af2e36ad5ce5f6cc99ef4b60e57b3b5b9c9fcbcd4cfb3975e70ce4c2506bcd71fef0e53592461504e3d42c885caab21b782e26294c6a9d61118cc40a26f378441ceb48f31a362bf8502a723a36c63502229a462cc2a3796279a5e3a7f81a68c7f81312c381cc16a4ab03513a51ad5b54306ec1d78a5e47e2b15e5b7a1438e5b8b2882dbdad13d6a4a8c3558cae043501b68eb3b040067152>"
    const val QUIC_I2 = "<b 0x0000000000010000000000000000000000000000000000000000000000000000>"

    // Mimic DNS: I1 is a single DNS query packet (example.com, type A).
    const val DNS_I1 = "<b 0x123401000001000000000000076578616d706c6503636f6d0000010001>"

    // Mimic SIP (VoIP): I1 is a SIP INVITE, I2 a 100 Trying response.
    const val SIP_I1 = "<b 0x494e56495445207369703a626f624062696c6f78692e636f6d205349502f322e300d0a5669613a205349502f322e302f55445020706333332e61746c616e74612e636f6d3b6272616e63683d7a39684734624b3737366173646864730d0a4d61782d466f7277617264733a2037300d0a546f3a20426f62203c7369703a626f624062696c6f78692e636f6d3e0d0a46726f6d3a20416c696365203c7369703a616c6963654061746c616e74612e636f6d3e3b7461673d313932383330313737340d0a43616c6c2d49443a20613834623463373665363637313040706333332e61746c616e74612e636f6d0d0a435365713a2033313431353920494e564954450d0a436f6e746163743a203c7369703a616c69636540706333332e61746c616e74612e636f6d3e0d0a436f6e74656e742d547970653a206170706c69636174696f6e2f7364700d0a436f6e74656e742d4c656e6774683a20300d0a0d0a>"
    const val SIP_I2 = "<b 0x5349502f322e302031303020547279696e670d0a5669613a205349502f322e302f55445020706333332e61746c616e74612e636f6d3b6272616e63683d7a39684734624b3737366173646864730d0a546f3a20426f62203c7369703a626f624062696c6f78692e636f6d3e0d0a46726f6d3a20416c696365203c7369703a616c6963654061746c616e74612e636f6d3e3b7461673d313932383330313737340d0a43616c6c2d49443a20613834623463373665363637313040706333332e61746c616e74612e636f6d0d0a435365713a2033313431353920494e564954450d0a436f6e74656e742d4c656e6774683a20300d0a0d0a>"

    /**
     * True when the base parameters match the Amnezia compatibility defaults,
     * mirroring WG Tunnel's EditableInterface.isAmneziaCompatibilityModeSet().
     */
    fun isCompatibilitySet(values: AmneziaValues): Boolean {
        return (values.s1.toIntOrNull() ?: 0) == 0 &&
            (values.s2.toIntOrNull() ?: 0) == 0 &&
            values.h1.toLongOrNull() == 1L &&
            values.h2.toLongOrNull() == 2L &&
            values.h3.toLongOrNull() == 3L &&
            values.h4.toLongOrNull() == 4L
    }
}

/**
 * The editable Amnezia fields of a WireGuard interface, as plain strings
 * exactly as shown in the tunnel editor. Blank means "not set".
 */
data class AmneziaValues(
    val jc: String = "",
    val jmin: String = "",
    val jmax: String = "",
    val s1: String = "",
    val s2: String = "",
    val s3: String = "",
    val s4: String = "",
    val h1: String = "",
    val h2: String = "",
    val h3: String = "",
    val h4: String = "",
    val i1: String = "",
    val i2: String = "",
    val i3: String = "",
    val i4: String = "",
    val i5: String = "",
) {
    fun isAnySet(): Boolean {
        return listOf(
                jc, jmin, jmax, s1, s2, s3, s4, h1, h2, h3, h4, i1, i2, i3, i4, i5
            )
            .any { it.isNotBlank() }
    }

    /** Fill every base parameter with the Amnezia 2.0 defaults. */
    fun withDefaults(): AmneziaValues {
        return copy(
            jc = AmneziaPresets.JC.toString(),
            jmin = AmneziaPresets.JMIN.toString(),
            jmax = AmneziaPresets.JMAX.toString(),
            s1 = AmneziaPresets.S1.toString(),
            s2 = AmneziaPresets.S2.toString(),
            s3 = AmneziaPresets.S3.toString(),
            s4 = AmneziaPresets.S4.toString(),
            h1 = AmneziaPresets.H1.toString(),
            h2 = AmneziaPresets.H2.toString(),
            h3 = AmneziaPresets.H3.toString(),
            h4 = AmneziaPresets.H4.toString(),
        )
    }

    /** Fill in only the blank base parameters, leaving user values untouched. */
    fun withBaseParametersEnsured(): AmneziaValues {
        return copy(
            jc = jc.ifBlank { AmneziaPresets.JC.toString() },
            jmin = jmin.ifBlank { AmneziaPresets.JMIN.toString() },
            jmax = jmax.ifBlank { AmneziaPresets.JMAX.toString() },
            s1 = s1.ifBlank { AmneziaPresets.S1.toString() },
            s2 = s2.ifBlank { AmneziaPresets.S2.toString() },
            s3 = s3.ifBlank { AmneziaPresets.S3.toString() },
            s4 = s4.ifBlank { AmneziaPresets.S4.toString() },
            h1 = h1.ifBlank { AmneziaPresets.H1.toString() },
            h2 = h2.ifBlank { AmneziaPresets.H2.toString() },
            h3 = h3.ifBlank { AmneziaPresets.H3.toString() },
            h4 = h4.ifBlank { AmneziaPresets.H4.toString() },
        )
    }

    /** Clear every Amnezia field. */
    fun cleared(): AmneziaValues {
        return AmneziaValues()
    }

    fun withQuicMimic(): AmneziaValues {
        return withBaseParametersEnsured().copy(i1 = AmneziaPresets.QUIC_I1, i2 = AmneziaPresets.QUIC_I2)
    }

    fun withDnsMimic(): AmneziaValues {
        // DNS is typically single-packet, so no follow-up packet is set.
        return withBaseParametersEnsured().copy(i1 = AmneziaPresets.DNS_I1, i2 = "")
    }

    fun withSipMimic(): AmneziaValues {
        return withBaseParametersEnsured().copy(i1 = AmneziaPresets.SIP_I1, i2 = AmneziaPresets.SIP_I2)
    }

    /** One-line summary, like WgInterface.getAmzProps() but for editor values. */
    fun toSummaryString(): String {
        return "jc=${jc.ifBlank { "0" }}, jmin=${jmin.ifBlank { "0" }}, " +
            "jmax=${jmax.ifBlank { "0" }}, s1=${s1.ifBlank { "0" }}, " +
            "s2=${s2.ifBlank { "0" }}, s3=${s3.ifBlank { "0" }}, " +
            "s4=${s4.ifBlank { "0" }}, h1=${h1.ifBlank { "0" }}, " +
            "h2=${h2.ifBlank { "0" }}, h3=${h3.ifBlank { "0" }}, " +
            "h4=${h4.ifBlank { "0" }}" +
            if (i1.isNotBlank() || i2.isNotBlank() || i3.isNotBlank() ||
                i4.isNotBlank() || i5.isNotBlank()
            ) {
                ", i1=${i1.isNotBlank()}, i2=${i2.isNotBlank()}, " +
                    "i3=${i3.isNotBlank()}, i4=${i4.isNotBlank()}, i5=${i5.isNotBlank()}"
            } else {
                ""
            }
    }

    companion object {
        fun fromInterface(wgInterface: WgInterface): AmneziaValues {
            return AmneziaValues(
                jc = wgInterface.getJc().map { it.toString() }.orElse(""),
                jmin = wgInterface.getJmin().map { it.toString() }.orElse(""),
                jmax = wgInterface.getJmax().map { it.toString() }.orElse(""),
                s1 = wgInterface.getS1().map { it.toString() }.orElse(""),
                s2 = wgInterface.getS2().map { it.toString() }.orElse(""),
                s3 = wgInterface.getS3().map { it.toString() }.orElse(""),
                s4 = wgInterface.getS4().map { it.toString() }.orElse(""),
                h1 = wgInterface.getH1().map { it.toString() }.orElse(""),
                h2 = wgInterface.getH2().map { it.toString() }.orElse(""),
                h3 = wgInterface.getH3().map { it.toString() }.orElse(""),
                h4 = wgInterface.getH4().map { it.toString() }.orElse(""),
                i1 = wgInterface.getI1().orElse(""),
                i2 = wgInterface.getI2().orElse(""),
                i3 = wgInterface.getI3().orElse(""),
                i4 = wgInterface.getI4().orElse(""),
                i5 = wgInterface.getI5().orElse(""),
            )
        }
    }
}
