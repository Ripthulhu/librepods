package me.kavishdevar.librepods.services

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import me.kavishdevar.librepods.bluetooth.hasNearbyDevicesPermissions
import me.kavishdevar.librepods.bluetooth.nearbyScanSettings

private const val TAG = "LibrePodsService"
private const val NEARBY_SCAN_TAG = "NearbyScan"

/**
 * Keeps one Apple manufacturer-data BLE scan registered while requested: low latency while the screen is on,
 * the preferred mode once it has been off for 30 s, at most five registrations per rolling 30 s, and a 30 s
 * retry after a failure. Results are delivered on [handler]'s (main) thread.
 */
@SuppressLint("MissingPermission")
internal class BleScanController(
    private val context: Context,
    private val handler: Handler,
    private val destroying: () -> Boolean,
    /** Microphone capture currently blocks BLE work. */
    private val captureBlocked: () -> Boolean,
    /** The user's scan mode for a non-interactive screen. */
    private val preferredMode: () -> Int,
    /** Nearby-devices permission was revoked; the service stops itself. */
    private val onPermissionLost: () -> Unit,
    private val onResult: (ScanResult) -> Unit,
) {
    private var scanGeneration = 0L
    /** Whether the service wants a scan; it survives temporary blocks and failures. */
    var requested = false
        private set
    /** Set once the saved devices are loaded; nothing is registered before that. */
    var ready = false
    private var activeScanner: BluetoothLeScanner? = null
    private var activeCallback: ScanCallback? = null
    private var activeMode: Int? = null
    private val scanStarts = ArrayDeque<Long>()
    private var retry: Runnable? = null
    private val updateScreenMode = Runnable { if (requested) start(updateMode = true) }

    fun onScreenChanged(on: Boolean) {
        handler.removeCallbacks(updateScreenMode)
        if (requested) {
            // A quick lock/unlock keeps the working scan instead of spending two registrations.
            if (on) start(updateMode = true)
            else handler.postDelayed(updateScreenMode, 30_000)
        }
    }

    private fun scheduleRetry(delayMs: Long, updateMode: Boolean = false) {
        retry?.let(handler::removeCallbacks)
        retry = Runnable {
            retry = null
            if (requested && !destroying()) start(updateMode)
        }.also { handler.postDelayed(it, delayMs) }
    }

    fun start(updateMode: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { start(updateMode) }
            return
        }
        if (destroying()) return
        requested = true
        if (captureBlocked() || !ready) return
        if (!hasNearbyDevicesPermissions(context)) { stopActive(); onPermissionLost(); return }
        val interactive = context.getSystemService(PowerManager::class.java).isInteractive
        val settings = nearbyScanSettings(interactive, preferredMode())
        val mode = settings.scanMode
        if (activeCallback != null && (!updateMode || activeMode == mode)) {
            retry?.let(handler::removeCallbacks)
            retry = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        while (scanStarts.firstOrNull()?.let { now - it >= 30_000 } == true) scanStarts.removeFirst()
        if (scanStarts.size >= 5) {
            // Keep a healthy scan running until Android's rolling registration allowance recovers.
            scheduleRetry(30_000 - (now - scanStarts.first()) + 1, updateMode)
            return
        }
        val scanner = try {
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return
            if (!adapter.isEnabled) return
            adapter.bluetoothLeScanner ?: return
        } catch (_: SecurityException) { stopActive(); onPermissionLost(); return }
        stopActive()
        val generation = ++scanGeneration
        val filter = ScanFilter.Builder().setManufacturerData(0x004c, byteArrayOf()).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handler.post {
                    if (!destroying() && generation == scanGeneration) try { onResult(result) }
                    catch (_: SecurityException) { stopActive(); onPermissionLost() }
                }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.sortedBy { it.timestampNanos }.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }
            override fun onScanFailed(errorCode: Int) {
                handler.post {
                    if (generation != scanGeneration || destroying()) return@post
                    stopActive()
                    Log.w(TAG, "BLE scan failed: $errorCode")
                    if (errorCode != ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED) scheduleRetry(30_000)
                }
            }
        }
        activeScanner = scanner
        activeCallback = callback
        activeMode = mode
        scanStarts.addLast(now)
        try {
            scanner.startScan(listOf(filter), settings, callback)
            if (Log.isLoggable(NEARBY_SCAN_TAG, Log.DEBUG)) Log.d(NEARBY_SCAN_TAG, "BLE lid monitoring started: mode=$mode, reportDelay=0")
        } catch (_: SecurityException) {
            stopActive()
            onPermissionLost()
            Log.w(TAG, "Nearby devices permission is unavailable")
        } catch (_: Exception) {
            stopActive()
            scheduleRetry(30_000)
        }
    }

    fun stopActive() {
        retry?.let(handler::removeCallbacks)
        retry = null
        handler.removeCallbacks(updateScreenMode)
        scanGeneration++
        val scanner = activeScanner
        val callback = activeCallback
        activeScanner = null
        activeCallback = null
        activeMode = null
        if (scanner != null && callback != null) runCatching { scanner.stopScan(callback) }
    }

    /** Withdraws the request and the active scan. Main thread only. */
    fun stop() {
        requested = false
        stopActive()
    }
}
