/* Copyright (C) 2026 LibrePods contributors; GPL-3.0-or-later. */
package me.kavishdevar.librepods.core.devices

/** Public firmware selection follows NTPods; internal build strings are not public majors. */
object AirPodsFirmware {
    fun publicVersion(version1: String, version3: String): String? =
        humanVersion(version3) ?: humanVersion(version1) ?: packedRelease(version3)

    fun major(version: String): Int? = (humanVersion(version) ?: packedRelease(version))
        ?.takeWhile { it in '0'..'9' }?.toIntOrNull()

    private fun humanVersion(version: String): String? {
        val value = version.trim()
        Regex("""^([0-9]{1,2})[A-Z][0-9]+[a-z]?$""").matchEntire(value)?.let {
            return value.takeIf { _ -> it.groupValues[1].toInt() in 1..99 }
        }
        val parts = value.split('.')
        return value.takeIf { parts.size == 3 && parts.all { part ->
            part.isNotEmpty() && part.all { it in '0'..'9' } && (part.toIntOrNull() ?: -1) in 0..255
        } && (parts[0].toIntOrNull() ?: -1) in 1..99 }
    }

    private fun packedRelease(value: String): String? {
        if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
        val packed = value.toLongOrNull()?.takeIf { it in 0..0xffff_ffffL } ?: return null
        val major = packed ushr 20
        if (major !in 1..99 || packed and 0xf != 0L) return null
        // Observed release reports: 6357536=6B34, 6619472=6F21, 9442752=9A348.
        val branch = 'A' + ((packed ushr 16) and 0xf).toInt()
        return "$major$branch${(packed ushr 4) and 0xfff}"
    }
}
