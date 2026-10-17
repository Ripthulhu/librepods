package me.kavishdevar.librepods.shizuku

import android.content.Context
import android.os.Looper
import android.os.Process
import me.kavishdevar.librepods.BuildConfig
import org.json.JSONObject

/** Runs the identical capability probe through ADB without replacing the installed application. */
object ShellAudioDiagnostics {
    @JvmStatic fun main(args: Array<String>) {
        var success = false
        val report = try {
            check(Process.myUid() == 2000 && args.isEmpty()) { "Run this diagnostic with the ADB shell identity and no arguments" }
            if (Looper.myLooper() == null) {
                @Suppress("DEPRECATION")
                if (Looper.getMainLooper() == null) Looper.prepareMainLooper() else Looper.prepare()
            }
            val type = Class.forName("android.app.ActivityThread")
            val thread = type.getMethod("systemMain").invoke(null)
            val base = type.getMethod("getSystemContext").invoke(thread) as Context
            val appUid = base.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid
            val context = ShellAudioContext(base).also { it.installProcessAttribution() }
            AudioDiagnostics(context, AudioRouting(context), appUid).run().also { success = true }
        } catch (error: Throwable) {
            var cause = error
            while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
            JSONObject().put("status", "blocked").put("detail", "${cause.javaClass.simpleName}: ${cause.message.orEmpty()}").toString()
        }
        println(report)
        kotlin.system.exitProcess(if (success) 0 else 1)
    }
}
