package me.kavishdevar.librepods.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorPrivacyManager
import android.os.Binder
import android.os.Process

/** Read-only platform privacy checks; unavailable queries must block custom AACP microphone use. */
internal object ShellMicrophonePrivacy {
    @SuppressLint("SoonBlockedPrivateApi")
    fun isBlocked(context: Context): Boolean {
        check(Process.myUid() == 2000) { "Microphone privacy requires the Shizuku shell backend" }
        check(context.checkPermission("android.permission.OBSERVE_SENSOR_PRIVACY", Process.myPid(), Process.myUid()) ==
            PackageManager.PERMISSION_GRANTED) { "Shizuku cannot observe microphone privacy" }
        val token = Binder.clearCallingIdentity()
        try {
            val manager = checkNotNull(context.getSystemService(SensorPrivacyManager::class.java)) {
                "Sensor privacy service is unavailable"
            }
            val type = SensorPrivacyManager::class.java
            val all = type.getMethod("isAllSensorPrivacyEnabled").invoke(manager) as Boolean
            val query = type.getMethod("isSensorPrivacyEnabled", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val software = query.invoke(manager, SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE,
                SensorPrivacyManager.Sensors.MICROPHONE) as Boolean
            val hardware = query.invoke(manager, SensorPrivacyManager.TOGGLE_TYPE_HARDWARE,
                SensorPrivacyManager.Sensors.MICROPHONE) as Boolean
            return all || software || hardware
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }
}
