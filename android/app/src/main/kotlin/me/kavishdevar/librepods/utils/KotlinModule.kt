package me.kavishdevar.librepods.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageView
import androidx.core.net.toUri
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "LibrePodsHook"

@SuppressLint("DiscouragedApi", "PrivateApi")
class KotlinModule: XposedModule() {
    private var eldJavaHooksInstalled = false
    private var eldAudioFormatHook: XposedInterface.HookHandle? = null
    private val eldAudioFormatObserved = AtomicBoolean(false)
    private val gameBridgeInstalled = AtomicBoolean(false)
    @Volatile private var gameBridgeContext: Context? = null
    private var gameBridge: AutoCloseable? = null
    private val hfpBridgeInstalled = AtomicBoolean(false)
    private var hfpBridgeContext: Context? = null
    private var hfpBridge: AutoCloseable? = null
    @Volatile private var hfpControl: HfpCallSuppression? = null
    private val hfpBatteryHooksInstalled = AtomicBoolean(false)
    private var usbVolumeInstalled = false
    private var usbVolumePolicy: UsbAbsoluteVolumeSystemPolicy? = null
    private var usbVolumeDiscovery: AutoCloseable? = null
    private var usbVolumeFailureLogged = false
    private var moduleProcess = ""
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        moduleProcess = param.processName
        log(Log.INFO, TAG, "module initialized at :: ${param.processName}")
        log(Log.INFO, TAG, "framework: $frameworkName($frameworkVersionCode) API $apiVersion")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        installUsbVolumePolicy(param.classLoader)
        if (eldAudioFormatHook != null) return
        val method = EldAudioFormatBridge.discover(android.os.Build.VERSION.SDK_INT,
            "system_server", param.classLoader) ?: return
        try {
            eldAudioFormatHook = hook(method).intercept { chain ->
                val codecId = chain.args.firstOrNull() as? Long
                val original = chain.proceed()
                if (codecId == null || original !is Int) return@intercept original
                val mapped = EldAudioFormatBridge.map(codecId, original, enabled = true)
                if (mapped != original && eldAudioFormatObserved.compareAndSet(false, true))
                    log(Log.INFO, TAG, "Apple codec mapped to native AAC-ELD audio format")
                mapped
            }
            // The mapping is a format definition, independent of startup codec opt-in. An
            // already published Apple identity still needs it during disable/recovery.
            log(Log.INFO, TAG, "SDK37 Apple audio-format bridge installed")
        } catch (_: Throwable) {
            log(Log.WARN, TAG, "Audio-format bridge unavailable; keeping framework mapping")
        }
    }

    private fun installUsbVolumePolicy(loader: ClassLoader) {
        if (usbVolumeInstalled) return
        // The system-server callback provides the scope; package process labels may say android.
        val methods = UsbAbsoluteVolumePolicyHooks.discover(android.os.Build.VERSION.SDK_INT, "system_server", loader,
            ::usbVolumeFailure) ?: return
        val installed = mutableListOf<XposedInterface.HookHandle>()
        var ready = false
        try {
            fun usb(value: Any?): Boolean = value != null && runCatching {
                val type = value.javaClass.getMethod("getType").invoke(value) as Int
                val role = value.javaClass.getMethod("getRole").invoke(value) as Int
                role == 2 && type in me.kavishdevar.librepods.shizuku.UsbAbsoluteVolumeBroker.USB_TYPES
            }.getOrDefault(false)
            for (method in listOf(methods.register, methods.unregister, methods.behavior)) {
                installed += hook(method).intercept { chain ->
                    val policy = usbVolumePolicy
                    val device = chain.args.getOrNull(if (method == methods.register) 3 else 0)
                    if (policy != null && !UsbAbsoluteVolumeSystemPolicy.isOwnOperation() && usb(device) && policy.mayMutate() &&
                        !policy.invalidate("foreign_volume_policy")) null else chain.proceed()
                }
            }
            installed += hook(methods.wiredDevice).intercept { chain ->
                val policy = usbVolumePolicy
                if (policy != null && usb(chain.args.firstOrNull()) && policy.mayMutate() &&
                    !policy.invalidate("usb_topology_changed") && chain.args.getOrNull(1) == 1) null else chain.proceed()
            }
            installed += hook(methods.nativePolicy).intercept { chain ->
                val policy = usbVolumePolicy
                val type = chain.args.firstOrNull() as? Int
                if (policy != null && type != null && !UsbAbsoluteVolumeSystemPolicy.isOwnOperation() && policy.ownsNativeType(type))
                    policy.foreignNativePolicy() else chain.proceed()
            }
            installed += hook(methods.audioServerDied).intercept { chain ->
                usbVolumePolicy?.invalidate("audio_server_restarted")
                val result = chain.proceed()
                usbVolumePolicy?.invalidate("audio_server_restarted")
                result
            }
            installed += hook(methods.ready).intercept { chain ->
                val result = chain.proceed()
                var stage = "AudioService context"
                if (ready && usbVolumePolicy == null && chain.thisObject != null) runCatching {
                    val service = chain.thisObject!!
                    val context = methods.ready.declaringClass.getDeclaredField("mContext").apply { isAccessible = true }.get(service) as Context
                    stage = "policy constructor"
                    val policy = UsbAbsoluteVolumeSystemPolicy(context, service, methods)
                    stage = "discovery registration"
                    val discovery = UsbAbsoluteVolumeTransport.install(context, policy, moduleApplicationInfo.uid)
                    if (discovery == null) {
                        policy.close()
                        usbVolumeFailure(stage, IllegalStateException("Discovery identity or permission guard"))
                    } else {
                        usbVolumePolicy = policy; usbVolumeDiscovery = discovery
                        log(Log.INFO, TAG, "Owned USB volume policy ready; inactive until explicit enable")
                    }
                }.onFailure { usbVolumeFailure(stage, it) }
                result
            }
            ready = true; usbVolumeInstalled = true
        } catch (failure: Throwable) {
            ready = false
            installed.asReversed().forEach { runCatching { it.unhook() } }
            usbVolumeFailure("hook installation", failure)
        }
    }

    private fun usbVolumeFailure(stage: String, failure: Throwable) {
        if (usbVolumeFailureLogged) return
        usbVolumeFailureLogged = true
        val detail = "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}".replace('\n', ' ').take(180)
        log(Log.WARN, TAG, "USB volume unavailable at $stage ($detail)")
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName == "com.google.android.bluetooth" || param.packageName == "com.android.bluetooth") {
            log(Log.INFO, TAG, "Bluetooth app detected, hooking l2c_fcr_chk_chan_modes")
            try {
                if (param.isFirstPackage) {
                    installHfpBatteryNegotiation(param.defaultClassLoader)
                    installHfpCallBridge(param.defaultClassLoader)
                    val abi = android.os.Build.SUPPORTED_ABIS.first()
                    val soName = "libfluoride_hooks.so"

                    val candidates = buildList {
                        add("${moduleApplicationInfo.sourceDir}!/lib/$abi/$soName")

                        moduleApplicationInfo.splitSourceDirs?.forEach { split ->
                            add("$split!/lib/$abi/$soName")
                        }
                    }

                    var loaded = false

                    for (path in candidates) {
                        try {
                            if (Log.isLoggable(TAG, Log.DEBUG)) log(Log.DEBUG, TAG, "Trying to load native lib from $path")
                            System.load(path)
                            loaded = true
                            break
                        } catch (e: Throwable) {
                            log(Log.WARN, TAG, "Failed to load from $path: ${e.message}")
                        }
                    }

                    if (!loaded) {
                        log(Log.ERROR, TAG, "Could not load $soName from base or splits")
                        return
                    }

                    val remotePrefValue = getRemotePreferences("me.kavishdevar.librepods").getBoolean("vendor_id_hook", false)
                    log(Log.INFO, TAG, "sdp hook enabled (remote pref): $remotePrefValue")
                    NativeBridge.setSdpHook(remotePrefValue)
                    log(Log.INFO, TAG, "Native library loaded successfully")

                    // A codec failure must not undo the independent, existing identity hook.
                    installEldCodecBridge(param.defaultClassLoader,
                        runCatching { getRemotePreferences("me.kavishdevar.librepods")
                            .getBoolean("aac_eld_hook", false) }.getOrDefault(false))
                    installNativeGameBridge(param.defaultClassLoader)
                }
            } catch (e: Exception) {
                log(Log.ERROR, TAG, "Failed to load native library: ${e.message}")
            }
        }

        if (param.packageName == "com.google.android.settings") {
            hookSettingsController(param, "com.google.android.settings.bluetooth.AdvancedBluetoothDetailsHeaderController")
        }

        if (param.packageName == "com.android.settings") {
            hookSettingsController(param, "com.android.settings.bluetooth.AdvancedBluetoothDetailsHeaderController")
        }
    }

    private fun installHfpBatteryNegotiation(loader: ClassLoader) {
        if (android.os.Build.VERSION.SDK_INT != 37 || !hfpBatteryHooksInstalled.compareAndSet(false, true)) return
        val handles = mutableListOf<XposedInterface.HookHandle>()
        val ready = AtomicBoolean(false)
        try {
            val state = Class.forName("com.android.bluetooth.hfp.HeadsetStateMachine", false, loader)
            val native = Class.forName("com.android.bluetooth.hfp.HeadsetNativeInterface", false, loader)
            val methods = HfpBatteryHookMethods.discover(state, native)
            val negotiation = HfpBatteryNegotiation()
            val observed = AtomicBoolean(false)
            handles += hook(methods.exchange).intercept { chain ->
                if (!ready.get()) chain.proceed() else negotiation.withXapl(chain.args[0] as? Array<*>,
                    (chain.args[1] as? android.bluetooth.BluetoothDevice)?.address) { chain.proceed() }
            }
            handles += hook(methods.response).intercept { chain ->
                val original = chain.args[1] as? String
                val response = if (ready.get()) negotiation.response(
                    (chain.args[0] as? android.bluetooth.BluetoothDevice)?.address, original) else original
                if (response == original) chain.proceed() else {
                    if (observed.compareAndSet(false, true))
                        log(Log.INFO, TAG, "Pro 3 legacy HFP battery feature withdrawn for XAPL comparison")
                    chain.proceed(chain.args.toTypedArray().also { it[1] = response })
                }
            }
            ready.set(true)
            log(Log.INFO, TAG, "Scoped Pro 3 HFP battery negotiation ready")
        } catch (_: Throwable) {
            ready.set(false)
            handles.asReversed().forEach { runCatching { it.unhook() } }
            hfpBatteryHooksInstalled.set(false)
            log(Log.WARN, TAG, "HFP battery negotiation unavailable; keeping native calling")
        }
    }

    private fun installHfpCallBridge(loader: ClassLoader) {
        if (android.os.Build.VERSION.SDK_INT != 37 || !hfpBridgeInstalled.compareAndSet(false, true)) return
        val handles = mutableListOf<XposedInterface.HookHandle>()
        val ready = AtomicBoolean(false)
        try {
            val adapter = Class.forName("com.android.bluetooth.btservice.AdapterService", false, loader)
            val headset = Class.forName("com.android.bluetooth.hfp.HeadsetService", false, loader)
            val methods = HfpCallHookMethods.discover(adapter, headset)
            handles += hook(methods.connect).intercept { chain ->
                val peer = (chain.args.firstOrNull() as? android.bluetooth.BluetoothDevice)?.address
                if (hfpControl?.blockConnect(chain.thisObject, peer) == true) false else chain.proceed()
            }
            handles += hook(methods.incoming).intercept { chain ->
                val peer = (chain.args.firstOrNull() as? android.bluetooth.BluetoothDevice)?.address
                if (hfpControl?.blockIncoming(chain.thisObject, peer) == true) false else chain.proceed()
            }
            handles += hook(methods.cleanup).intercept { chain ->
                hfpControl?.serviceStopped(chain.thisObject)
                chain.proceed()
            }
            handles += hook(methods.create).intercept { chain ->
                val result = chain.proceed()
                val context = chain.thisObject as? Context
                if (ready.get() && context != null && hfpBridge == null) {
                    val control = HfpCallSuppression(context) { methods.current.invoke(null) }
                    val bridge = runCatching { HfpCallTransport.install(context, control, moduleApplicationInfo.uid) }.getOrNull()
                    if (bridge == null) control.close() else {
                        hfpControl = control; hfpBridge = bridge; hfpBridgeContext = context
                        log(Log.INFO, TAG, "Selected-call hands-free control ready")
                    }
                }
                result
            }
            handles += hook(methods.destroy).intercept { chain ->
                if (chain.thisObject === hfpBridgeContext) {
                    runCatching { hfpBridge?.close() }
                    hfpControl = null; hfpBridge = null; hfpBridgeContext = null
                }
                chain.proceed()
            }
            ready.set(true)
        } catch (_: Throwable) {
            ready.set(false)
            handles.asReversed().forEach { runCatching { it.unhook() } }
            hfpBridgeInstalled.set(false)
            log(Log.WARN, TAG, "Selected-call hands-free control unavailable; keeping native calling")
        }
    }

    private fun installNativeGameBridge(loader: ClassLoader) {
        if (android.os.Build.VERSION.SDK_INT != 37 || !gameBridgeInstalled.compareAndSet(false, true)) return
        val handles = mutableListOf<XposedInterface.HookHandle>()
        try {
            val adapter = Class.forName("com.android.bluetooth.btservice.AdapterService", false, loader)
            val create = exactEldMethod(adapter, "onCreate", Void.TYPE, emptyList())
            val destroy = exactEldMethod(adapter, "onDestroy", Void.TYPE, emptyList())
            val gateway = object : GameNativeGateway {
                override fun query() = NativeBridge.queryNativeGame()
                override fun submit(operation: Int, ownerSerial: Long, expectedPeer: ByteArray,
                    expectedMediaGeneration: Long, arguments: LongArray): LongArray {
                    if (arguments.size != 2 || arguments[1] !in 0..0xeff) return longArrayOf()
                    return NativeBridge.submitNativeGame(operation, ownerSerial, expectedPeer,
                        expectedMediaGeneration, arguments[0], arguments[1].toInt())
                }
                override fun poll(ownerSerial: Long, nativeSerial: Long) =
                    NativeBridge.pollNativeGame(ownerSerial, nativeSerial)
                override fun restore(ownerSerial: Long, holdSerial: Long) =
                    NativeBridge.restoreNativeGame(ownerSerial, holdSerial)
                override fun beginClock(ownerSerial: Long, holdSerial: Long, expectedPeer: ByteArray,
                    expectedMediaGeneration: Long, arguments: LongArray): LongArray {
                    if (arguments.size != 4 || arguments[0] <= 0 || arguments[1] !in 0..0xeff ||
                        arguments[2] !in setOf(9L, 11L) || arguments[3] !in 1..500) return longArrayOf()
                    return NativeBridge.beginNativeGameClock(ownerSerial, holdSerial, expectedPeer,
                        expectedMediaGeneration, arguments[0], arguments[1].toInt(),
                        arguments[2].toInt(), arguments[3].toInt())
                }
                override fun cancelClock(ownerSerial: Long, holdSerial: Long) =
                    NativeBridge.cancelNativeGameClock(ownerSerial, holdSerial)
                override fun cancelOwner(ownerSerial: Long) = NativeBridge.cancelNativeGame(ownerSerial)
            }
            handles += hook(create).intercept { chain ->
                val result = chain.proceed()
                val context = chain.thisObject as? Context
                if (context != null && gameBridge == null) {
                    runCatching { NativeGameTransportServer.install(context, gateway, moduleApplicationInfo.uid) }
                        .onSuccess { server ->
                            if (server != null) {
                                gameBridge = server
                                gameBridgeContext = context
                                log(Log.INFO, TAG, "Native Game Mode diagnostic bridge ready")
                            }
                        }.onFailure { log(Log.WARN, TAG, "Native Game Mode bridge unavailable") }
                }
                result
            }
            handles += hook(destroy).intercept { chain ->
                if (chain.thisObject === gameBridgeContext) {
                    runCatching { gameBridge?.close() }
                    gameBridge = null
                    gameBridgeContext = null
                }
                chain.proceed()
            }
        } catch (_: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() } }
            gameBridgeInstalled.set(false)
            log(Log.WARN, TAG, "Native Game Mode lifecycle unavailable; keeping ordinary audio")
        }
    }

    private fun installEldCodecBridge(loader: ClassLoader, enabled: Boolean) {
        if (eldJavaHooksInstalled) return
        val sdk = android.os.Build.VERSION.SDK_INT
        if (sdk != 33 && sdk != 37) {
            log(Log.WARN, TAG, "ELD Bluetooth API unsupported; keeping stock codecs")
            return
        }
        try {
            NativeBridge.setEldJavaBridgeReady(false)
            NativeBridge.setEldEnabled(enabled)
            NativeBridge.isEldRuntimeReady() // Resolve every JNI symbol before acknowledging Java.
            NativeBridge.getEldEncoderGeneration()
            NativeBridge.getEldEncoderPeer()
            NativeBridge.isEldEncoderFailed()
            if (sdk == 37) require(NativeBridge.getEldNativeCodecIndex() in -1..11)
        } catch (_: Throwable) {
            log(Log.WARN, TAG, "ELD native bridge unavailable; keeping stock codecs")
            return
        }
        val (plan, hostLoader) = try {
            val nativeClass = Class.forName("com.android.bluetooth.a2dp.A2dpNativeInterface", false, loader)
            val owner = if (sdk == 37) "AdapterService" else "AdapterApp"
            val adapterClass = Class.forName("com.android.bluetooth.btservice.$owner", false, loader)
            val hostLoader = adapterClass.classLoader ?: nativeClass.classLoader
                ?: throw IllegalStateException("Bluetooth defining loader unavailable")
            require(Class.forName(nativeClass.name, false, hostLoader) === nativeClass)
            check(NativeBridge.pinEldHostLoader(hostLoader)) { "Bluetooth loader pin unavailable" }
            EldCodecHookPlan.discover(hostLoader, sdk) {
                runCatching { NativeBridge.getEldNativeCodecIndex() }.getOrNull()
            } to hostLoader
        } catch (_: Throwable) {
            log(Log.WARN, TAG, "ELD Java signatures unavailable; keeping stock codecs")
            return
        }
        val boundary = EldCodecBoundary(plan.objects)
        val handles = mutableListOf<XposedInterface.HookHandle>()
        fun ready() = runCatching {
            NativeBridge.isEldRuntimeReady() && (!plan.objects.extended || plan.objects.nativeIndex() != null)
        }.getOrDefault(false)
        val watchdog = EldFailureWatchdog(plan, read = {
            runCatching {
                if (!ready()) null else {
                    val generation = NativeBridge.getEldEncoderGeneration()
                    val peer = NativeBridge.getEldEncoderPeer().map { it.toInt() and 255 }
                    val failed = NativeBridge.isEldEncoderFailed()
                    if (generation > 0 && NativeBridge.getEldEncoderGeneration() == generation)
                        EldEncoderHealth(peer, generation, failed) else null
                }
            }.getOrNull()
        }, report = { log(Log.WARN, TAG, it) })
        val automaticOverride = EldCodecOverrideGuard()
        val automaticOverrideObserved = AtomicBoolean(false)
        val automaticOverrideCaptured = AtomicBoolean(false)
        fun rejected() = log(Log.ERROR, TAG, "ELD codec mapping rejected an unexpected carrier")
        try {
            handles += hook(plan.init).intercept { chain ->
                val initial = chain.args.toTypedArray()
                if (plan.objects.extended) {
                    val free = boundary.initialCodecSlotFree(initial)
                    try { NativeBridge.setEldInitialCodecSlotFree(free) }
                    catch (_: Throwable) { rejected(); return@intercept chain.proceed() }
                    if (!free) { rejected(); return@intercept chain.proceed() }
                }
                val active = ready()
                val args = try { boundary.init(initial, active) }
                    catch (_: Throwable) {
                        if (plan.objects.extended) {
                            runCatching { NativeBridge.setEldInitialCodecSlotFree(false) }
                            rejected(); return@intercept chain.proceed()
                        }
                        if (!active) return@intercept chain.proceed()
                        rejected(); return@intercept null
                    }
                chain.proceed(args)
            }
            handles += hook(plan.outbound).intercept { chain ->
                if (!ready()) return@intercept chain.proceed()
                val args = try { boundary.outbound(chain.args.toTypedArray(), true) }
                    catch (_: Throwable) { rejected(); return@intercept null }
                watchdog.codec(args[0] as ByteArray, args[1]?.let {
                    if (plan.objects.isEld(it)) EldCodecIdentity.MODULE_CODEC_ID
                    else if (plan.objects.extended) -1 else plan.objects.fields(it).type
                } ?: -1)
                chain.proceed(args)
            }
            handles += hook(plan.preference).intercept { chain ->
                val active = ready()
                val args = try { boundary.preference(chain.args.toTypedArray(), active) }
                    catch (_: Throwable) { if (!active) return@intercept chain.proceed(); rejected(); return@intercept false }
                if (args == null) false else {
                    val blocked = automaticOverride.hasRequest && runCatching {
                        val native = chain.thisObject ?: return@runCatching false
                        val peer = (plan.address.invoke(native, args[0]) as ByteArray).map { it.toInt() and 255 }
                        val codecs = (args[1] as Array<*>).map { it?.let(plan.objects::fields) }
                        automaticOverride.shouldBlock(peer, codecs,
                            enabled = { getRemotePreferences("me.kavishdevar.librepods").getBoolean("aac_eld_hook", false) },
                            healthy = { active && EldCodecOverrideGuard.bonded(args[0]) && watchdog.protectsHealthyEld(native, args[0]) },
                            idle = { EldCodecOverrideGuard.idle(gameBridgeContext) })
                    }.getOrDefault(false)
                    if (blocked) {
                        if (automaticOverrideObserved.compareAndSet(false, true))
                            log(Log.INFO, TAG, "Ignored automatic Pixel device-service AAC override for healthy selected AAC-ELD")
                        false
                    } else chain.proceed(args)
                }
            }
            handles += hook(plan.priorities).intercept { chain ->
                val original = chain.proceed()
                try { plan.objects.priorities(original, ready()) }
                catch (_: Throwable) { rejected(); original }
            }
            plan.bufferSwitch?.let { method -> handles += hook(method).intercept { chain ->
                if (ready() && boundary.suppressBufferSwitch(chain.args[2] as Int, true)) null else chain.proceed()
            } }
            plan.name?.let { method -> handles += hook(method).intercept { chain ->
                if (ready() && chain.args[0] == EldCodecIdentity.MODULE_CODEC_ID) "Apple AAC-ELD" else chain.proceed()
            } }
            handles += hook(plan.active).intercept { chain ->
                val result = chain.proceed()
                if (result == true) chain.thisObject?.let { watchdog.selected(it, chain.args[0]) }
                result
            }
            handles += hook(plan.audio).intercept { chain ->
                if (ready()) watchdog.audioChanged(chain.args[0] as ByteArray, chain.args[1] == 2)
                chain.proceed()
            }
            handles += hook(plan.connection).intercept { chain ->
                if (chain.args[1] == 0 || chain.args[1] == 3) watchdog.disconnected(chain.args[0] as ByteArray)
                else if (chain.args[1] == 2) watchdog.connected(chain.args[0] as ByteArray)
                chain.proceed()
            }
            handles += hook(plan.cleanup).intercept { chain -> watchdog.clear(); chain.proceed() }
        } catch (_: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() } }
            runCatching { NativeBridge.setEldJavaBridgeReady(false) }
            log(Log.WARN, TAG, "ELD Java hooks incomplete; keeping stock codecs")
            return
        }
        eldJavaHooksInstalled = true
        // Keep all mapping hooks if acknowledgement fails: native identity may already
        // have been published. Native readiness is sticky until ELD objects are retired.
        try { NativeBridge.setEldJavaBridgeReady(true) }
        catch (_: Throwable) { log(Log.ERROR, TAG, "ELD Java readiness acknowledgement failed") }
        if (sdk == 37) try {
            val incoming = EldCodecPreferenceBinderPlan.discover(hostLoader, sdk)
            hook(incoming.preference).intercept { chain ->
                val callingUid = android.os.Binder.getCallingUid()
                val call = EldCodecOverrideGuard.BinderCall(callingUid,
                    chain.args.getOrNull(0) as? android.bluetooth.BluetoothDevice, chain.args.getOrNull(1),
                    chain.args.getOrNull(2) as? android.content.AttributionSource)
                val captured = EldCodecOverrideGuard.capture(gameBridgeContext, call, plan.objects)
                if (captured != null && automaticOverrideCaptured.compareAndSet(false, true))
                    log(Log.INFO, TAG, "Scoped Pixel codec request captured")
                // Always retain platform permission/attribution checks; suppress only at the native boundary.
                automaticOverride.withRequest(captured) { chain.proceed() }
            }
            log(Log.INFO, TAG, "Scoped Pixel automatic codec preference guard ready")
        } catch (failure: Throwable) {
            log(Log.WARN, TAG, "Automatic codec preference guard unavailable; keeping stock requests: ${EldCodecOverrideGuard.failure(failure)}")
        }
    }

    private fun hookSettingsController(param: PackageLoadedParam, className: String) {
        log(Log.INFO, TAG, "Settings app detected, hooking Bluetooth icon handling")
        try {
            val headerControllerClass = Class.forName(className, false, param.defaultClassLoader)
            val updateIconMethod = headerControllerClass.getDeclaredMethod(
                "updateIcon",
                ImageView::class.java,
                String::class.java
            )

            hook(updateIconMethod).intercept { chain ->
                try {
                    val imageView = chain.args[0] as? ImageView
                    val iconUri = chain.args[1] as? String

                    if (imageView == null || iconUri == null) {
                        return@intercept chain.proceed()
                    }

                    val uri = iconUri.toUri()
                    if (!uri.toString().startsWith("android.resource://me.kavishdevar.librepods")) {
                        return@intercept chain.proceed()
                    }


                    Handler(Looper.getMainLooper()).post {
                        try {
                            val context = imageView.context
                            val packageName = uri.authority ?: return@post
                            val packageContext = context.createPackageContext(
                                packageName,
                                Context.CONTEXT_IGNORE_SECURITY
                            )

                            val resPath = uri.pathSegments
                            if (resPath.size >= 2 && resPath[0] == "drawable") {
                                val resourceName = resPath[1]
                                val resourceId = packageContext.resources.getIdentifier(
                                    resourceName, "drawable", packageName
                                )

                                if (resourceId != 0) {
                                    val drawable = packageContext.resources.getDrawable(
                                        resourceId, packageContext.theme
                                    )
                                    imageView.setImageDrawable(drawable)
                                    imageView.alpha = 1.0f
                                } else {
                                    log(Log.ERROR, TAG, "Resource not found: $resourceName")
                                }
                            }
                        } catch (e: Exception) {
                            log(Log.ERROR, TAG, "Error loading resource from URI $uri: ${e.message}")
                        }
                    }
                    null
                } catch (e: Exception) {
                    log(Log.ERROR, TAG, "Error in Bluetooth icon hook: ${e.message}")
                    chain.proceed()
                }
            }

            log(Log.INFO, TAG, "Successfully hooked updateIcon method in Bluetooth settings")
        } catch (e: Exception) {
            log(Log.ERROR, TAG, "Failed to hook Bluetooth icon handler: ${e.message}")
        }
    }
}


object NativeBridge {
    external fun queryNativeGame(): LongArray
    external fun submitNativeGame(operation: Int, leaseOwner: Long, peer: ByteArray,
        mediaGeneration: Long, encoderGeneration: Long, aclHandle: Int): LongArray
    external fun pollNativeGame(leaseOwner: Long, nativeSerial: Long): LongArray
    external fun restoreNativeGame(leaseOwner: Long, nativeSerial: Long): LongArray
    external fun beginNativeGameClock(leaseOwner: Long, nativeSerial: Long, peer: ByteArray,
        mediaGeneration: Long, encoderGeneration: Long, aclHandle: Int,
        intervalTenths: Int, driftMs: Int): LongArray
    external fun cancelNativeGameClock(leaseOwner: Long, nativeSerial: Long): LongArray
    external fun cancelNativeGame(leaseOwner: Long)
    external fun setSdpHook(enabled: Boolean)
    external fun setEldJavaBridgeReady(ready: Boolean)
    external fun setEldInitialCodecSlotFree(free: Boolean)
    external fun setEldEnabled(enabled: Boolean)
    external fun isEldRuntimeReady(): Boolean
    external fun getEldNativeCodecIndex(): Int
    external fun getEldEncoderGeneration(): Long
    external fun getEldEncoderPeer(): ByteArray
    external fun isEldEncoderFailed(): Boolean
    external fun pinEldHostLoader(loader: ClassLoader): Boolean
}
