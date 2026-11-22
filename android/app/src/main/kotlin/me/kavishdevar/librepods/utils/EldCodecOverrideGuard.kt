/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.AttributionSource
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.telecom.TelecomManager
import java.lang.reflect.Method

/** Captures identity at the incoming Binder method; stock authorization must still run. */
internal class EldCodecPreferenceBinderPlan(owner: Class<*>, stub: Class<*>, device: Class<*>,
    codec: Class<*>, attribution: Class<*>) {
    val preference: Method = exactEldMethod(owner, "setCodecConfigPreference", Void.TYPE,
        listOf(device, codec, attribution))
    init { require(stub.isAssignableFrom(owner)) { "Unknown A2DP Binder owner" } }

    companion object {
        fun discover(loader: ClassLoader, sdk: Int): EldCodecPreferenceBinderPlan {
            require(sdk == 37) { "Unknown A2DP Binder API" }
            return EldCodecPreferenceBinderPlan(
                Class.forName("com.android.bluetooth.a2dp.A2dpServiceBinder", false, loader),
                Class.forName("android.bluetooth.IBluetoothA2dp\$Stub", false, loader),
                Class.forName("android.bluetooth.BluetoothDevice", false, loader),
                Class.forName("android.bluetooth.BluetoothCodecConfig", false, loader),
                Class.forName("android.content.AttributionSource", false, loader))
        }
    }
}

internal class EldCodecOverrideGuard {
    data class Request(val peer: List<Int>, val codec: EldCodecIdentity.Fields)
    private val request = ThreadLocal<Request?>()
    val hasRequest: Boolean get() = request.get() != null

    fun <T> withRequest(value: Request?, proceed: () -> T): T {
        val previous = request.get()
        request.set(value)
        try { return proceed() }
        finally { if (previous == null) request.remove() else request.set(previous) }
    }

    /** Only the matching, already-authorized native call inside this Binder invocation is eligible. */
    fun shouldBlock(peer: List<Int>?, codecs: List<EldCodecIdentity.Fields?>,
        enabled: () -> Boolean, healthy: () -> Boolean, idle: () -> Boolean): Boolean {
        return runCatching {
            val current = request.get() ?: return false
            current.peer == peer && codecs.size == 1 && codecs.single() == current.codec &&
                forcedAac(current.codec) && enabled() && healthy() && idle()
        }.getOrDefault(false)
    }

    /** Who is calling: the Binder caller, the package that owns it, and what the attribution claims. */
    data class CallerIdentity(val callingUid: Int, val packageUid: Int, val system: Boolean, val packages: List<String>?,
        val attributionUid: Int?, val attributionPackage: String?, val delegated: Boolean)

    /** The arguments of one incoming setCodecConfigPreference Binder call. */
    data class BinderCall(val callingUid: Int, val device: BluetoothDevice?, val codec: Any?,
        val attribution: AttributionSource?)

    companion object {
        const val ACTOR = "com.google.android.apps.pixel.dcservice"
        /** android.os.Process.FIRST_APPLICATION_UID; lower UIDs belong to the system. */
        private const val FIRST_APPLICATION_UID = 10_000
        /** BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC. */
        private const val AAC_CODEC_TYPE = 1
        private const val AAC_EXTENDED_CODEC_ID = 2L
        /** BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST, which the Pixel service uses to force AAC. */
        private const val HIGHEST_CODEC_PRIORITY = 1_000_000
        private const val MAXIMUM_FAILURE_KIND_LENGTH = 96
        private const val MAXIMUM_FAILURE_MESSAGE_LENGTH = 160
        private const val BROADCAST_ADDRESS_BYTE = 255

        fun failure(error: Throwable): String {
            val kind = error.javaClass.name.take(MAXIMUM_FAILURE_KIND_LENGTH)
            val message = error.message.orEmpty().take(MAXIMUM_FAILURE_MESSAGE_LENGTH).map {
                if (Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() ||
                    it == '\u2028' || it == '\u2029') ' ' else it
            }.joinToString("").trim()
            return if (message.isEmpty()) kind else "$kind: $message"
        }
        fun forcedAac(codec: EldCodecIdentity.Fields) = codec.type == AAC_CODEC_TYPE &&
            codec.extendedCodecId == AAC_EXTENDED_CODEC_ID && codec.priority == HIGHEST_CODEC_PRIORITY

        /** The caller must be the one system app that owns its UID, with an undelegated attribution naming itself. */
        fun trustedCaller(caller: CallerIdentity): Boolean = with(caller) {
            val ownsItsUid = callingUid >= FIRST_APPLICATION_UID && callingUid == packageUid && packages == listOf(ACTOR)
            val attributionMatches = attributionUid == callingUid && attributionPackage == ACTOR && !delegated
            ownsItsUid && system && attributionMatches
        }

        fun peer(address: String?): List<Int>? = address?.takeIf {
            it.matches(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}"))
        }?.split(':')?.map { it.toInt(16) }?.takeIf { bytes ->
            bytes.any { it != 0 } && bytes.any { it != BROADCAST_ADDRESS_BYTE }
        }

        @SuppressLint("NewApi", "MissingPermission")
        fun capture(context: Context?, call: BinderCall, objects: EldCodecObjects): Request? {
            return runCatching {
                val attribution = call.attribution
                val device = call.device
                val codec = call.codec
                if (context == null || attribution == null || device == null || codec == null) return null
                val fields = objects.fields(codec)
                val attributionNamesCaller = attribution.uid == call.callingUid && attribution.packageName == ACTOR &&
                    attribution.next == null
                if (!forcedAac(fields) || !attributionNamesCaller) return null
                val manager = context.packageManager
                val app = manager.getApplicationInfo(ACTOR, 0)
                val system = app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val caller = CallerIdentity(call.callingUid, app.uid, system, manager.getPackagesForUid(call.callingUid)?.toList(),
                    attribution.uid, attribution.packageName, attribution.next != null)
                if (!trustedCaller(caller)) return null
                peer(device.address)?.let { Request(it, fields) }
            }.getOrNull()
        }

        @SuppressLint("MissingPermission")
        fun bonded(device: Any?): Boolean = (device as? BluetoothDevice)?.bondState == BluetoothDevice.BOND_BONDED

        @SuppressLint("MissingPermission")
        fun idle(context: Context?): Boolean {
            return runCatching {
                val audio = context?.getSystemService(AudioManager::class.java) ?: return false
                val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
                audio.mode == AudioManager.MODE_NORMAL && !audio.isBluetoothScoOn &&
                    audio.activeRecordingConfigurations.isEmpty() && !telecom.isInCall
            }.getOrDefault(false)
        }
    }
}
