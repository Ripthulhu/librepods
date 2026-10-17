/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import me.kavishdevar.librepods.core.bluetooth.ble.ProximityReport
import me.kavishdevar.librepods.core.devices.AirPodsSpecs

internal object OverlayArtwork {
    fun pairing(modelId: Int): Int {
        val spec = AirPodsSpecs.getSpec(ProximityReport.model(modelId))
        return spec.caseImageRes ?: spec.primaryImageRes
    }
}
