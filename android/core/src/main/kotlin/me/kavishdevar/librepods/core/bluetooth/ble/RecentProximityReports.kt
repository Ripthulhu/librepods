package me.kavishdevar.librepods.core.bluetooth.ble

import me.kavishdevar.librepods.core.devices.AirPodsModel

/** Raw observations remain untrusted until the current pair's keys resolve and decode them. */
class RecentProximityReports {
    private data class Snapshot(val address: String, val manufacturer: ByteArray,
        val observedNanos: Long, val observedAt: Long)
    data class Observation(val report: ProximityReport, val observedNanos: Long)
    private val snapshots = mutableListOf<Snapshot>()

    fun observe(address: String, manufacturer: ByteArray, observedNanos: Long, observedAt: Long, nowNanos: Long) {
        expire(nowNanos)
        if (observedAt < 0 || observedNanos < 0 || observedNanos > nowNanos || nowNanos - observedNanos >= MAX_AGE_NANOS) return
        val record = NearbyAirPods.proximityRecord(manufacturer) ?: return
        if (record[2] != 1.toByte() || ProximityReport.model(((record[3].toInt() and 255) shl 8) or
                (record[4].toInt() and 255)) == AirPodsModel.UNKNOWN) return
        val repeated = snapshots.firstOrNull { it.address == address && it.manufacturer.contentEquals(manufacturer) }
        if (repeated != null && repeated.observedNanos >= observedNanos) return
        if (repeated != null) snapshots.remove(repeated)
        snapshots += Snapshot(address, manufacturer.copyOf(), observedNanos, observedAt)
        if (snapshots.size > 16) snapshots.removeAt(snapshots.indices.minBy { snapshots[it].observedNanos })
    }

    fun matching(irk: ByteArray, encryption: ByteArray, nowNanos: Long): List<Observation> {
        expire(nowNanos)
        return snapshots.sortedBy { it.observedNanos }.mapNotNull { snapshot ->
            ProximityReport.decode(snapshot.address, snapshot.manufacturer, irk, encryption, snapshot.observedAt)
                ?.let { Observation(it, snapshot.observedNanos) }
        }
    }

    fun expire(nowNanos: Long) {
        snapshots.removeAll { it.observedNanos > nowNanos || nowNanos - it.observedNanos >= MAX_AGE_NANOS }
    }
    fun clear() = snapshots.clear()

    private companion object { const val MAX_AGE_NANOS = 15_000_000_000L }
}
