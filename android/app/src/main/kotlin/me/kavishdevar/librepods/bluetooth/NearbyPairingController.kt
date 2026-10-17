/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.ble.NearbyAirPods
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.presentation.overlays.PopupWindow
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient

/** Keeps untrusted BLE invitations separate from bonded-device controls and battery state. */
@SuppressLint("MissingPermission")
class NearbyPairingController(
    private val context: Context,
    private val onInteractionChanged: (Int?) -> Unit = {},
    private val confirmConsent: (suspend (String, () -> Boolean) -> Boolean)? = null,
    private val consentGateFactory: (Int, (BluetoothDevice, Long, PairingConsentGate.Consent) -> Unit) -> PairingConsentWatch =
        { model, onConsent -> PairingConsentGate(context, model, onConsent) },
    private val onPaired: (BluetoothDevice) -> Unit = {}
) {
    private class ConsentHint(val receivedAt: Long, @Volatile var active: Boolean = true) {
        var pending: PairingConsentGate.Consent? = null
        var awaitingGate = false
        fun isCurrent(now: Long) = active && now >= receivedAt && now - receivedAt < CONSENT_HINT_MS
    }
    private class NativeConsent(val result: BroadcastReceiver.PendingResult, override val deadline: Long) : PairingConsentGate.Consent {
        override fun finish(accepted: Boolean) {
            if (accepted) result.abortBroadcast()
            result.finish()
        }
    }
    private class BondSelection(val device: BluetoothDevice, var bondingObserved: Boolean,
        var consentRequested: Boolean = false)
    private abstract class AttemptReceiver : BroadcastReceiver() {
        abstract fun retire()
    }
    private val handler = Handler(Looper.getMainLooper())
    private val session = NearbyAirPods.Session()
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private var popup: PopupWindow? = null
    @Volatile private var receiver: BroadcastReceiver? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var consentJob: Job? = null
    @Volatile private var pendingConsent: PairingConsentGate.Consent? = null
    private var consentFallback: Runnable? = null
    private var consentGate: PairingConsentWatch? = null
    private var client: ShizukuAudioClient? = null
    private var discoveryOwned = false
    private var timeout: Runnable? = null
    private var continuation: Runnable? = null
    private var selection: Runnable? = null
    private var requestedAt = 0L
    private var firstCandidateAt: Long? = null
    private var pairedAddress: String? = null
    private var batteryObservedAfter = 0L
    @Volatile private var closed = false
    @Volatile private var blocked = false

    fun onNearbyDevice(event: NearbyAirPods.Event, observedAtNanos: Long = SystemClock.elapsedRealtimeNanos()) {
        handler.post {
            if (closed || blocked) return@post
            if (!event.lidOpen) {
                if (session.modelId == event.modelId) dismiss()
                return@post
            }
            if (!Settings.canDrawOverlays(context)) return@post
            val token = session.open(event.modelId) ?: return@post
            val name = NearbyAirPods.modelNames[event.modelId] ?: return@post
            val radioAge = (SystemClock.elapsedRealtimeNanos() - observedAtNanos).coerceAtLeast(0L)
            // Round up the age so decoding the same invitation cannot miss this floor by 1 ms.
            batteryObservedAfter = System.currentTimeMillis() - radioAge / 1_000_000 -
                if (radioAge % 1_000_000 == 0L) 0 else 1
            popup = PopupWindow(context) {
                if (session.isCurrent(token)) dismiss()
            }.also {
                it.openPairing(name, event.modelId, observedAtNanos)
                Log.d(TAG, "Lid advertisement to pairing overlay: ${(SystemClock.elapsedRealtimeNanos() - observedAtNanos) / 1_000_000}ms")
                it.showPairingActions(instructions(R.string.pairing_nearby_hint), listOf(
                    context.getString(R.string.pairing_connect) to { discover(token) }
                ), stage = PopupWindow.Stage.NEARBY)
            }
        }
    }

    private fun discover(token: Long) {
        if (closed || blocked) return
        val now = SystemClock.elapsedRealtime()
        val attempt = session.beginAttempt(token, now) ?: return
        pairedAddress = null
        if (batteryObservedAfter == 0L) batteryObservedAfter = System.currentTimeMillis()
        stopDiscovery()
        requestedAt = now
        firstCandidateAt = null
        Log.i(TAG, "Connect requested")
        if (confirmConsent == null) runCatching {
            (client ?: ShizukuAudioClient(context).also { client = it }).connect()
        }
        onInteractionChanged(session.modelId)
        timeout = Runnable {
            if (session.isCurrentAttempt(token, attempt)) {
                stopDiscovery()
                showRetry(token, R.string.pairing_request_timeout)
            }
        }.also { handler.postDelayed(it, NearbyAirPods.Session.REQUEST_MS) }
        startScan(token, attempt)
    }

    private fun startScan(token: Long, attempt: Long) {
        if (!canContinue(token, attempt)) return
        stopScan()
        val model = session.modelId ?: return
        val devices = linkedMapOf<String, BluetoothDevice>()
        val candidates = NearbyAirPods.Discovery(model)
        val hints = linkedMapOf<String, ConsentHint>()
        val bondingSeen = mutableSetOf<String>()
        var selected: BondSelection? = null
        fun retireHint(hint: ConsentHint?) {
            hint?.let {
                it.active = false
                finishConsent(it.pending)
            }
        }
        fun select(device: BluetoothDevice) {
            val owner = receiver ?: return
            pair(token, attempt, device, owner, bondingSeen, hints) { selected = it }
        }
        var gateGeneration = 0
        fun startConsentGate() {
            consentGate?.close()
            val generation = ++gateGeneration
            val owner = receiver
            consentGate = consentGateFactory(model) { device, receivedAt, pending ->
                if (generation != gateGeneration || receiver !== owner || !canContinue(token, attempt)) {
                    pending.finish(false)
                } else try {
                    val now = SystemClock.elapsedRealtime()
                    if (!consentReady() || candidates.ambiguous || receivedAt > now || now >= pending.deadline ||
                        device.bondState != BluetoothDevice.BOND_BONDING ||
                        device.type !in setOf(BluetoothDevice.DEVICE_TYPE_CLASSIC, BluetoothDevice.DEVICE_TYPE_DUAL) ||
                        !NearbyAirPods.matchesName(model, device.name) ||
                        selected?.device?.address?.let { it != device.address } == true) {
                        pending.finish(false)
                    } else {
                        val hint = hints[device.address]
                        if (hint == null || receivedAt < hint.receivedAt || !hint.isCurrent(now) ||
                            hint.pending != null || pendingConsent != null || selected?.consentRequested == true) {
                            pending.finish(false)
                        } else {
                            hint.pending = pending
                            pendingConsent = pending
                            consentFallback = Runnable { finishConsent(pending) }
                                .also { handler.postDelayed(it, (pending.deadline - now).coerceAtLeast(0)) }
                            Log.i(TAG, "Incoming consent held for Classic selection: ${now - requestedAt}ms")
                            selected?.let { confirmSelectedConsent(token, attempt, requireNotNull(owner), it, hint) }
                        }
                    }
                } catch (error: SecurityException) {
                    pending.finish(false)
                    permissionDenied(token, error)
                }
            }
        }
        fun observe(device: BluetoothDevice, name: String?) {
            val now = SystemClock.elapsedRealtime()
            if (candidates.observe(device.address, name, now) && firstCandidateAt == null) {
                firstCandidateAt = now
                Log.i(TAG, "Connect to first Classic candidate: ${now - requestedAt}ms")
            }
            if (candidates.ambiguous) finishConsent()
            showInstructions(token, if (candidates.ambiguous) R.string.pairing_multiple_cases else null)
            scheduleSelection(token, attempt, devices, candidates, ::select)
        }
        try {
            if (adapter?.isEnabled != true) {
                showRetry(token, R.string.pairing_bluetooth_off)
                return
            }
            // A discovery started elsewhere belongs to Android or another app.
            if (adapter.isDiscovering) {
                showRetry(token, R.string.pairing_discovery_busy)
                return
            }
            receiver = object : AttemptReceiver() {
                override fun retire() {
                    hints.values.forEach { it.active = false }
                    hints.clear()
                    bondingSeen.clear()
                }
                override fun onReceive(context: Context, intent: Intent) {
                    if (!canContinue(token, attempt) || receiver !== this) return
                    try {
                        val changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        if (changed != null) {
                            val address = changed.address
                            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                                    BluetoothDevice.BOND_NONE -> {
                                        val hint = hints.remove(address)
                                        retireHint(hint)
                                        if (hint != null && selected == null) startConsentGate()
                                        bondingSeen.remove(address)
                                    }
                                    BluetoothDevice.BOND_BONDING -> if (bondingSeen.size < 16 || address in bondingSeen) {
                                        if (bondingSeen.add(address)) retireHint(hints.remove(address))
                                    }
                                }
                            } else if (intent.action == BluetoothDevice.ACTION_PAIRING_REQUEST) {
                                val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
                                val consent = variant == 3
                                val nativeState = if (consent) changed.bondState else null
                                Log.i(TAG, "Pairing request: variant=$variant state=$nativeState selected=${selected?.device?.address == address} after=${SystemClock.elapsedRealtime() - requestedAt}ms")
                                if (!consent) {
                                    retireHint(hints[address])
                                    if (hints.size < 16) hints.putIfAbsent(address, ConsentHint(SystemClock.elapsedRealtime(), false))
                                    if (selected?.device?.address == address) {
                                        consentJob?.cancel()
                                        consentJob = null
                                        consentFallback?.let(handler::removeCallbacks)
                                        consentFallback = null
                                        finishConsent()
                                    }
                                } else if (nativeState == BluetoothDevice.BOND_BONDING &&
                                    (hints.size < 16 || address in hints) &&
                                    (bondingSeen.size < 16 || address in bondingSeen)) {
                                    bondingSeen.add(address)
                                    if (hints.putIfAbsent(address, ConsentHint(SystemClock.elapsedRealtime()).also {
                                        it.awaitingGate = isOrderedBroadcast && consentGate?.isWatching == true && consentReady() &&
                                            changed.type in setOf(BluetoothDevice.DEVICE_TYPE_CLASSIC, BluetoothDevice.DEVICE_TYPE_DUAL) &&
                                            NearbyAirPods.matchesName(model, changed.name)
                                    }) == null) {
                                        Log.i(TAG, "Captured incoming consent: ${SystemClock.elapsedRealtime() - requestedAt}ms")
                                    }
                                }
                            }
                        }
                        selected?.let { selection ->
                            handleBondEvent(token, attempt, this, selection, intent, hints[selection.device.address])
                            return
                        }
                        when (intent.action) {
                            BluetoothDevice.ACTION_FOUND -> {
                                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                                    ?: return
                                val type = if (Build.VERSION.SDK_INT >= 37 || intent.hasExtra(BluetoothDevice.EXTRA_DISCOVERY_RESULT_TYPE)) intent.getIntExtra(
                                    BluetoothDevice.EXTRA_DISCOVERY_RESULT_TYPE, BluetoothDevice.DEVICE_TYPE_UNKNOWN)
                                    else device.type
                                val bondState = device.bondState
                                if ((bondState !in setOf(BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDING) &&
                                    !(bondState == BluetoothDevice.BOND_BONDED && device.address in bondingSeen)) ||
                                    type !in setOf(BluetoothDevice.DEVICE_TYPE_CLASSIC, BluetoothDevice.DEVICE_TYPE_DUAL)) return
                                if (bondState == BluetoothDevice.BOND_BONDING && bondingSeen.size < 16) bondingSeen.add(device.address)
                                // Keep unnamed Classic results so their later name event can finish this scan.
                                devices[device.address] = device
                                observe(device, intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: device.name)
                            }
                            BluetoothDevice.ACTION_NAME_CHANGED -> {
                                val changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                                    ?: return
                                val device = devices[changed.address] ?: return
                                observe(device, intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: device.name)
                            }
                            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                                // A late completion from an earlier scan must not finish this one.
                                if (!adapter.isDiscovering) {
                                    discoveryOwned = false
                                    finishDiscovery(token, attempt, devices, candidates, ::select)
                                }
                            }
                        }
                    } catch (e: SecurityException) {
                        permissionDenied(token, e)
                    }
                }
            }
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_NAME_CHANGED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
            }, Context.RECEIVER_EXPORTED)
            startConsentGate()
            discoveryOwned = adapter.startDiscovery()
            if (!discoveryOwned) {
                scheduleScan(token, attempt, R.string.pairing_discovery_failed)
                return
            }
            showInstructions(token)
            continuation = Runnable { finishDiscovery(token, attempt, devices, candidates, ::select) }
                .also { handler.postDelayed(it, minOf(15_000L, session.remainingMillis(token, attempt, SystemClock.elapsedRealtime()))) }
        } catch (e: SecurityException) {
            Log.w(TAG, "Nearby pairing requires Bluetooth permission", e)
            stopDiscovery()
            showRetry(token, R.string.pairing_permission_needed)
        }
    }

    private fun finishDiscovery(token: Long, attempt: Long, devices: Map<String, BluetoothDevice>, candidates: NearbyAirPods.Discovery,
        onSelect: (BluetoothDevice) -> Unit) {
        if (!canContinue(token, attempt)) return
        stopScan(keepReceiver = receiver)
        candidates.finish()
        Log.i(TAG, "Classic discovery finished with ${devices.size} fresh Classic devices")
        if (candidates.readyInMillis(SystemClock.elapsedRealtime()) != null) {
            scheduleSelection(token, attempt, devices, candidates, onSelect)
        } else {
            scheduleScan(token, attempt, if (candidates.ambiguous) R.string.pairing_multiple_cases else null)
        }
    }

    private fun scheduleSelection(token: Long, attempt: Long, devices: Map<String, BluetoothDevice>, candidates: NearbyAirPods.Discovery,
        onSelect: (BluetoothDevice) -> Unit) {
        selection?.let(handler::removeCallbacks)
        selection = null
        val remaining = candidates.readyInMillis(SystemClock.elapsedRealtime()) ?: return
        selection = Runnable {
            selection = null
            if (canContinue(token, attempt)) {
                val now = SystemClock.elapsedRealtime()
                val remaining = candidates.readyInMillis(now)
                Log.i(TAG, "Connect to candidate check: ${now - requestedAt}ms remaining=$remaining")
                if (remaining != null && remaining > 0) {
                    Log.i(TAG, "Classic selection rearmed with ${remaining}ms remaining")
                    scheduleSelection(token, attempt, devices, candidates, onSelect)
                } else if (remaining == 0L) {
                    candidates.automatic(now)?.let(devices::get)?.let(onSelect)
                }
            }
        }.also { handler.postDelayed(it, remaining) }
    }

    private fun pair(token: Long, attempt: Long, device: BluetoothDevice, owner: BroadcastReceiver,
        bondingSeen: MutableSet<String>, hints: MutableMap<String, ConsentHint>, select: (BondSelection) -> Unit) {
        if (!canContinue(token, attempt) || receiver !== owner) return
        try {
            val bondState = device.bondState
            if ((bondState !in setOf(BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDING) &&
                !(bondState == BluetoothDevice.BOND_BONDED && device.address in bondingSeen)) ||
                device.name?.let { !NearbyAirPods.matchesName(session.modelId ?: 0, it) } == true) {
                showRetry(token, R.string.pairing_device_changed)
                return
            }
            if (pendingConsent !== hints[device.address]?.pending) finishConsent()
            if (bondState != BluetoothDevice.BOND_BONDING) hints.remove(device.address)?.let {
                it.active = false
                finishConsent(it.pending)
            }
            if (bondState == BluetoothDevice.BOND_NONE) bondingSeen.remove(device.address)
            val selected = BondSelection(device, bondState == BluetoothDevice.BOND_BONDING ||
                bondState == BluetoothDevice.BOND_BONDED && device.address in bondingSeen)
            select(selected)
            // Keep the attempt's bond receiver across selection; only retire its inquiry and timers.
            stopScan(keepReceiver = owner)
            val currentState = device.bondState
            if (currentState == BluetoothDevice.BOND_NONE && bondState != BluetoothDevice.BOND_NONE) {
                hints.remove(device.address)?.active = false
                bondingSeen.remove(device.address)
                scheduleScan(token, attempt, R.string.pairing_bond_failed)
                return
            }
            val hint = hints[device.address]?.takeIf {
                bondState == BluetoothDevice.BOND_BONDING && currentState == BluetoothDevice.BOND_BONDING
            }
            Log.i(TAG, "Selected bond: initial=$bondState current=$currentState consentAge=${hint?.let { SystemClock.elapsedRealtime() - it.receivedAt }} consentActive=${hint?.active}")
            if (currentState == BluetoothDevice.BOND_NONE) {
                Log.i(TAG, "Connect to createBond: ${SystemClock.elapsedRealtime() - requestedAt}ms")
                val started = if (Build.VERSION.SDK_INT >= 37) device.createBond(BluetoothDevice.TRANSPORT_BREDR)
                    else device.createBond()
                if (!started && device.bondState != BluetoothDevice.BOND_BONDING) {
                    scheduleScan(token, attempt, R.string.pairing_bond_failed)
                    return
                }
            } else Log.i(TAG, "Adopted incoming bond: ${SystemClock.elapsedRealtime() - requestedAt}ms")
            showInstructions(token, R.string.pairing_confirm_system)
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> if (selected.bondingObserved) completeBond(token, attempt, device)
                BluetoothDevice.BOND_BONDING -> {
                    selected.bondingObserved = true
                    if (hint != null) confirmSelectedConsent(token, attempt, owner, selected, hint)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Pairing permission denied", e)
            stopDiscovery()
            showRetry(token, R.string.pairing_permission_needed)
        }
    }

    private fun handleBondEvent(token: Long, attempt: Long, owner: BroadcastReceiver, selected: BondSelection,
        intent: Intent, hint: ConsentHint?) {
        val device = selected.device
        val changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        if (changed?.address != device.address) return
        if (intent.action == BluetoothDevice.ACTION_PAIRING_REQUEST) {
            if (intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR) == 3 && hint != null) {
                confirmSelectedConsent(token, attempt, owner, selected, hint) {
                    if (owner.isOrderedBroadcast) owner.goAsync() else null
                }
            }
            return
        }
        if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
            BluetoothDevice.BOND_BONDING -> {
                selected.bondingObserved = true
                Log.i(TAG, "Connect to BONDING: ${SystemClock.elapsedRealtime() - requestedAt}ms")
            }
            BluetoothDevice.BOND_BONDED -> if (selected.bondingObserved && device.bondState == BluetoothDevice.BOND_BONDED) {
                completeBond(token, attempt, device)
            }
            BluetoothDevice.BOND_NONE -> if (selected.bondingObserved) {
                scheduleScan(token, attempt, R.string.pairing_bond_failed)
            }
        }
    }

    private fun confirmSelectedConsent(token: Long, attempt: Long, owner: BroadcastReceiver,
        selected: BondSelection, hint: ConsentHint,
        hold: () -> BroadcastReceiver.PendingResult? = { null }) {
        val device = selected.device
        if (selected.consentRequested || !hint.isCurrent(SystemClock.elapsedRealtime()) ||
            device.bondState != BluetoothDevice.BOND_BONDING) return
        // Let the worker capture this request; a lost callback must not strand consent.
        if (hint.pending == null && hint.awaitingGate) {
            hint.awaitingGate = false
            handler.postDelayed({
                if (receiver === owner && canContinue(token, attempt)) {
                    confirmSelectedConsent(token, attempt, owner, selected, hint)
                }
            }, 100)
            return
        }
        selected.consentRequested = true
        val incoming = hint.pending?.takeIf { pendingConsent === it }
        val consentDeadline = minOf(hint.receivedAt + CONSENT_HINT_MS, SystemClock.elapsedRealtime() + 1_500,
            incoming?.deadline ?: Long.MAX_VALUE)
        val pending = incoming ?: hold()?.let { NativeConsent(it, consentDeadline) }
        if (pending != null) pendingConsent = pending
        consentFallback?.let(handler::removeCallbacks)
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            var confirmed = false
            val stillCurrent = {
                receiver === owner && canContinue(token, attempt) && hint.isCurrent(SystemClock.elapsedRealtime()) &&
                    SystemClock.elapsedRealtime() < consentDeadline &&
                    (pending == null || pendingConsent === pending) &&
                    runCatching { device.bondState == BluetoothDevice.BOND_BONDING }.getOrDefault(false)
            }
            try {
                if (stillCurrent()) {
                    confirmed = confirmConsent?.invoke(device.address, stillCurrent)
                        ?: client?.confirmPairing(device.address, stillCurrent) ?: false
                    Log.i(TAG, "Consent confirmation available: $confirmed after ${SystemClock.elapsedRealtime() - requestedAt}ms")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w(TAG, "Consent confirmation unavailable", error)
            } finally {
                handler.post {
                    if (consentJob === job) {
                        consentJob = null
                        consentFallback?.let(handler::removeCallbacks)
                        consentFallback = null
                    }
                    if (pending != null) finishConsent(pending,
                        confirmed && receiver === owner && canContinue(token, attempt) &&
                            hint.isCurrent(SystemClock.elapsedRealtime()) && SystemClock.elapsedRealtime() < consentDeadline)
                }
            }
        }
        consentJob = job
        consentFallback = Runnable {
            if (consentJob === job) {
                hint.active = false
                job.cancel()
                consentJob = null
                consentFallback = null
                if (pending != null) finishConsent(pending)
            }
        }.also { handler.postDelayed(it, (consentDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)) }
        job.start()
    }

    private fun consentReady() = confirmConsent != null || client?.canConfirmPairing() == true

    private fun completeBond(token: Long, attempt: Long, device: BluetoothDevice) {
        Log.i(TAG, "Connect to BONDED: ${SystemClock.elapsedRealtime() - requestedAt}ms")
        finishConsent(accepted = true)
        stopDiscovery()
        pairedAddress = device.address
        popup?.showPairingActions(context.getString(R.string.pairing_complete), listOf(
            context.getString(R.string.pairing_continue) to { if (session.isCurrentAttempt(token, attempt)) dismiss() }
        ), busy = false, stage = PopupWindow.Stage.COMPLETE)
        onPaired(device)
    }

    private fun showRetry(token: Long, message: Int) {
        if (!session.isCurrent(token)) return
        pairedAddress = null
        batteryObservedAfter = 0L
        stopDiscovery()
        onInteractionChanged(null)
        popup?.showPairingActions(instructions(message), listOf(
            context.getString(R.string.pairing_retry) to { discover(token) }
        ), stage = PopupWindow.Stage.RETRY)
    }

    private fun instructions(message: Int? = null): String {
        val gesture = if (NearbyAirPods.usesDoubleTap(session.modelId ?: 0)) R.string.pairing_double_tap else R.string.pairing_hold_button
        return context.getString(gesture) + (message?.let { "\n\n${context.getString(it)}" } ?: "")
    }

    private fun showInstructions(token: Long, message: Int? = null) {
        if (!session.isCurrent(token)) return
        val connecting = message == R.string.pairing_confirm_system
        popup?.showPairingActions(
            if (connecting) context.getString(R.string.pairing_confirm_system) else instructions(message), emptyList(),
            stage = if (connecting) PopupWindow.Stage.CONNECTING else PopupWindow.Stage.SETUP)
    }

    private fun scheduleScan(token: Long, attempt: Long, message: Int?) {
        if (!canContinue(token, attempt)) return
        stopScan()
        showInstructions(token, message)
        continuation = Runnable { startScan(token, attempt) }.also { handler.postDelayed(it, NearbyAirPods.Discovery.STABLE_MS) }
    }

    private fun canContinue(token: Long, attempt: Long): Boolean = !closed && !blocked &&
        session.remainingMillis(token, attempt, SystemClock.elapsedRealtime()) > 0

    private fun permissionDenied(token: Long, error: SecurityException) {
        Log.w(TAG, "Bluetooth permission revoked during pairing", error)
        stopDiscovery()
        showRetry(token, R.string.pairing_permission_needed)
    }

    private fun stopDiscovery() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        stopScan()
    }

    private fun stopScan(keepReceiver: BroadcastReceiver? = null) {
        val oldReceiver = receiver
        receiver = keepReceiver
        if (oldReceiver !== keepReceiver) (oldReceiver as? AttemptReceiver)?.retire()
        if (keepReceiver == null || oldReceiver !== keepReceiver) {
            consentJob?.cancel()
            consentJob = null
            consentFallback?.let(handler::removeCallbacks)
            consentFallback = null
            finishConsent()
            consentGate?.close()
            consentGate = null
        }
        selection?.let(handler::removeCallbacks)
        selection = null
        continuation?.let(handler::removeCallbacks)
        continuation = null
        oldReceiver?.takeIf { it !== keepReceiver }?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Pairing receiver was not registered", e)
            }
        }
        if (discoveryOwned) {
            discoveryOwned = false
            try {
                adapter?.cancelDiscovery()
            } catch (e: SecurityException) {
                Log.w(TAG, "Bluetooth permission revoked during discovery", e)
            }
        }
    }

    /** PendingResult is completed only on the main thread, once, including cancellation. */
    private fun finishConsent(expected: PairingConsentGate.Consent? = pendingConsent, accepted: Boolean = false) {
        if (expected == null || pendingConsent !== expected) return
        pendingConsent = null
        consentFallback?.let(handler::removeCallbacks)
        consentFallback = null
        expected.finish(accepted)
    }

    @androidx.annotation.MainThread
    fun updatePairingBattery(device: BluetoothDevice, batteries: Set<Battery>) {
        if (closed || blocked || pairedAddress != device.address) return
        if (runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)) {
            // Owned reports from this invitation can be verified after the bond completes.
            popup?.updatePairingBattery(batteries.filter { (it.observedAt ?: Long.MIN_VALUE) >= batteryObservedAfter }.toSet())
        } else dismiss()
    }

    private fun dismiss() {
        pairedAddress = null
        batteryObservedAfter = 0L
        session.close()
        stopDiscovery()
        onInteractionChanged(null)
        val old = popup
        popup = null
        if (closed) old?.forceClose() else old?.close()
    }

    fun close() {
        closed = true
        dismiss()
        scope.cancel()
        client?.close()
        client = null
    }

    fun cancelPending() { handler.post { dismiss() } }

    fun setBlocked(value: Boolean) {
        blocked = value
        handler.post {
            session.setBlocked(value)
            if (value) dismiss()
        }
    }

    private companion object {
        const val TAG = "NearbyPairing"
        const val CONSENT_HINT_MS = 15_000L
    }
}
