package me.kavishdevar.librepods.shizuku

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Cycles only the selected bonded AirPods' media profile, never ACL, other peers or global routing. */
internal class A2dpPlaybackRecovery(
    private val context: Context,
    address: String,
    private val owner: IPlaybackRecoveryOwner,
) : IBinder.DeathRecipient {
    private val guard = A2dpRecoveryGuard(address)
    fun cancel() = guard.cancel()
    override fun binderDied() = guard.ownerDied()

    private fun refreshOwner() {
        try {
            if (!owner.isCurrent) guard.cancel()
        } catch (_: Exception) {
            guard.ownerDied()
        }
    }

    @SuppressLint("MissingPermission", "SoonBlockedPrivateApi")
    fun run(): Boolean {
        for (permission in arrayOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_PRIVILEGED")) {
            check(context.checkPermission(permission, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED) {
                "Shizuku lacks $permission for selected-device playback recovery"
            }
        }
        val deadline = SystemClock.elapsedRealtime() + 5500
        val adapter = ShellBluetooth.adapter(context)
        check(adapter.isEnabled) { "Bluetooth is off" }
        val device = adapter.bondedDevices.singleOrNull { it.address == guard.address }
            ?: error("Selected AirPods are no longer bonded")
        check(A2dpRecoveryGuard.isAirPodsCandidate(device.bondState == BluetoothDevice.BOND_BONDED,
            device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC || device.type == BluetoothDevice.DEVICE_TYPE_DUAL,
            device.uuids?.map { it.uuid.toString() }.orEmpty())) { "Selected bonded device does not advertise the AirPods AACP service" }
        val disconnect = BluetoothA2dp::class.java.getDeclaredMethod("disconnect", BluetoothDevice::class.java)
            .apply { isAccessible = true }
        val connect = BluetoothA2dp::class.java.getDeclaredMethod("connect", BluetoothDevice::class.java)
            .apply { isAccessible = true }
        val latch = CountDownLatch(1)
        val proxyLock = Any()
        var closed = false
        var proxy: BluetoothA2dp? = null
        var connectIssued = false
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, service: BluetoothProfile) {
                val closeLate = synchronized(proxyLock) {
                    if (closed || profile != BluetoothProfile.A2DP || service !is BluetoothA2dp) true
                    else { proxy = service; false }
                }
                if (closeLate) adapter.closeProfileProxy(profile, service) else latch.countDown()
            }
            override fun onServiceDisconnected(profile: Int) { cancel(); latch.countDown() }
        }
        fun competing(profile: BluetoothA2dp) = profile.connectedDevices.any { it.address != guard.address }
        fun bonded() = device.bondState == BluetoothDevice.BOND_BONDED
        fun awaitState(profile: BluetoothA2dp, state: Int, until: Long): Boolean {
            while (SystemClock.elapsedRealtime() < until) {
                refreshOwner()
                if (!guard.mayReconnect(competing(profile)) || !bonded()) return false
                if (profile.getConnectionState(device) == state) return true
                Thread.sleep(40)
            }
            return false
        }
        owner.asBinder().linkToDeath(this, 0)
        try {
            refreshOwner()
            if (!guard.liveOwner()) return false
            check(adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)) { "A2DP proxy request was rejected" }
            if (!latch.await(minOf(1000L, deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS)) return false
            val profile = synchronized(proxyLock) { proxy } ?: return false
            val initial = profile.getConnectionState(device)
            if (initial != BluetoothProfile.STATE_CONNECTED && initial != BluetoothProfile.STATE_DISCONNECTED) return false
            guard.begin(initial == BluetoothProfile.STATE_CONNECTED)
            refreshOwner()
            if (!guard.liveOwner() || competing(profile) || !bonded()) return false
            if (initial == BluetoothProfile.STATE_CONNECTED) {
                if (!guard.mayDisconnect(competing(profile))) return false
                if (disconnect.invoke(profile, device) != true) return false
                guard.disconnectedByUs()
                if (!awaitState(profile, BluetoothProfile.STATE_DISCONNECTED, minOf(deadline, SystemClock.elapsedRealtime() + 1500))) return false
            }
            refreshOwner()
            if (!guard.mayReconnect(competing(profile)) || !bonded() || SystemClock.elapsedRealtime() >= deadline) return false
            connectIssued = connect.invoke(profile, device) == true
            if (!connectIssued) return false
            return awaitState(profile, BluetoothProfile.STATE_CONNECTED, deadline)
        } finally {
            // An owner that died after our disconnect gets only its exact prior pair's best-effort rollback.
            val profile = synchronized(proxyLock) { closed = true; proxy.also { proxy = null } }
            try {
                if (profile != null && !connectIssued) {
                    refreshOwner()
                    if (guard.mayRollback(competing(profile)) && bonded() &&
                        profile.getConnectionState(device) == BluetoothProfile.STATE_DISCONNECTED) {
                        runCatching { connect.invoke(profile, device) }
                    }
                }
            } finally {
                if (profile != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, profile)
                runCatching { owner.asBinder().unlinkToDeath(this, 0) }
            }
        }
    }
}
