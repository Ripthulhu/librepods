/* SPDX-License-Identifier: GPL-3.0-or-later
 * Lid invitation validation follows ntpods battery_ble.rs (Ripthulhu/ntpods).
 */
package me.kavishdevar.librepods.core.bluetooth.ble

/** Public advertisements supply a model hint, never a Classic Bluetooth identity. */
object NearbyAirPods {
    data class Event(val modelId: Int, val lidOpen: Boolean)

    val modelNames = mapOf(
        0x0220 to "AirPods 1", 0x0f20 to "AirPods 2", 0x1320 to "AirPods 3",
        0x1920 to "AirPods 4", 0x1b20 to "AirPods 4 (ANC)",
        0x0e20 to "AirPods Pro", 0x1420 to "AirPods Pro 2",
        0x2420 to "AirPods Pro 2 (USB-C)", 0x2720 to "AirPods Pro 3",
        0x3020 to "AirPods 5 (Wireless Charging)", 0x3620 to "AirPods 5"
    )

    fun proximityRecord(manufacturer: ByteArray): ByteArray? {
        if (manufacturer.size > 254) return null
        var offset = 0
        var record: ByteArray? = null
        while (offset < manufacturer.size) {
            if (manufacturer.size - offset < 2) return null
            val length = (manufacturer[offset + 1].toInt() and 0xff) + 2
            if (length > manufacturer.size - offset) return null
            if (manufacturer[offset].toInt() == 7) {
                if (length != 27 || record != null) return null
                record = manufacturer.copyOfRange(offset, offset + length)
            }
            offset += length
        }
        return record
    }

    fun lidEvent(manufacturer: ByteArray): Event? {
        val data = proximityRecord(manufacturer) ?: return null
        val model = ((data[3].toInt() and 0xff) shl 8) or (data[4].toInt() and 0xff)
        if (data[2].toInt() != 1 || model !in modelNames || data[5].toInt() and 0x44 == 0) return null
        return Event(model, data[8].toInt() and 8 == 0)
    }

    fun matchesName(modelId: Int, name: String?): Boolean {
        val trimmed = name?.trim() ?: return false
        if (!Regex("\\bAirPods\\b", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) return false
        val pro = modelNames[modelId]?.contains("Pro") ?: return false
        val namedPro = Regex("\\bAirPods\\s+Pro\\b", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
        if ((namedPro && !pro) ||
            Regex("\\bAirPods\\s+(?:Pro\\s+)?Max\\b", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) return false
        // A custom name can omit the family, but an explicit generation still identifies it.
        val generation = Regex("\\bAirPods\\s+(?:Pro\\s+)?([1-5])\\b", RegexOption.IGNORE_CASE)
            .find(trimmed)?.groupValues?.get(1)?.toInt() ?: return true
        return namedPro == pro && generation == when (modelId) {
            0x0220, 0x0e20 -> 1
            0x0f20, 0x1420, 0x2420 -> 2
            0x1320, 0x2720 -> 3
            0x1920, 0x1b20 -> 4
            0x3020, 0x3620 -> 5
            else -> return false
        }
    }

    fun usesDoubleTap(modelId: Int): Boolean = modelId in setOf(0x1920, 0x1b20, 0x2720, 0x3020, 0x3620)

    class Discovery(private val modelId: Int) {
        private val addresses = linkedSetOf<String>()
        private var soleSince = 0L
        private var complete = false

        fun observe(address: String, name: String?, now: Long): Boolean {
            if (complete) return false
            if (name == null) return address in addresses
            if (!matchesName(modelId, name)) {
                if (addresses.remove(address) && addresses.size == 1) soleSince = now
                return false
            }
            if (addresses.add(address) && addresses.size == 1) soleSince = now
            return true
        }

        fun finish() { complete = true }

        fun readyInMillis(now: Long): Long? {
            if (addresses.size != 1) return null
            return if (now <= soleSince) STABLE_MS else (STABLE_MS - (now - soleSince)).coerceAtLeast(0)
        }

        fun automatic(now: Long): String? = addresses.singleOrNull()?.takeIf { readyInMillis(now) == 0L }

        val ambiguous: Boolean get() = addresses.size > 1

        companion object { const val STABLE_MS = 2_000L }
    }

    class Visits {
        // shortcut: public reports coalesce same-model pairs; split visits when authenticated identity is available.
        private data class Visit(var at: Long, var open: Boolean, var offered: Boolean)
        private val visits = mutableMapOf<Int, Visit>()

        fun observe(event: Event, now: Long, alreadyPaired: Boolean, protectedModel: Int? = null): Event? {
            val visit = visits.getOrPut(event.modelId) { Visit(now, false, false) }
            if (now < visit.at) return null
            if (now - visit.at >= ABSENCE_MS) visit.offered = false
            visit.at = now
            if (!event.lidOpen) {
                val wasOpen = visit.open
                visit.open = false
                visit.offered = false
                return event.takeIf { wasOpen }
            }
            if (alreadyPaired && event.modelId != protectedModel) {
                val wasOpen = visit.open
                visit.open = false
                visit.offered = true
                return event.copy(lidOpen = false).takeIf { wasOpen }
            }
            visit.open = true
            if (visit.offered) return null
            visit.offered = true
            return event
        }

        fun expire(now: Long, protectedModel: Int? = null): List<Event> = visits.mapNotNull { (model, visit) ->
            if (model != protectedModel && visit.open && now - visit.at >= ABSENCE_MS) {
                visit.open = false
                visit.offered = false
                Event(model, false)
            } else null
        }

        fun clear(): List<Event> {
            val closed = visits.filterValues { it.open }.keys.map { Event(it, false) }
            visits.clear()
            return closed
        }
    }

    /** A closed/replaced invitation invalidates every discovery and bond callback. */
    class Session {
        var generation: Long = 0
            private set
        var modelId: Int? = null
            private set
        private var attempt = 0L
        private var requestedAt = 0L
        var isBlocked = false
            private set

        fun open(model: Int): Long? {
            if (isBlocked || modelId != null || model !in modelNames) return null
            generation++
            modelId = model
            return generation
        }

        fun isCurrent(token: Long): Boolean = modelId != null && generation == token

        fun beginAttempt(token: Long, now: Long = 0): Long? {
            if (!isCurrent(token) || isBlocked) return null
            requestedAt = now
            return ++attempt
        }

        fun isCurrentAttempt(token: Long, operation: Long): Boolean = !isBlocked && isCurrent(token) && attempt == operation

        fun remainingMillis(token: Long, operation: Long, now: Long): Long =
            if (isCurrentAttempt(token, operation)) (REQUEST_MS - (now - requestedAt).coerceAtLeast(0)).coerceAtLeast(0) else 0

        fun setBlocked(blocked: Boolean) {
            isBlocked = blocked
            if (blocked && modelId != null) close()
        }

        fun close(model: Int? = null): Boolean {
            if (model != null && modelId != model) return false
            generation++
            modelId = null
            return true
        }

        companion object { const val REQUEST_MS = 60_000L }
    }

    private const val ABSENCE_MS = 30_000L
}
