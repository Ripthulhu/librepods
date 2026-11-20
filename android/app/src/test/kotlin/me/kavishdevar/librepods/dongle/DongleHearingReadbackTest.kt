package me.kavishdevar.librepods.dongle

import android.app.Application
import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.core.bluetooth.att.types.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.HearingModes
import me.kavishdevar.librepods.core.devices.SettingsReadStatus
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class DongleHearingReadbackTest {
    @Test fun delayedBaselineRetriesOnlyReadsAndAcceptsEitherValidLengthHeader() = runBlocking<Unit> {
        fixture {
            response = { attempt ->
                (if (attempt < 3) baseline else checkNotNull(written)).copyOf().also {
                    it[2] = if (attempt < 3) 0x64 else 0x60
                }
            }
            edit(); settled()
            assertEquals(3, readbackTimes.size)
            assertSpacedReads()
            assertEquals(1, writes)
            assertNull(controller.state.value.error)
            assertEquals(0.25f, controller.state.value.hearingProfile!!.leftTone, 0f)
            assertEquals(0x60.toByte(), controller.state.value.hearingAidData[2])
            assertArrayEquals(checkNotNull(written).copyOfRange(4, 104),
                controller.state.value.hearingAidData.copyOfRange(4, 104))
            assertNull(firmware.token)
        }
    }

    @Test fun unchangedFittingStopsAfterFiveReadbacksWithoutRewriting() = runBlocking<Unit> {
        fixture {
            response = { baseline }
            edit(); settled()
            assertEquals(5, readbackTimes.size)
            assertSpacedReads()
            assertUnconfirmed()
        }
    }

    @Test fun conflictingFittingStopsAtTheFirstReadback() = runBlocking<Unit> {
        fixture {
            response = { checkNotNull(written).copyOf().also { putFloat(it, 100, 0.5f) } }
            edit(); settled()
            assertEquals(1, readbackTimes.size)
            assertUnconfirmed()
        }
    }

    @Test fun malformedOrReadOnlyFittingsNeverConfirmOrRetry() = runBlocking<Unit> {
        for (kind in 0..3) fixture {
            response = {
                checkNotNull(written).copyOf().let { value ->
                    when (kind) {
                        0 -> value.copyOf(103)
                        1 -> value.also { it[2] = 0x61 }
                        2 -> value.also { putFloat(it, 100, Float.NaN) }
                        else -> value.also { it[1] = 0 }
                    }
                }
            }
            edit(); settled()
            assertEquals(1, readbackTimes.size)
            assertUnconfirmed()
        }
    }

    @Test fun ownerChangeDuringRetryPreventsAnotherRead() = runBlocking<Unit> {
        fixture {
            response = { baseline }
            edit()
            withTimeout(2_000) { firstRead.await() }
            state.value = state.value.copy(controlSession = state.value.controlSession + 1)
            settled()
            assertEquals(1, readbackTimes.size)
            assertEquals(1, writes)
            assertArrayEquals(baseline, controller.state.value.hearingAidData)
            assertNull(firmware.token)
        }
    }

    @Test fun ownerChangeWhileMatchingReadbackReturnsCannotPublishIt() = runBlocking<Unit> {
        fixture {
            response = {
                state.value = state.value.copy(controlSession = state.value.controlSession + 1)
                checkNotNull(written)
            }
            edit(); settled()
            assertEquals(1, readbackTimes.size)
            assertEquals(1, writes)
            assertArrayEquals(baseline, controller.state.value.hearingAidData)
            assertNull(firmware.token)
        }
    }

    @Test fun changedPreflightFittingIsNeverWritten() = runBlocking<Unit> {
        fixture {
            preflight = baseline.copyOf().also { putFloat(it, 100, 0.5f) }
            edit(); settled()
            assertEquals(0, writes)
            assertTrue(readbackTimes.isEmpty())
            assertArrayEquals(baseline, controller.state.value.hearingAidData)
            assertNotNull(controller.state.value.error)
            assertNull(firmware.token)
        }
    }

    @Test fun cancellationDuringRetryStopsFurtherReads() = runBlocking<Unit> {
        fixture {
            response = { baseline }
            edit()
            withTimeout(2_000) { firstRead.await() }
            (field("scope").get(controller) as CoroutineScope).cancel()
            delay(150)
            assertEquals(1, readbackTimes.size)
            assertEquals(1, writes)
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
        private val client = DongleSession(firmware::exchange, closeTransport = firmware::close)
        val baseline = ByteArray(104).also { it[0] = 2; it[1] = 2; it[2] = 0x60 }
        var preflight = baseline
        var written: ByteArray? = null
        var writes = 0
        val readbackTimes = mutableListOf<Long>()
        val firstRead = CompletableDeferred<Unit>()
        var response: (Int) -> ByteArray = { checkNotNull(written) }
        val state: MutableStateFlow<DongleState>

        init {
            context.getSharedPreferences("dongle-control", Context.MODE_PRIVATE).edit().clear()
                .putString("address", "00:11:22:33:44:55").commit()
            controller = DongleController(context)
            (field("scope").get(controller) as CoroutineScope).cancel()
            field("scope").set(controller, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
            field("bridge\$delegate").set(controller, lazyOf(mock(ShizukuAudioClient::class.java)))
            @Suppress("UNCHECKED_CAST")
            val mutable = field("mutableState").get(controller) as MutableStateFlow<DongleState>
            state = mutable
            val hearing = byteArrayOf(4, 0, 4, 0, 9, 0, 0x2c, 1, 1, 0, 0)
            val gate = byteArrayOf(4, 0, 4, 0, 9, 0, 0x33, 1, 0, 0, 0)
            firmware.cachedReply = { slot -> if (slot == 4) byteArrayOf() else
                ByteArray(8).also { DongleProtocol.put32(it, 4, 1) } + if (slot == 2) hearing else gate }
            firmware.rawReply = { operation, request ->
                assertEquals(1, operation)
                when (request[0].toInt()) {
                    0x0a -> byteArrayOf(0x0b) + if (written == null) preflight else {
                        readbackTimes += System.nanoTime()
                        response(readbackTimes.size).also { firstRead.complete(Unit) }
                    }
                    0x12 -> {
                        writes++
                        written = request.copyOfRange(3, request.size)
                        byteArrayOf(0x13)
                    }
                    else -> error("Unexpected ATT request")
                }
            }
            state.value = state.value.copy(metadata = AppleMetadata(modelNumber = "A3063"),
                hearingModes = HearingModes.empty().withReport(hearing).withReport(gate),
                hearingProfile = checkNotNull(parseHearingAidSettingsResponse(baseline)),
                hearingAidData = baseline.copyOf(), hearingAidReadStatus = SettingsReadStatus.AVAILABLE)
            hearingField("profileData").set(field("hearing").get(controller), baseline.copyOf())
            hearingField("profileCharacteristic").set(field("hearing").get(controller), DongleAtt.Characteristic(0x20, 0x12))
        }

        suspend fun attach() {
            field("session").set(controller, client)
            state.value = state.value.copy(connected = true, status = client.refresh(), controlSession = 1)
        }

        fun edit() {
            controller.updateHearing { it.copy(leftTone = 0.25f, rightTone = 0.25f) }
            assertNotNull(controller.liveHearingEditor().state.value.requested)
        }

        suspend fun settled() = withTimeout(3_000) {
            val editor = controller.liveHearingEditor()
            while (editor.state.value.pending || editor.state.value.requested != null || controller.state.value.busy) delay(1)
        }

        fun assertSpacedReads() {
            for ((earlier, later) in readbackTimes.zipWithNext())
                assertTrue("Readback retries must wait", later - earlier >= 70_000_000)
        }

        fun assertUnconfirmed() {
            assertEquals(1, writes)
            assertNotNull(controller.state.value.error)
            assertArrayEquals(baseline, controller.state.value.hearingAidData)
            assertNull(firmware.token)
        }
    }

    companion object {
        private fun field(name: String) = DongleController::class.java.getDeclaredField(name).apply { isAccessible = true }
        private fun hearingField(name: String) = DongleHearingRecords::class.java.getDeclaredField(name).apply { isAccessible = true }
        private fun putFloat(data: ByteArray, offset: Int, value: Float) {
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset, value)
        }
    }
}
