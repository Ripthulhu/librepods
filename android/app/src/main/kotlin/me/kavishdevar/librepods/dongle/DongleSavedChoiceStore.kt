package me.kavishdevar.librepods.dongle

import android.content.SharedPreferences
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.ALLOW_OFF_OPTION
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Control.StatusField

/**
 * Setting choices remembered per AirPods pair, so they can be shown and written before the
 * AirPods report them. Keys are `choice:<peer>:<setting id>` with the peer address as lowercase hex.
 */
internal class DongleSavedChoiceStore(private val preferences: SharedPreferences) {
    /** The pair the dongle has selected, or null while no real address is known. */
    fun peer(status: DongleProtocol.Status?): String? {
        val address = status?.address ?: return null
        val wellFormed = address.size == StatusField.SELECTED_ADDRESS_SIZE && address.all { value -> value in 0..255 }
        val assigned = address.any { value -> value != 0 } && address.any { value -> value != 255 }
        if (!wellFormed || !assigned) return null
        return address.joinToString("") { it.toString(16).padStart(2, '0') }
    }

    /** Every valid saved choice for the selected pair. */
    fun load(status: DongleProtocol.Status?): Map<Int, Long> {
        val peer = peer(status) ?: return emptyMap()
        return dongleSavedChoiceIds.mapNotNull { id ->
            val key = key(peer, id)
            if (!preferences.contains(key)) return@mapNotNull null
            runCatching { preferences.getLong(key, -1) }.getOrNull()
                ?.takeIf { validLiveSetting(id, it) }
                ?.let { id to it }
        }.toMap()
    }

    /** Whether a write to [id] is remembered; Allow Off is only remembered for AirPods Pro 3. */
    fun persists(id: Int, model: AirPodsModel?): Boolean =
        id in dongleSavedChoiceIds && (id != ALLOW_OFF_OPTION.settingId || model == AirPodsModel.AIRPODS_PRO_3)

    /** Saves synchronously; false when the preference could not be written. */
    fun save(peer: String, id: Int, value: Long): Boolean =
        runCatching { preferences.edit().putLong(key(peer, id), value).commit() }.getOrDefault(false)

    private fun key(peer: String, id: Int) = "choice:$peer:$id"
}
