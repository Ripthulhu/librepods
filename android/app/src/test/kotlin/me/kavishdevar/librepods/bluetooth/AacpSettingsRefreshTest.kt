package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MagicKeyType
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.effectiveAllowOff
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.shadows.ShadowBluetoothSocket
import java.io.IOException
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class,
    shadows = [RefreshSocketShadow::class, RefreshDeviceShadow::class])
class AacpSettingsRefreshTest {
    @Test fun retiredTransportCannotPublishKeysAfterItsInitialOwnershipCheck() {
        val device = createDevice()
        val manager = device.aacp
        val original = socket(connected = true)
        val replacement = socket(connected = true)
        fun keys(value: Int): ByteArray {
            fun record(type: Int) = byteArrayOf(type.toByte(), 0, 16, 0) + ByteArray(16) { value.toByte() }
            return byteArrayOf(4, 0, 4, 0, 0x31, 0, 2) + record(1) + record(4)
        }
        bind(manager, original)
        manager.processPacket(keys(1), original)
        assertArrayEquals(ByteArray(16) { 1 }, device.state.value.magicKeys[MagicKeyType.IRK])
        val entered = CountDownLatch(1)
        var worker: Thread? = null
        try {
            synchronized(manager) {
                val pending = thread {
                    entered.countDown()
                    manager.processPacket(keys(2), original)
                }
                worker = pending
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (pending.state != Thread.State.BLOCKED && pending.isAlive && System.nanoTime() < deadline) Thread.yield()
                assertEquals("The old report must wait at the transport lifecycle monitor", Thread.State.BLOCKED, pending.state)
                bind(manager, replacement)
                manager.processPacket(keys(3), replacement)
            }
            val completed = checkNotNull(worker)
            completed.join(2_000)
            assertFalse(completed.isAlive)
            assertArrayEquals(ByteArray(16) { 3 }, device.state.value.magicKeys[MagicKeyType.IRK])
            assertArrayEquals(ByteArray(16) { 3 }, device.state.value.magicKeys[MagicKeyType.ENC_KEY])
            manager.processPacket(keys(4), null)
            assertArrayEquals("A report without a live transport cannot replace pairing keys",
                ByteArray(16) { 3 }, device.state.value.magicKeys[MagicKeyType.IRK])
        } finally { manager.disconnect(); original.close(); replacement.close(); worker?.join(2_000) }
    }

    @Test fun refreshReopensOnlyControlTransportAndPreservesConfirmedSettingsAndKeys() {
        val device = createDevice()
        val id = ControlCommandIdentifier.LISTENING_MODE
        val controls = mapOf(id to byteArrayOf(4))
        val keys = mapOf(MagicKeyType.IRK to ByteArray(16) { 1 }, MagicKeyType.ENC_KEY to ByteArray(16) { 2 })
        val liveBatteries = setOf(Battery(BatteryComponent.CASE, 62, BatteryStatus.CHARGING, System.currentTimeMillis() - 1000))
        device.loadInitialState(AppleState(controlStates = controls, magicKeys = keys, battery = liveBatteries, headTrackingState = BuddyState.WAITING,
            requestedControls = mapOf(id to byteArrayOf(2))), AppleSettings(allowOffChoice = true), fixtureMetadata())
        val confirmedControls = device.state.value.controlStates
        val original = socket(connected = true)
        val replacement = socket()
        val manager = AACPManager(device, socketFactory = { replacement })
        bind(manager, original)
        try {
            val request = requireNotNull(manager.prepareControlSessionRefresh())
            assertTrue(manager.refreshControlSession(request) { true })
            assertFalse(original.isConnected)
            assertSame(replacement, manager.captureAudioSocket())
            assertSame(confirmedControls, device.state.value.controlStates)
            assertSame(keys, device.state.value.magicKeys)
            assertEquals("Reopening only AACP must retain the unchanged physical battery observation", liveBatteries, device.state.value.battery)
            assertTrue(device.state.value.requestedControls.isEmpty())
            assertEquals("The replacement sensor session must retain the user's active intent", BuddyState.WAITING,
                device.state.value.headTrackingState)
            assertEquals(true, device.settings.value.allowOffChoice)
            assertEquals(ConnectionState.CONNECTED, device.connectionState.value)
            assertEquals("A settings refresh must not disconnect native audio profiles", 0,
                deviceShadow(device.bluetoothDevice).disconnects.get())
        } finally { manager.disconnect(); original.close(); replacement.close() }
    }

    @Test fun cancellingBlockedConnectClosesItsReplacementAndCannotPublishSuccess() {
        val device = createDevice()
        val observedAt = System.currentTimeMillis() - 1000
        device.loadInitialState(AppleState(battery = setOf(Battery(BatteryComponent.CASE, 62, BatteryStatus.CHARGING, observedAt))),
            AppleSettings(), fixtureMetadata())
        val original = socket(connected = true)
        val replacement = socket()
        socketShadow(replacement).blockConnect()
        val manager = AACPManager(device, socketFactory = { replacement })
        bind(manager, original)
        val request = requireNotNull(manager.prepareControlSessionRefresh())
        val result = AtomicReference<Boolean?>()
        val worker = thread { result.set(manager.refreshControlSession(request) { true }) }
        try {
            assertTrue(socketShadow(replacement).connectEntered.await(2, TimeUnit.SECONDS))
            manager.cancelControlSessionRefresh(request)
            worker.join(2_000)
            assertFalse("Closing the owned transport must release blocked connect", worker.isAlive)
            assertEquals(false, result.get())
            assertFalse(replacement.isConnected)
            assertNull(manager.captureAudioSocket())
            assertEquals(Battery(BatteryComponent.CASE, 62, BatteryStatus.DISCONNECTED, observedAt, present = false),
                device.state.value.battery.single())
            assertEquals(0, deviceShadow(device.bluetoothDevice).disconnects.get())
        } finally { manager.cancelControlSessionRefresh(request); replacement.close(); original.close(); worker.join(2_000) }
    }

    @Test fun cancellingAnOldRefreshCannotCloseANewerSuccessfulRefresh() {
        val device = createDevice()
        val original = socket(connected = true)
        val first = socket()
        val second = socket()
        val created = AtomicInteger()
        val manager = AACPManager(device, socketFactory = { if (created.getAndIncrement() == 0) first else second })
        bind(manager, original)
        try {
            val oldRequest = requireNotNull(manager.prepareControlSessionRefresh())
            assertTrue(manager.refreshControlSession(oldRequest) { true })
            val nextRequest = requireNotNull(manager.prepareControlSessionRefresh())
            assertTrue(manager.refreshControlSession(nextRequest) { true })
            manager.cancelControlSessionRefresh(oldRequest)
            assertSame(second, manager.captureAudioSocket())
            assertTrue(second.isConnected)
            assertEquals(0, socketShadow(second).closes.get())
        } finally { manager.disconnect(); original.close(); first.close(); second.close() }
    }

    @Test fun twoRequestsForTheSameOriginalSocketCreateOnlyOneReplacement() {
        val device = createDevice()
        val original = socket(connected = true)
        val replacement = socket()
        val replacementShadow = socketShadow(replacement)
        replacementShadow.blockConnect()
        val created = AtomicInteger()
        val manager = AACPManager(device, socketFactory = { created.incrementAndGet(); replacement })
        bind(manager, original)
        val first = requireNotNull(manager.prepareControlSessionRefresh())
        val second = requireNotNull(manager.prepareControlSessionRefresh())
        val firstResult = AtomicReference<Boolean?>()
        val secondResult = AtomicReference<Boolean?>()
        val a = thread { firstResult.set(manager.refreshControlSession(first) { true }) }
        var b: Thread? = null
        try {
            assertTrue(replacementShadow.connectEntered.await(2, TimeUnit.SECONDS))
            val waiting = CountDownLatch(1)
            b = thread { waiting.countDown(); secondResult.set(manager.refreshControlSession(second) { true }) }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            replacementShadow.unblockConnect()
            a.join(2_000); b.join(2_000)
            assertFalse(a.isAlive); assertFalse(b.isAlive)
            assertEquals(true, firstResult.get())
            assertEquals(false, secondResult.get())
            assertEquals(1, created.get())
            assertSame(replacement, manager.captureAudioSocket())
        } finally {
            manager.cancelControlSessionRefresh(first)
            replacement.close(); original.close(); a.join(2_000); b?.join(2_000)
        }
    }

    @Test fun realAllowOffWritesPersistOnlyOnSuccessAndFreshReportsWinEvenDuringTheWrite() {
        val device = createDevice()
        val replacement = socket()
        val manager = AACPManager(device, socketFactory = { replacement })
        val original = socket(connected = true)
        bind(manager, original)
        val id = ControlCommandIdentifier.ALLOW_OFF_OPTION
        fun report(source: BluetoothSocket = original) = manager.processPacket(byteArrayOf(4, 0, 4, 0, 9, 0, 0x34, 2, 0, 0, 0), source)
        val output = socketShadow(original).output
        try {
            report()
            assertEquals(false, device.settings.value.allowOffChoice)
            assertTrue(manager.sendControlCommand(id.value, byteArrayOf(1)))
            assertEquals(true, device.settings.value.allowOffChoice)
            assertArrayEquals(byteArrayOf(2), device.state.value.controlStates[id])
            assertArrayEquals(byteArrayOf(1), device.state.value.requestedControls[id])

            // A stale cached report must not replace a newer silent choice after app restart.
            val reloaded = createDevice()
            val listening = ControlCommandIdentifier.LISTENING_MODE
            reloaded.loadInitialState(AppleState(controlStates = mapOf(id to byteArrayOf(2), listening to byteArrayOf(4))),
                device.settings.value, fixtureMetadata())
            assertFalse(id in reloaded.state.value.controlStates)
            assertArrayEquals(byteArrayOf(4), reloaded.state.value.controlStates[listening])
            assertEquals(true, reloaded.state.value.effectiveAllowOff(reloaded.settings.value, "A3063"))
            assertNull("A separate pair must not inherit another device's choice", createDevice("00:11:22:33:44:66").settings.value.allowOffChoice)
            val unknownChoice = createDevice()
            unknownChoice.loadInitialState(AppleState(controlStates = mapOf(id to byteArrayOf(2))), AppleSettings(), fixtureMetadata())
            assertArrayEquals(byteArrayOf(2), unknownChoice.state.value.controlStates[id])
            val unsupported = createDevice()
            unsupported.loadInitialState(AppleState(controlStates = mapOf(id to byteArrayOf(2))),
                AppleSettings(allowOffChoice = true), AppleMetadata(modelNumber = "A2084"))
            assertArrayEquals(byteArrayOf(2), unsupported.state.value.controlStates[id])

            output.failWrites = true
            assertFalse(manager.sendControlCommand(id.value, byteArrayOf(2)))
            assertEquals(true, device.settings.value.allowOffChoice)
            output.failWrites = false
            output.beforeWrite = { report() }
            assertTrue(manager.sendControlCommand(id.value, byteArrayOf(1)))
            assertEquals(false, device.settings.value.allowOffChoice)
            assertFalse(id in device.state.value.requestedControls)
            assertTrue(manager.sendControlCommand(id.value, byteArrayOf(1)))
            val request = requireNotNull(manager.prepareControlSessionRefresh())
            assertTrue(manager.refreshControlSession(request) { true })
            assertEquals(true, device.state.value.effectiveAllowOff(device.settings.value, "A3063"))
            assertFalse("Retained old reports become cached during a planned refresh", id in device.state.value.controlStates)
            report(replacement)
            assertEquals(false, device.settings.value.allowOffChoice)
            assertEquals(false, device.state.value.effectiveAllowOff(device.settings.value, "A3063"))
        } finally { manager.disconnect(); original.close(); replacement.close() }
    }

    @Test fun repeatedSocketRetirementKeepsOnlyObservedPercentagesUntilFreshReportsReplaceThem() {
        val device = createDevice()
        val manager = device.aacp
        val observedAt = System.currentTimeMillis() - 2000
        val initial = setOf(Battery(BatteryComponent.LEFT, 0, BatteryStatus.CHARGING, observedAt),
            Battery(BatteryComponent.RIGHT, 70, BatteryStatus.CHARGING, observedAt),
            Battery(BatteryComponent.CASE, 60, BatteryStatus.CHARGING, observedAt),
            Battery(BatteryComponent.HEADSET, 0, BatteryStatus.DISCONNECTED),
            Battery(BatteryComponent.HEADSET, 0, BatteryStatus.UNKNOWN, observedAt),
            Battery(BatteryComponent.LEFT, 101, BatteryStatus.CHARGING, observedAt))
        device.loadInitialState(AppleState(battery = initial), AppleSettings(), fixtureMetadata())
        val sockets = mutableListOf<BluetoothSocket>()
        val unavailable = setOf(Battery(BatteryComponent.LEFT, 0, BatteryStatus.DISCONNECTED),
            Battery(BatteryComponent.RIGHT, 0, BatteryStatus.DISCONNECTED), Battery(BatteryComponent.CASE, 0, BatteryStatus.DISCONNECTED))
        try {
            repeat(3) {
                val socket = socket(connected = true).also { sockets += it }
                bind(manager, socket)
                assertTrue(manager.disconnect(socket))
                val cached = device.state.value.battery.associateBy { it.component }
                assertEquals(3, cached.size)
                assertEquals(0, cached[BatteryComponent.LEFT]?.level)
                assertEquals(70, cached[BatteryComponent.RIGHT]?.level)
                assertEquals(60, cached[BatteryComponent.CASE]?.level)
                assertTrue(cached.values.all { !it.present && it.status == BatteryStatus.DISCONNECTED && it.observedAt == observedAt })
                device.receiveNearbyBattery(unavailable)
                assertEquals(cached.values.toSet(), device.state.value.battery)
            }
            val freshAt = System.currentTimeMillis()
            device.receiveNearbyBattery(setOf(Battery(BatteryComponent.LEFT, 81, BatteryStatus.CHARGING, freshAt)))
            assertEquals(81, device.state.value.battery.first { it.component == BatteryComponent.LEFT }.level)
            val current = socket(connected = true).also { sockets += it }
            bind(manager, current)
            val before = device.state.value.battery
            device.receiveNearbyBattery(setOf(Battery(BatteryComponent.LEFT, 5, BatteryStatus.NOT_CHARGING, freshAt)))
            assertEquals("BLE must not replace a live AACP observation", before, device.state.value.battery)
            manager.processPacket(byteArrayOf(4, 0, 4, 0, 4, 0, 1, 2, 1, 72, 2, 1), current)
            assertEquals(72, device.state.value.battery.first { it.component == BatteryComponent.RIGHT }.level)
            val refreshed = device.state.value.battery
            assertFalse(manager.disconnect(sockets.first()))
            assertEquals("An old socket cannot invalidate replacement readings", refreshed, device.state.value.battery)
            device.updateSettings { it.copy(cacheDisconnectedComponentBattery = false) }
            assertTrue(manager.disconnect(current))
            assertTrue(device.state.value.battery.isEmpty())
        } finally { manager.disconnect(); sockets.forEach { it.close() } }
    }

    @Test fun failedRefreshRetainsPercentagesWithoutRetainingLiveCharging() {
        val device = createDevice()
        val observedAt = System.currentTimeMillis() - 1000
        device.loadInitialState(AppleState(battery = setOf(Battery(BatteryComponent.CASE, 62, BatteryStatus.CHARGING, observedAt))),
            AppleSettings(), fixtureMetadata())
        val original = socket(connected = true)
        val manager = AACPManager(device, socketFactory = { throw IOException("Test connect failure") })
        bind(manager, original)
        try {
            assertFalse(manager.refreshControlSession(requireNotNull(manager.prepareControlSessionRefresh())) { true })
            assertEquals(Battery(BatteryComponent.CASE, 62, BatteryStatus.DISCONNECTED, observedAt, present = false),
                device.state.value.battery.single())
        } finally { manager.disconnect(); original.close() }
    }

    private fun createDevice(address: String = "00:11:22:33:44:55"): AppleDevice {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        return AppleDevice(context, adapter, adapter.getRemoteDevice(address), ConnectionState.CONNECTED).apply {
            loadInitialState(AppleState(), AppleSettings(), fixtureMetadata())
        }
    }

    private fun fixtureMetadata() = AppleMetadata(modelNumber = "A3063", version1 = "9A348")
    private fun socket(connected: Boolean = false) = Shadow.newInstanceOf(BluetoothSocket::class.java).also {
        if (connected) it.connect()
    }
    private fun socketShadow(socket: BluetoothSocket): RefreshSocketShadow = Shadow.extract(socket)
    private fun deviceShadow(device: BluetoothDevice): RefreshDeviceShadow = Shadow.extract(device)
    private fun bind(manager: AACPManager, socket: BluetoothSocket) {
        AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(manager, socket)
    }
}

@Implements(BluetoothSocket::class)
class RefreshSocketShadow : ShadowBluetoothSocket() {
    val connectEntered = CountDownLatch(1)
    val closes = AtomicInteger()
    val output = RefreshOutput()
    init { setOutputStream(output) }
    @Implementation public override fun connect() { connectEntered.countDown(); super.connect() }
    @Implementation public override fun close() {
        closes.incrementAndGet()
        super.close()
        inputStreamFeeder.close()
        outputStreamSink.close()
    }
}

class RefreshOutput : PipedOutputStream() {
    @Volatile var failWrites = false
    var beforeWrite: (() -> Unit)? = null
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (failWrites) throw IOException("Test write failure")
        beforeWrite?.also { beforeWrite = null; it() }
    }
    override fun flush() = Unit
}

@Implements(BluetoothDevice::class)
class RefreshDeviceShadow : ShadowBluetoothDevice() {
    val disconnects = AtomicInteger()
    @Implementation fun disconnect(): Int { disconnects.incrementAndGet(); return 0 }
}
