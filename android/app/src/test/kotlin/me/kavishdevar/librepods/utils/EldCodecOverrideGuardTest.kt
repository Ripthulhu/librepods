/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class EldCodecOverrideGuardTest {
    private val peer = listOf(1, 2, 3, 4, 5, 6)
    private val aac = EldCodecIdentity.Fields(1, 1_000_000, 0, 0, 0, 0, 0, 0, 0, 2L)
    private val guard = EldCodecOverrideGuard()
    private val request = EldCodecOverrideGuard.Request(peer, aac)
    private fun block(peer: List<Int>? = this.peer, codecs: List<EldCodecIdentity.Fields?> = listOf(aac),
        enabled: () -> Boolean = { true }, healthy: () -> Boolean = { true }, idle: () -> Boolean = { true }) =
        guard.shouldBlock(peer, codecs, enabled, healthy, idle)

    @Test fun onlyAnExactScopedForcedAacRequestCanBeSuppressed() {
        assertFalse(block()) // Manual Settings and internal watchdog calls have no captured Binder scope.
        guard.withRequest(request) {
            assertTrue(block())
            assertFalse(block(peer = peer.reversed()))
            assertFalse(block(peer = null))
            assertFalse(block(codecs = emptyList()))
            assertFalse(block(codecs = listOf(null)))
            assertFalse(block(codecs = listOf(aac, aac)))
            assertFalse(block(codecs = listOf(aac.copy(sampleRate = 1))))
            assertFalse(block(codecs = listOf(aac.copy(priority = 2001))))
            assertFalse(block(codecs = listOf(aac.copy(type = 5, extendedCodecId = EldCodecIdentity.APPLE_CODEC_ID))))
        }
        assertFalse(guard.hasRequest)
        assertFalse(block())
    }

    @Test fun offUnknownFailedOrBusyStateAlwaysPasses() = guard.withRequest(request) {
        assertFalse(block(enabled = { false }))
        assertFalse(block(enabled = { throw IllegalStateException("remote preference unavailable") }))
        assertFalse(block(healthy = { false }))
        assertFalse(block(healthy = { throw IllegalStateException("native health unavailable") }))
        assertFalse(block(idle = { false }))
        assertFalse(block(idle = { throw SecurityException("call state unavailable") }))
    }

    @Test fun nestedUntrustedRequestsDoNotInheritTheOuterIdentity() = guard.withRequest(request) {
        assertTrue(block())
        guard.withRequest(null) { assertFalse(guard.hasRequest); assertFalse(block()) }
        assertTrue(block())
        guard.withRequest(request.copy(peer = peer.reversed())) { assertFalse(block()) }
        assertTrue(block())
    }

    @Test fun stockAuthorizationExceptionsPropagateOnceAndClearTheScope() {
        var calls = 0
        val error = assertThrows(SecurityException::class.java) {
            guard.withRequest(request) { calls++; throw SecurityException("stock authorization rejected") }
        }
        assertEquals("stock authorization rejected", error.message)
        assertEquals(1, calls)
        assertFalse(guard.hasRequest)
        assertFalse(block())
    }

    @Test fun nestedExceptionsRestoreTheOuterScope() = guard.withRequest(request) {
        assertThrows(IllegalStateException::class.java) {
            guard.withRequest(null) { throw IllegalStateException("nested") }
        }
        assertTrue(block())
    }

    @Test fun capturedIdentityDoesNotLeakToAnotherThread() = guard.withRequest(request) {
        var otherBlocked = true
        val thread = Thread { otherBlocked = block() }
        thread.start(); thread.join()
        assertFalse(otherBlocked)
        assertTrue(block())
    }

    @Test fun actualBinderUidMustOwnTheUniqueTrustedSystemPackageAndAgreeWithAttribution() {
        val actor = EldCodecOverrideGuard.ACTOR
        fun trusted(caller: Int = 10294, uid: Int = 10294, system: Boolean = true,
            packages: List<String>? = listOf(actor), claimedUid: Int? = 10294,
            claimedPackage: String? = actor, delegated: Boolean = false) =
            EldCodecOverrideGuard.trustedCaller(EldCodecOverrideGuard.CallerIdentity(
                caller, uid, system, packages, claimedUid, claimedPackage, delegated))
        assertTrue(trusted())
        assertFalse(trusted(caller = 1000)) // Settings/system_server.
        assertFalse(trusted(caller = 1002)) // Bluetooth/internal watchdog.
        assertFalse(trusted(caller = 10300)) // A supplied attribution cannot impersonate the actor.
        assertFalse(trusted(uid = 10300)) // Package/UID identity changed.
        assertFalse(trusted(system = false))
        assertFalse(trusted(packages = null))
        assertFalse(trusted(packages = listOf(actor, "another.system.package")))
        assertFalse(trusted(packages = listOf("com.android.settings")))
        assertFalse(trusted(claimedUid = null))
        assertFalse(trusted(claimedUid = 10300))
        assertFalse(trusted(claimedPackage = null))
        assertFalse(trusted(claimedPackage = "com.android.settings"))
        assertFalse(trusted(delegated = true))
    }

    @Test fun ordinaryAacIdentityAndPeerAddressMustBeUnambiguous() {
        assertTrue(EldCodecOverrideGuard.forcedAac(aac))
        assertFalse(EldCodecOverrideGuard.forcedAac(aac.copy(extendedCodecId = null)))
        assertFalse(EldCodecOverrideGuard.forcedAac(aac.copy(extendedCodecId = EldCodecIdentity.APPLE_CODEC_ID)))
        assertFalse(EldCodecOverrideGuard.forcedAac(aac.copy(type = 5)))
        assertFalse(EldCodecOverrideGuard.forcedAac(aac.copy(priority = 2001)))
        assertEquals(peer, EldCodecOverrideGuard.peer("01:02:03:04:05:06"))
        for (address in listOf(null, "", "01:02:03:04:05", "GG:02:03:04:05:06", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF"))
            assertNull(EldCodecOverrideGuard.peer(address))
    }

    @Test fun discoveryFailureWarningIsBoundedAndContainsNoControlOrFormattingCharacters() {
        val error = IllegalStateException("\n\t\u001b\u2028\u2029\u202e" + "x".repeat(1000))
        val message = EldCodecOverrideGuard.failure(error)
        assertTrue(message.startsWith("java.lang.IllegalStateException: "))
        assertTrue(message.length <= 96 + 2 + 160)
        assertFalse(message.any { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() ||
            it == '\u2028' || it == '\u2029' })
        assertEquals("java.lang.IllegalStateException", EldCodecOverrideGuard.failure(IllegalStateException()))
    }

    @Test fun binderDiscoveryRequiresTheExactDeclaredSdk37OverrideAndOwner() {
        fun discover(owner: Class<*>, stub: Class<*> = Stub::class.java) = EldCodecPreferenceBinderPlan(owner,
            stub, Device::class.java, Codec::class.java, Attribution::class.java)
        assertEquals(Binder::class.java, discover(Binder::class.java).preference.declaringClass)
        for (owner in listOf(WrongResult::class.java, WrongArguments::class.java, Overloaded::class.java,
                Inherited::class.java, Static::class.java))
            assertThrows(IllegalArgumentException::class.java) { discover(owner) }
        assertThrows(IllegalArgumentException::class.java) { discover(Binder::class.java, Unrelated::class.java) }
        assertThrows(IllegalArgumentException::class.java) {
            EldCodecPreferenceBinderPlan.discover(checkNotNull(javaClass.classLoader), 33)
        }
    }

    open class Stub
    open class Binder : Stub() { fun setCodecConfigPreference(device: Device, codec: Codec, source: Attribution) = Unit }
    class Inherited : Binder()
    class WrongResult : Stub() { fun setCodecConfigPreference(device: Device, codec: Codec, source: Attribution) = true }
    class WrongArguments : Stub() { fun setCodecConfigPreference(device: Device, codec: Codec) = Unit }
    class Overloaded : Stub() {
        fun setCodecConfigPreference(device: Device, codec: Codec, source: Attribution) = Unit
        fun setCodecConfigPreference(device: Device, codec: Codec) = Unit
    }
    class Static : Stub() { companion object {
        @JvmStatic fun setCodecConfigPreference(device: Device, codec: Codec, source: Attribution) = Unit
    } }
    class Device
    class Codec
    class Attribution
    class Unrelated
}
