/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.AACPPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MessageOpcode
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MagicKeyType
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.ConnectionState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.io.PipedOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class CapturedAudioTransportTest {
    @Test fun incompleteKeyRepliesLeaveTheCachedPairIntactUntilBothReplacementKeysArrive() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        val device = AppleDevice(context, adapter, adapter.getRemoteDevice("00:11:22:33:44:55"), ConnectionState.DISCONNECTED)
        val previous = mapOf(MagicKeyType.IRK to ByteArray(16) { 1 }, MagicKeyType.ENC_KEY to ByteArray(16) { 2 })
        device.loadInitialState(AppleState(magicKeys = previous), device.settings.value, device.metadata.value)
        val socket = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
        AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(device.aacp, socket)
        fun record(type: Int) = byteArrayOf(type.toByte(), (type shr 8).toByte(), 16, 0) + ByteArray(16) { type.toByte() }
        fun packet(vararg records: ByteArray) = byteArrayOf(4, 0, 4, 0, 0x31, 0, records.size.toByte()) +
            records.fold(byteArrayOf()) { bytes, row -> bytes + row }
        try {
            for (incomplete in listOf(packet(), packet(record(1)), packet(record(4)), packet(record(0x401)))) {
                assertThrows(IllegalArgumentException::class.java) { device.aacp.processPacket(incomplete, socket) }
                assertSame(previous, device.state.value.magicKeys)
                assertTrue(socket.isConnected)
            }
            val irk = ByteArray(16) { 3 }
            val encryption = ByteArray(16) { 4 }
            device.aacp.processPacket(packet(byteArrayOf(1, 0, 16, 0) + irk, record(0x401),
                byteArrayOf(4, 0, 16, 0) + encryption), socket)
            assertArrayEquals(irk, device.state.value.magicKeys[MagicKeyType.IRK])
            assertArrayEquals(encryption, device.state.value.magicKeys[MagicKeyType.ENC_KEY])
            assertTrue(device.state.value.aacpPackets.isEmpty())
        } finally {
            device.aacp.disconnect(socket)
            socket.close()
        }
    }

    @Test fun delayedCommandsAndPacketsCannotMoveToANewConnectionToTheSameAirPods() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        val bluetoothDevice = adapter.getRemoteDevice("00:11:22:33:44:55")
        val device = AppleDevice(context, adapter, bluetoothDevice, ConnectionState.DISCONNECTED)
        val manager = device.aacp
        // The shadow device factory caches one socket; a reconnect needs two real identities.
        val first = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
        val replacement = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
        assertNotSame(first, replacement)
        val writes = mutableListOf<ByteArray>()
        fun bind(socket: BluetoothSocket) {
            shadowOf(socket).setOutputStream(object : PipedOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, length: Int) { writes += bytes.copyOfRange(offset, offset + length) }
                override fun flush() = Unit
            })
            AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(manager, socket)
        }
        try {
            bind(first)
            assertSame(first, manager.captureAudioSocket())
            assertTrue(manager.requestMicrophoneStream(first))
            assertEquals(1, writes.size)
            assertTrue(device.state.value.aacpPackets.isEmpty())

            bind(replacement)
            assertFalse(manager.endMicrophoneStream(first))
            assertFalse(manager.disconnect(first))
            assertSame(replacement, manager.captureAudioSocket())
            assertEquals(1, writes.size)
            var packets = 0
            manager.microphonePacketCallback = { source, _ -> assertSame(replacement, source); packets++ }
            val audio = byteArrayOf(4, 0, 4, 0, 0x58, 0, 1, 0) + ByteArray(14) + byteArrayOf(1, 0, 0, 0, 1, 0x55)
            manager.processPacket(audio, first)
            assertEquals(0, packets)
            manager.processPacket(audio, replacement)
            assertEquals(1, packets)
            assertTrue(device.state.value.aacpPackets.isEmpty())

            assertTrue(manager.sendPacket(AACPPacket.createUnknownPacket(MessageOpcode.MICROPHONE_STREAM, byteArrayOf(0, 0, 2, 0, 3, 1))))
            assertTrue(device.state.value.aacpPackets.isEmpty())

            val connectLock = AACPManager::class.java.getDeclaredField("connectLock").apply { isAccessible = true }.get(manager) as java.util.concurrent.locks.ReentrantLock
            var queuedResult: Boolean? = null
            val queued = Thread { queuedResult = manager.connect() }
            connectLock.lock()
            try {
                queued.start()
                val deadline = System.nanoTime() + 2_000_000_000L
                while (!connectLock.hasQueuedThread(queued) && System.nanoTime() < deadline) Thread.yield()
                assertTrue("Connect did not wait for the existing operation", connectLock.hasQueuedThread(queued))
                assertTrue(manager.disconnect(replacement))
            } finally { connectLock.unlock() }
            queued.join(1_000)
            assertFalse("Queued connect survived cancellation", queuedResult ?: true)
            assertNull(manager.captureAudioSocket())
        } finally {
            first.close()
            replacement.close()
        }
    }
}
