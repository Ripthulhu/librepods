package me.kavishdevar.librepods.dongle

import android.app.Application
import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.lang.reflect.InvocationTargetException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.hostClockPacket
import me.kavishdevar.librepods.core.bluetooth.att.types.HearingDiagnosticUuids
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.SettingsReadStatus
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class DongleHostClockTest {
    @Test fun sendsPhoneUtcTimeBeforeProfilesAndOnlyOnceForAnOwner() = runBlocking<Unit> {
        fixture {
            val before = Instant.now()
            refresh()
            val after = Instant.now()
            val packet = clocks().single()
            val fields = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            val sent = Instant.ofEpochSecond(fields.getInt(6).toLong() and 0xffff_ffffL,
                fields.getInt(10).toLong() * 1_000)
            assertTrue(!sent.isBefore(before.minusNanos(1_000)) && !sent.isAfter(after))
            assertArrayEquals(hostClockPacket(sent)!!.rawPacket, packet)
            assertEquals(41, packet.size)
            assertEquals(2, firmware.rawRequests.first().first)
            assertEquals(SettingsReadStatus.AVAILABLE, state.value.hearingAidReadStatus)
            repeat(3) { retry() }
            assertEquals(1, clocks().size)
            assertNotNull(readsField("hostClockOwner").get(field("automaticReads").get(controller)))
            assertNull(firmware.token)
        }
    }

    @Test fun newOwnerAndClearedReportsEachGetAFreshClock() = runBlocking<Unit> {
        fixture {
            refresh()
            state.value = state.value.copy(controlSession = state.value.controlSession + 1)
            refresh()
            assertEquals(2, clocks().size)
            DongleController::class.java.getDeclaredMethod("clearReports").apply { isAccessible = true }.invoke(controller)
            assertNull(readsField("hostClockOwner").get(field("automaticReads").get(controller)))
            state.value = state.value.copy(metadata = AppleMetadata(modelNumber = "A3063"))
            refresh()
            assertEquals(3, clocks().size)
        }
    }

    @Test fun unsupportedModelsCallsAndMicrophoneCaptureDoNotSendClock() = runBlocking<Unit> {
        for (gate in 0..2) fixture {
            when (gate) {
                0 -> state.value = state.value.copy(metadata = AppleMetadata(modelNumber = "A2084"))
                1 -> field("callActive").setBoolean(controller, true)
                else -> microphone = true
            }
            refresh()
            assertTrue(clocks().isEmpty())
            assertNull(readsField("hostClockOwner").get(field("automaticReads").get(controller)))
        }
    }

    @Test fun staleOwnerBeforeCommitDoesNotSendOrRecordTheClock() = runBlocking<Unit> {
        fixture {
            firmware.beforeLeaseReply = { action -> if (action == 0)
                state.value = state.value.copy(controlSession = state.value.controlSession + 1) }
            refresh()
            assertTrue(clocks().isEmpty())
            assertNull(readsField("hostClockOwner").get(field("automaticReads").get(controller)))
            assertNull(firmware.token)
        }
    }

    @Test fun lateClockReceiptCannotMarkANewOwnerSynchronized() = runBlocking<Unit> {
        fixture {
            clockReply = {
                state.value = state.value.copy(controlSession = state.value.controlSession + 1)
                byteArrayOf()
            }
            refresh()
            assertEquals(1, clocks().size)
            assertNull(readsField("hostClockOwner").get(field("automaticReads").get(controller)))
            clockReply = { byteArrayOf() }
            refresh()
            assertEquals(2, clocks().size)
            assertNotNull(readsField("hostClockOwner").get(field("automaticReads").get(controller)))
        }
    }

    @Test fun rejectedClockDoesNotBlockProfilesAndStopsAtTheHealthRetryLimit() = runBlocking<Unit> {
        fixture {
            clockReply = { byteArrayOf(1) }
            refresh()
            assertEquals(SettingsReadStatus.AVAILABLE, state.value.hearingAidReadStatus)
            repeat(5) { retry() }
            assertEquals(3, clocks().size)
            assertEquals(SettingsReadStatus.AVAILABLE, state.value.hearingAidReadStatus)
            assertNull(readsField("hostClockOwner").get(field("automaticReads").get(controller)))
            assertNull(firmware.token)
        }
    }

    @Test fun repeatedContentionBacksOffWithoutExhaustingHealthReads() = runBlocking<Unit> {
        fixture {
            val competing = DongleSession(firmware::exchange)
            competing.withTarget(competing.refresh()) {
                competing.acquireSettingsLease()
                repeat(5) {
                    if (it > 0) ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
                    expectBusy()
                    assertEquals(0, readsField("healthAttempts").getInt(field("automaticReads").get(controller)))
                    val attempts = firmware.actions.count { action -> action == 0 }
                    repeat(4) { refresh() }
                    assertEquals(attempts, firmware.actions.count { action -> action == 0 })
                    ShadowSystemClock.advanceBy(Duration.ofMillis(4_999))
                    refresh()
                    assertEquals(attempts, firmware.actions.count { action -> action == 0 })
                }
            }
            retry()
            assertEquals(SettingsReadStatus.AVAILABLE, state.value.hearingAidReadStatus)
            assertEquals(1, clocks().size)
            assertEquals(1, readsField("healthAttempts").getInt(field("automaticReads").get(controller)))
            assertNull(firmware.token)
        }
    }

    @Test fun contentionFromRetiredOwnerCannotDecrementNewOwnersBudget() = runBlocking<Unit> {
        fixture {
            val competing = DongleSession(firmware::exchange)
            competing.withTarget(competing.refresh()) {
                competing.acquireSettingsLease()
                var retired = false
                afterExchange = { id, reply ->
                    if (!retired && id == 3 && reply[21] == 4.toByte() && reply[22] == 2.toByte()) {
                        retired = true
                        clearReports()
                    }
                }
                expectBusy()
                assertTrue(retired)
                assertEquals(0, readsField("healthAttempts").getInt(field("automaticReads").get(controller)))
                assertNull(readsField("healthAttemptOwner").get(field("automaticReads").get(controller)))
            }
            state.value = state.value.copy(metadata = AppleMetadata(modelNumber = "A3063"))
            refresh()
            assertEquals(SettingsReadStatus.AVAILABLE, state.value.hearingAidReadStatus)
            assertEquals(1, readsField("healthAttempts").getInt(field("automaticReads").get(controller)))
            assertEquals(1, clocks().size)
        }
    }

    @Test fun busyTargetRefreshDoesNotConsumeTheHealthBudget() = runBlocking<Unit> {
        fixture {
            var blocked = false
            beforeExchange = { _, id ->
                if (!blocked && id == 2 && readsField("healthAttempts").getInt(field("automaticReads").get(controller)) > 0) {
                    blocked = true
                    throw DongleBusyException()
                }
            }
            expectBusy()
            assertTrue(blocked)
            assertEquals(0, readsField("healthAttempts").getInt(field("automaticReads").get(controller)))
            repeat(3) { refresh() }
            assertTrue(clocks().isEmpty())
            retry()
            assertEquals(SettingsReadStatus.AVAILABLE, state.value.hearingAidReadStatus)
            assertEquals(1, clocks().size)
        }
    }

    private suspend fun fixture(test: suspend Rig.() -> Unit) {
        val rig = Rig()
        try { rig.attach(); rig.test() } finally { rig.controller.close() }
    }

    private class Rig {
        private val context = RuntimeEnvironment.getApplication()
        val controller: DongleController
        val firmware = DongleLeaseFirmware()
        var microphone = false
        var clockReply: () -> ByteArray = { byteArrayOf() }
        var beforeExchange: (Int, Int) -> Unit = { _, _ -> }
        var afterExchange: (Int, ByteArray) -> Unit = { _, _ -> }
        private val client = DongleSession({ operation, id, data ->
            beforeExchange(operation, id)
            firmware.exchange(operation, id, data).also {
                if (id == 2 && microphone) it[7] = 19
                afterExchange(id, it)
            }
        }, closeTransport = firmware::close)
        val state: MutableStateFlow<DongleState>
        private val fitting = ByteArray(104).also { it[0] = 2; it[1] = 2; it[2] = 0x60 }

        init {
            context.getSharedPreferences("dongle-control", Context.MODE_PRIVATE).edit().clear()
                .putString("address", "00:11:22:33:44:55").commit()
            controller = DongleController(context)
            (field("scope").get(controller) as CoroutineScope).cancel()
            field("bridge\$delegate").set(controller, lazyOf(mock(ShizukuAudioClient::class.java)))
            @Suppress("UNCHECKED_CAST")
            val mutable = field("mutableState").get(controller) as MutableStateFlow<DongleState>
            state = mutable
            firmware.rawReply = { operation, request ->
                if (operation == 2) clockReply() else when (request[0].toInt()) {
                    6 -> byteArrayOf(7, 1, 0, 0x30, 0)
                    8 -> if (request[1] == 1.toByte()) byteArrayOf(9, 21, 0x10, 0, 0x0a, 0x11, 0) +
                        wire(UUID.fromString("d5621cc1-f7ab-45db-9403-9eaf744d5390")) +
                        byteArrayOf(0x20, 0, 2, 0x21, 0) + wire(HearingDiagnosticUuids.fitting)
                        else byteArrayOf(1, 8, request[1], request[2], 0x0a)
                    0x0a -> byteArrayOf(0x0b) + if (request[1] == 0x21.toByte()) fitting else ByteArray(104)
                    else -> error("Unexpected ATT write")
                }
            }
        }

        suspend fun attach() {
            field("session").set(controller, client)
            state.value = state.value.copy(connected = true, status = client.refresh(), controlSession = 1,
                metadata = AppleMetadata(modelNumber = "A3063"))
        }

        suspend fun refresh(): Unit = suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                DongleController::class.java.getDeclaredMethod("refresh", DongleSession::class.java, Continuation::class.java)
                    .apply { isAccessible = true }.invoke(controller, client, continuation)
            } catch (error: InvocationTargetException) { throw checkNotNull(error.cause) }
        }

        suspend fun retry() { ShadowSystemClock.advanceBy(Duration.ofSeconds(6)); refresh() }

        suspend fun expectBusy() {
            try { refresh(); fail("Expected another client's settings lease to block health reads") }
            catch (_: DongleBusyException) { }
        }

        fun clearReports() = DongleController::class.java.getDeclaredMethod("clearReports")
            .apply { isAccessible = true }.invoke(controller)

        fun clocks() = firmware.rawRequests.filter { it.first == 2 }.map { it.second }

        private fun wire(id: UUID) = ByteBuffer.allocate(16).putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits).array().reversedArray()
    }

    companion object {
        private fun field(name: String) = DongleController::class.java.getDeclaredField(name).apply { isAccessible = true }
        private fun readsField(name: String) = DongleAutomaticReads::class.java.getDeclaredField(name).apply { isAccessible = true }
    }
}
