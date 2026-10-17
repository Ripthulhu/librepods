/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.bluetooth

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import me.kavishdevar.librepods.core.bluetooth.ble.NearbyAirPods
import java.util.concurrent.atomic.AtomicLong

/** Holds consent only; the main process must validate the fresh Classic identity and confirm it. */
@SuppressLint("MissingPermission")
class PairingConsentService : Service() {
    private class Pending(val id: Long, val device: BluetoothDevice, val owner: IPairingConsentOwner,
        val deadline: Long, val result: BroadcastReceiver.PendingResult)
    private val handler = Handler(Looper.getMainLooper())
    private var owner: IPairingConsentOwner? = null
    private var death: IBinder.DeathRecipient? = null
    private var modelId = 0
    private var registered = false
    private var destroyed = false
    private var pending: Pending? = null
    private var expiry: Runnable? = null
    private val watchExpiry = Runnable { retire() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val currentOwner = owner ?: return
            if (!isOrderedBroadcast || pending != null || !currentOwner.asBinder().isBinderAlive ||
                intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1) != 3) return
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            if (!runCatching { device.bondState == BluetoothDevice.BOND_BONDING &&
                    device.type in setOf(BluetoothDevice.DEVICE_TYPE_CLASSIC, BluetoothDevice.DEVICE_TYPE_DUAL) &&
                    NearbyAirPods.matchesName(modelId, device.name) }.getOrDefault(false)) return
            val receivedAt = SystemClock.elapsedRealtime()
            val request = Pending(ids.incrementAndGet(), device, currentOwner, receivedAt + HOLD_MS, goAsync())
            pending = request
            expiry = Runnable { finishPending(request.id, false) }.also { handler.postDelayed(it, HOLD_MS) }
            runCatching { currentOwner.onConsent(device, receivedAt, request.id) }.onFailure { retire() }
        }
    }

    private val binder = object : IPairingConsentGate.Stub() {
        private fun enforceCaller() { check(Binder.getCallingUid() == applicationInfo.uid) { "Invalid pairing owner" } }
        override fun watch(modelId: Int, owner: IPairingConsentOwner) {
            enforceCaller()
            handler.post {
                if (destroyed) return@post
                retire()
                if (modelId !in NearbyAirPods.modelNames) return@post
                this@PairingConsentService.modelId = modelId
                this@PairingConsentService.owner = owner
                val recipient = IBinder.DeathRecipient { handler.post {
                    if (this@PairingConsentService.owner?.asBinder() === owner.asBinder()) retire()
                } }
                death = recipient
                runCatching {
                    owner.asBinder().linkToDeath(recipient, 0)
                    registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
                        priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 2
                    }, Context.RECEIVER_EXPORTED)
                    registered = true
                    handler.postDelayed(watchExpiry, NearbyAirPods.Session.REQUEST_MS)
                    owner.onReady()
                }.onFailure { retire() }
            }
        }
        override fun finish(requestId: Long, accepted: Boolean) {
            enforceCaller()
            handler.post { finishPending(requestId, accepted) }
        }
        override fun stop(owner: IPairingConsentOwner) {
            enforceCaller()
            handler.post { if (this@PairingConsentService.owner?.asBinder() === owner.asBinder()) retire() }
        }
    }

    private fun finishPending(requestId: Long, accepted: Boolean) {
        val request = pending?.takeIf { it.id == requestId } ?: return
        pending = null
        expiry?.let(handler::removeCallbacks)
        expiry = null
        val approve = accepted && owner?.asBinder() === request.owner.asBinder() &&
            request.owner.asBinder().isBinderAlive && SystemClock.elapsedRealtime() < request.deadline &&
            runCatching { request.device.bondState in setOf(BluetoothDevice.BOND_BONDING, BluetoothDevice.BOND_BONDED) }.getOrDefault(false)
        try { if (approve) request.result.abortBroadcast() } finally { request.result.finish() }
    }

    private fun retire() {
        pending?.let { finishPending(it.id, false) }
        handler.removeCallbacks(watchExpiry)
        if (registered) runCatching { unregisterReceiver(receiver) }
        registered = false
        death?.let { recipient -> owner?.let { runCatching { it.asBinder().unlinkToDeath(recipient, 0) } } }
        death = null
        owner = null
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onUnbind(intent: Intent?): Boolean { retire(); return false }
    override fun onDestroy() { destroyed = true; retire(); super.onDestroy() }

    companion object {
        internal const val HOLD_MS = 7_000L
        private val ids = AtomicLong()
    }
}
