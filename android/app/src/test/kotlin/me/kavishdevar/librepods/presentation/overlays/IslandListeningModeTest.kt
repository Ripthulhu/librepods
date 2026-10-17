/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import me.kavishdevar.librepods.core.bluetooth.aacp.packet.ControlCommandPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.core.devices.ComponentStatus
import me.kavishdevar.librepods.core.devices.DeviceComponent
import me.kavishdevar.librepods.core.devices.DeviceComponentState
import me.kavishdevar.librepods.services.saveChangedControlCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IslandListeningModeTest {
    @Test fun paddedWireReportsReachTheConfirmedModeBaseline() {
        val modes = IslandListeningMode(); val socket = Any()
        var previous: ByteArray? = null
        fun report(mode: Byte): Int? {
            val packet = byteArrayOf(4, 0, 4, 0, 9, 0, 13, mode, 0, 0, 0)
            val value = ControlCommandPacket.parse(packet).controlCommand.value
            assertEquals(mode, value.singleOrNull())
            return modes.observeReport(socket, previous, value).also { previous = value }
        }
        assertNull(report(2))
        assertNull(report(2))
        assertEquals(4, report(4))
    }

    @Test fun anOldEmissionResumedAfterReconnectCannotSeedTheReplacementBaseline() {
        val modes = IslandListeningMode(); val first = Any(); val replacement = Any()
        val baseline = byteArrayOf(2); val stale = byteArrayOf(3); val fresh = byteArrayOf(1)
        assertNull(modes.observeReport(first, null, baseline))
        assertNull(modes.observeReport(replacement, baseline, stale, currentReport = fresh))
        assertNull(modes.observeReport(replacement, stale, fresh, currentReport = fresh))
        assertEquals(4, modes.observeReport(replacement, fresh, byteArrayOf(4)))
    }

    @Test fun confirmedModeCachePersistsWhenBatteryAndWearChangesAreCoalescedButRequestsDoNotWrite() = runBlocking {
        val id = ControlCommandIdentifier.LISTENING_MODE
        val baseline = AppleState(controlStates = mapOf(id to byteArrayOf(2)))
        val coalesced = baseline.copy(controlStates = mapOf(id to byteArrayOf(4)),
            battery = setOf(Battery(BatteryComponent.LEFT, 83, BatteryStatus.NOT_CHARGING)),
            componentState = setOf(DeviceComponentState(DeviceComponent.LEFT, ComponentStatus.IN_EAR)))
        val saved = mutableListOf<AppleState>()
        saveChangedControlCache(baseline, coalesced) { saved += it }
        assertSame(coalesced, saved.single())
        assertEquals(4, saved.single().controlStates.getValue(id).single().toInt())
        assertEquals(83, saved.single().battery.single().level)
        val requested = coalesced.copy(requestedControls = mapOf(id to byteArrayOf(3)))
        saveChangedControlCache(coalesced, requested) { saved += it }
        saveChangedControlCache(coalesced, coalesced.copy(controlStates = emptyMap())) { saved += it }
        assertEquals(1, saved.size)
    }

    @Test fun initialRepeatedAndInvalidReportsDoNotAnnounceAChange() {
        val modes = IslandListeningMode()
        assertFalse(modes.observe(0))
        assertFalse(modes.observe(2))
        assertFalse(modes.observe(2))
        assertFalse(modes.observe(5))
        assertFalse(modes.observe(-1))
        assertFalse(modes.observe(2))
        assertTrue(modes.observe(3))
        assertFalse(modes.observe(3))
        assertTrue(modes.observe(1))
        assertTrue(modes.observe(4))
    }

    @Test fun aNewConnectionMustEstablishItsOwnBaseline() {
        val modes = IslandListeningMode()
        assertFalse(modes.observe(2))
        assertTrue(modes.observe(3))
        modes.reset()
        assertFalse(modes.observe(1))
        assertTrue(modes.observe(4))
    }

    @Test fun cachedAndRequestedOnlyChangesDoNotSeedTheConfirmedReportBaseline() {
        val modes = IslandListeningMode(); val socket = Any()
        val cached = byteArrayOf(2)
        assertNull(modes.observeReport(socket, cached, cached))
        val firstReport = byteArrayOf(3)
        assertNull(modes.observeReport(socket, cached, firstReport))
        assertNull(modes.observeReport(socket, firstReport, firstReport))
        assertEquals(4, modes.observeReport(socket, firstReport, byteArrayOf(4)))
        assertNull(modes.observeReport(socket, firstReport, byteArrayOf(4)))
        assertNull(modes.observeReport(socket, firstReport, byteArrayOf(5)))
        assertNull(modes.observeReport(socket, firstReport, byteArrayOf(4, 1)))
        assertEquals(1, modes.observeReport(socket, firstReport, byteArrayOf(1)))
    }

    @Test fun reportBaselineBelongsToOneCapturedTransportEvenIfDisconnectStateWasCoalesced() {
        val modes = IslandListeningMode(); val first = Any(); val replacement = Any()
        assertNull(modes.observeReport(first, null, byteArrayOf(2)))
        assertEquals(3, modes.observeReport(first, byteArrayOf(2), byteArrayOf(3)))
        assertNull(modes.observeReport(replacement, byteArrayOf(3), byteArrayOf(1)))
        assertEquals(4, modes.observeReport(replacement, byteArrayOf(1), byteArrayOf(4)))
        assertNull(modes.observeReport(null, byteArrayOf(4), null))
        assertNull(modes.observeReport(replacement, null, byteArrayOf(2)))
    }
}
