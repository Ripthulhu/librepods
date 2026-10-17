package me.kavishdevar.librepods.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedCallTransportTest {
    @Test fun canonicalAddressRemainsBoundToTheExactSocket() {
        val generation = AudioSessionGeneration()
        val socket = Any()
        val selected = SelectedCallTransport(generation.next(), socket, "12:34:56:78:9a:bc")
        assertEquals("12:34:56:78:9A:BC", selected.address)
        assertTrue(selected.isCurrent(generation, socket, true))
        assertFalse(selected.isCurrent(generation, Any(), true)) // Same pair after reconnect still has a new socket.
        assertFalse(selected.isCurrent(generation, socket, false))
        assertFalse(selected.isCurrent(generation, null, true))
    }

    @Test fun stopOrANewSessionInvalidatesAnOtherwiseConnectedPair() {
        val generation = AudioSessionGeneration()
        val socket = Any()
        val old = SelectedCallTransport(generation.next(), socket, "12:34:56:78:9A:BC")
        generation.invalidate()
        assertFalse(old.isCurrent(generation, socket, true))
        val next = SelectedCallTransport(generation.next(), socket, old.address)
        assertTrue(next.isCurrent(generation, socket, true))
        assertFalse(old.isCurrent(generation, socket, true))
        generation.close()
        assertFalse(next.isCurrent(generation, socket, true))
    }

    @Test fun malformedOrPlaceholderAddressesNeverReachTheBridge() {
        for (address in listOf("", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "12:34:56:78:9A", "12:34:56:78:9A:BC suffix"))
            assertThrows(IllegalArgumentException::class.java) { SelectedCallTransport(1, Any(), address) }
    }
}
