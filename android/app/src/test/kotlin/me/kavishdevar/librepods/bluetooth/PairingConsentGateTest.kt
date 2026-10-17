/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Looper
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, shadows = [PairingDeviceShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PairingConsentGateTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private data class Incoming(val device: BluetoothDevice, val at: Long, val id: Long)
    private class Owner : IPairingConsentOwner.Stub() {
        var ready = false
        var fail = false
        val requests = mutableListOf<Incoming>()
        override fun onReady() { ready = true }
        override fun onConsent(device: BluetoothDevice, receivedAt: Long, requestId: Long) {
            if (fail) throw IllegalStateException("Owner unavailable")
            requests += Incoming(device, receivedAt, requestId)
        }
    }

    @Test fun workerOnlyAbortsTheExactCurrentRequestAndNeverReusesItsId() = withWorker { service, bridge, native ->
        val owner = Owner()
        bridge.watch(0x2720, owner)
        idle()
        assertTrue(owner.ready)
        send(device(1))
        val first = owner.requests.single()
        val held = held(service)
        assertEquals(0, native())
        bridge.finish(first.id + 1, true)
        bridge.stop(Owner())
        idle()
        assertSame(held, held(service))
        bridge.finish(first.id, true)
        idle()
        assertFinished(held, true)
        send(device(2))
        val second = owner.requests.last()
        assertTrue(second.id > first.id)
        val next = held(service)
        bridge.finish(first.id, true)
        idle()
        assertSame(next, held(service))
        bridge.finish(second.id, false)
        idle()
        assertFinished(next, false)
    }

    @Test fun workerDeadlineAndOwnerRetirementReturnHeldRequestsToAndroid() = withWorker { service, bridge, native ->
        val owner = Owner()
        bridge.watch(0x2720, owner)
        idle()
        val receivedAt = SystemClock.elapsedRealtime()
        send(device(1))
        val held = held(service)
        assertEquals(receivedAt, owner.requests.single().at)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(6_999))
        assertEquals(0, native())
        assertFalse(Shadow.extract<ShadowBroadcastPendingResult>(held).future.isDone)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertFinished(held, false)
        assertEquals(1, native())
        bridge.finish(owner.requests.single().id, true)
        idle()
        assertFinished(held, false)
        send(device(2))
        val stopped = held(service)
        bridge.stop(owner)
        idle()
        assertFinished(stopped, false)
        bridge.watch(0x2720, owner)
        idle()
        send(device(3))
        val unbound = held(service)
        service.onUnbind(Intent())
        assertFinished(unbound, false)
    }

    @Test fun workerLeavesUnsupportedRequestsUntouchedAndFailsOpenWhenItsOwnerThrows() = withWorker { service, bridge, native ->
        val owner = Owner()
        bridge.watch(0x2720, owner)
        idle()
        send(device(1, name = null))
        send(device(2, name = "Other headphones"))
        send(device(3, type = BluetoothDevice.DEVICE_TYPE_LE))
        send(device(4), variant = 2)
        send(device(5, bonding = false))
        context.sendBroadcast(request(device(6), 3))
        idle()
        assertEquals(6, native())
        assertTrue(owner.requests.isEmpty())
        assertNull(pending(service))
        owner.fail = true
        send(device(7))
        assertNull(pending(service))
        assertEquals(7, native())
    }

    @Test fun clientWaitsForRegistrationAckAndCompletesEachConsentOnlyOnce() {
        val requests = mutableListOf<PairingConsentGate.Consent>()
        val remote = FakeGate()
        prepareBind(remote)
        val gate = PairingConsentGate(context, 0x2720) { _, _, request -> requests += request }
        try {
            idle()
            val connection = connection(gate)
            assertFalse(gate.isWatching)
            remote.owner.onReady()
            idle()
            assertTrue(gate.isWatching)
            val now = SystemClock.elapsedRealtime()
            remote.owner.onConsent(device(1), now, 10)
            idle()
            val request = requests.single()
            assertEquals(now + 7_000, request.deadline)
            request.finish(true)
            request.finish(false)
            assertEquals(listOf(10L to true), remote.finished)
            connection.onServiceDisconnected(ComponentName(context, PairingConsentService::class.java))
            assertFalse(gate.isWatching)
            assertEquals(1, remote.stops)
            connection.onServiceConnected(ComponentName(context, PairingConsentService::class.java), remote)
            remote.owner.onReady()
            remote.owner.onConsent(device(2), SystemClock.elapsedRealtime(), 11)
            idle()
            assertFalse("A disconnected watch must never reauthorize its old callback owner", gate.isWatching)
            assertEquals(1, requests.size)
            assertEquals(listOf(10L to true), remote.finished)
        } finally { gate.close() }
    }

    @Test fun clientCallbackErrorsAndQueuedCallbacksAfterCloseReleaseWithoutAcceptance() {
        val remote = FakeGate()
        prepareBind(remote)
        val gate = PairingConsentGate(context, 0x2720) { _, _, _ -> throw IllegalStateException("Attempt retired") }
        try {
            idle()
            remote.owner.onReady()
            idle()
            remote.owner.onConsent(device(1), SystemClock.elapsedRealtime(), 20)
            idle()
            assertEquals(listOf(20L to false), remote.finished)
            remote.owner.onConsent(device(2), SystemClock.elapsedRealtime(), 21)
            gate.close()
            idle()
            assertFalse(gate.isWatching)
            assertEquals(listOf(20L to false, 21L to false), remote.finished)
        } finally { gate.close() }
    }

    private class FakeGate : IPairingConsentGate.Stub() {
        lateinit var owner: IPairingConsentOwner
        val finished = mutableListOf<Pair<Long, Boolean>>()
        var stops = 0
        override fun watch(modelId: Int, owner: IPairingConsentOwner) { this.owner = owner }
        override fun finish(requestId: Long, accepted: Boolean) { finished += requestId to accepted }
        override fun stop(owner: IPairingConsentOwner) { stops++ }
    }
    private fun prepareBind(remote: FakeGate) {
        shadowOf(context).setComponentNameAndServiceForBindService(
            ComponentName(context, PairingConsentService::class.java), remote)
        shadowOf(context).setUnbindServiceCallsOnServiceDisconnected(false)
    }
    private fun connection(gate: PairingConsentGate): ServiceConnection = PairingConsentGate::class.java
        .getDeclaredField("connection").apply { isAccessible = true }.get(gate) as ServiceConnection
    private fun withWorker(block: (PairingConsentService, IPairingConsentGate, () -> Int) -> Unit) {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        var native = 0
        val fallback = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { native++ } }
        context.registerReceiver(fallback, IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST), Context.RECEIVER_EXPORTED)
        val lifecycle = Robolectric.buildService(PairingConsentService::class.java).create()
        val service = lifecycle.get()
        try { block(service, IPairingConsentGate.Stub.asInterface(service.onBind(Intent())), { native }) }
        finally { lifecycle.destroy(); context.unregisterReceiver(fallback) }
    }
    private fun device(id: Int, name: String? = "AirPods Pro 3", type: Int = BluetoothDevice.DEVICE_TYPE_DUAL,
        bonding: Boolean = true): BluetoothDevice {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        return adapter.getRemoteDevice("00:11:22:33:44:${id.toString(16).padStart(2, '0')}").also {
            Shadow.extract<PairingDeviceShadow>(it).apply {
                setName(name); setType(type)
                setBondState(if (bonding) BluetoothDevice.BOND_BONDING else BluetoothDevice.BOND_NONE)
            }
        }
    }
    private fun request(device: BluetoothDevice, variant: Int) = Intent(BluetoothDevice.ACTION_PAIRING_REQUEST)
        .putExtra(BluetoothDevice.EXTRA_DEVICE, device).putExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, variant)
    private fun send(device: BluetoothDevice, variant: Int = 3) { context.sendOrderedBroadcast(request(device, variant), null); idle() }
    private fun pending(service: PairingConsentService): Any? = PairingConsentService::class.java.getDeclaredField("pending")
        .apply { isAccessible = true }.get(service)
    private fun held(service: PairingConsentService): BroadcastReceiver.PendingResult = requireNotNull(pending(service)).let {
        it.javaClass.getDeclaredField("result").apply { isAccessible = true }.get(it) as BroadcastReceiver.PendingResult
    }
    private fun assertFinished(result: BroadcastReceiver.PendingResult, aborted: Boolean) {
        assertEquals(aborted, result.getAbortBroadcast())
        assertTrue(Shadow.extract<ShadowBroadcastPendingResult>(result).future.isDone)
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
