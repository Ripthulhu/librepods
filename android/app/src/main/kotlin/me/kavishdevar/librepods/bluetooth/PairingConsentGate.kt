/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

interface PairingConsentWatch : Closeable {
    val isWatching: Boolean
}

/** Keeps an ordered request in a separate process so Classic discovery can continue here. */
class PairingConsentGate(context: Context, private val modelId: Int,
    private val onConsent: (BluetoothDevice, Long, Consent) -> Unit) : PairingConsentWatch {
    interface Consent {
        val deadline: Long
        fun finish(accepted: Boolean)
    }

    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var remote: IPairingConsentGate? = null
    @Volatile override var isWatching = false
        private set
    private var bound = false
    private var pending: Request? = null

    private inner class Request(val bridge: IPairingConsentGate, val id: Long,
        override val deadline: Long) : Consent {
        private val active = AtomicBoolean(true)
        override fun finish(accepted: Boolean) {
            if (!active.compareAndSet(true, false)) return
            val complete = Runnable {
                if (pending === this) pending = null
                runCatching { bridge.finish(id, accepted && !closed && remote === bridge &&
                    SystemClock.elapsedRealtime() < deadline) }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) complete.run() else handler.post(complete)
        }
    }

    private val owner = object : IPairingConsentOwner.Stub() {
        override fun onReady() {
            if (Binder.getCallingUid() != Process.myUid()) return
            val source = remote
            handler.post { if (!closed && source != null && remote === source) isWatching = true }
        }
        override fun onConsent(device: BluetoothDevice, receivedAt: Long, requestId: Long) {
            if (Binder.getCallingUid() != Process.myUid()) return
            val source = remote ?: return
            handler.post {
                val now = SystemClock.elapsedRealtime()
                if (closed || remote !== source || requestId <= 0 || receivedAt < 0 || receivedAt > now ||
                    now - receivedAt >= PairingConsentService.HOLD_MS) {
                    runCatching { source.finish(requestId, false) }
                    return@post
                }
                pending?.finish(false)
                val request = Request(source, requestId, receivedAt + PairingConsentService.HOLD_MS)
                pending = request
                runCatching { onConsent(device, receivedAt, request) }.onFailure { request.finish(false) }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closed) return
            val bridge = IPairingConsentGate.Stub.asInterface(service)
            remote = bridge
            runCatching { bridge.watch(modelId, owner) }.onFailure { close() }
        }
        override fun onServiceDisconnected(name: ComponentName) = close()
        override fun onBindingDied(name: ComponentName) = close()
        override fun onNullBinding(name: ComponentName) = close()
    }

    init {
        bound = runCatching { this.context.bindService(Intent(this.context, PairingConsentService::class.java),
            connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
    }

    override fun close() {
        closed = true
        isWatching = false
        val cleanup = Runnable {
            pending?.finish(false)
            remote?.let { runCatching { it.stop(owner) } }
            remote = null
            if (bound) runCatching { context.unbindService(connection) }
            bound = false
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run() else handler.post(cleanup)
    }
}
