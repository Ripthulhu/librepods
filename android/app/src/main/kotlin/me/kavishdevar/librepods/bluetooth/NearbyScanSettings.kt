/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.bluetooth

import android.bluetooth.le.ScanSettings
import android.os.Build

internal fun nearbyScanSettings(interactive: Boolean, preferredMode: Int): ScanSettings =
    ScanSettings.Builder()
        .setScanMode(if (interactive) ScanSettings.SCAN_MODE_LOW_LATENCY else preferredMode)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
        .setReportDelay(0)
        .apply {
            // Passive reports arrive without waiting for a scan response, as on NTPods.
            if (Build.VERSION.SDK_INT >= 37) setScanType(ScanSettings.SCAN_TYPE_PASSIVE)
        }
        .build()
