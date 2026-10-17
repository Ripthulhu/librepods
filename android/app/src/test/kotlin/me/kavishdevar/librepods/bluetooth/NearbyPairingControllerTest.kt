/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.ble.NearbyAirPods
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.presentation.overlays.PopupWindow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.shadows.ShadowSettings
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, shadows = [PairingDeviceShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class NearbyPairingControllerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var adapter: BluetoothAdapter
    private lateinit var controller: NearbyPairingController
    private val nativeReceivers = mutableListOf<BroadcastReceiver>()

    @Before fun setUp() {
        shadowOf(context).setComponentNameAndServiceForBindService(ComponentName(context, PairingConsentService::class.java),
            object : IPairingConsentGate.Stub() {
                override fun watch(modelId: Int, owner: IPairingConsentOwner) = Unit
                override fun finish(requestId: Long, accepted: Boolean) = Unit
                override fun stop(owner: IPairingConsentOwner) = Unit
            })
        shadowOf(context).setUnbindServiceCallsOnServiceDisconnected(false)
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        ShadowSettings.setCanDrawOverlays(true)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        controller = newController()
    }

    @After fun tearDown() {
        controller.close()
        nativeReceivers.forEach(context::unregisterReceiver)
    }

    @Test fun soleFreshClassicCandidateBondsAtTwoSecondsWithoutWaitingForInquiryEnd() {
        connect()
        assertEquals(PopupWindow.Stage.SETUP, stage())
        val device = device(1)
        found(device)
        val observedAt = field("firstCandidateAt") as Long
        advanceTo(observedAt + 1_000)
        found(device)
        // Native view invalidation can advance the test clock by a frame during broadcast delivery.
        advanceTo(observedAt + 1_900)
        assertTrue(SystemClock.elapsedRealtime() < observedAt + 2_000)
        assertEquals(0, pairing(device).requests)
        advanceTo(observedAt + 2_000)
        assertEquals(1, pairing(device).requests)
        assertEquals("Bonding must use the original candidate's exact two-second deadline", 2_000L,
            pairing(device).bondAt - observedAt)
        assertEquals(BluetoothDevice.TRANSPORT_BREDR, pairing(device).transport)
        assertFalse("Our inquiry must stop before Android pairing starts", pairing(device).discoveringAtBond)
        assertFalse(adapter.isDiscovering)
        assertEquals(PopupWindow.Stage.CONNECTING, stage())
        advance(15_000)
        assertEquals("The old full-scan callback must not request another bond", 1, pairing(device).requests)
    }

    @Test fun anEarlySelectionTickRearmsForTheOriginalDeadlineInsteadOfWaitingForInquiryEnd() {
        connect()
        val candidate = device(1)
        found(candidate)
        val observedAt = field("firstCandidateAt") as Long
        advanceTo(observedAt + 1_999)
        val earlyTick = field("selection") as Runnable
        (field("handler") as Handler).removeCallbacks(earlyTick)
        earlyTick.run()
        assertEquals(0, pairing(candidate).requests)
        assertNotNull("An early tick must keep the sole candidate scheduled", field("selection"))
        advanceTo(observedAt + 2_000)
        assertEquals(1, pairing(candidate).requests)
        assertEquals(observedAt + 2_000, pairing(candidate).bondAt)
        assertEquals(BluetoothDevice.TRANSPORT_BREDR, pairing(candidate).transport)
        assertFalse(pairing(candidate).discoveringAtBond)
        advance(15_000)
        assertEquals("Inquiry completion must not repeat a successful selection", 1, pairing(candidate).requests)
    }

    @Test fun ambiguityOrCancellationRetiresTheRearmedSelection() {
        for (cancel in listOf(false, true)) {
            controller.close()
            controller = newController()
            connect()
            val candidate = device(1)
            found(candidate)
            val observedAt = field("firstCandidateAt") as Long
            advanceTo(observedAt + 1_999)
            val earlyTick = field("selection") as Runnable
            (field("handler") as Handler).removeCallbacks(earlyTick)
            earlyTick.run()
            assertNotNull(field("selection"))
            val other = device(2)
            if (cancel) {
                controller.cancelPending()
                idle()
            } else {
                // Deliver the competing result before the one remaining millisecond elapses.
                (field("receiver") as BroadcastReceiver).onReceive(context,
                    discovery(other, BluetoothDevice.DEVICE_TYPE_CLASSIC))
            }
            assertNull("Retirement must remove the replacement timer", field("selection"))
            advance(2_000)
            assertEquals(0, pairing(candidate).requests)
            assertEquals(0, pairing(other).requests)
        }
    }

    @Test fun anotherCandidateBeforeTheSelectionDeadlinePreventsAutomaticPairing() {
        connect()
        val first = device(1)
        val second = device(2)
        found(first)
        val observedAt = field("firstCandidateAt") as Long
        advanceTo(observedAt + 1_900)
        found(second)
        assertTrue("The second candidate must arrive before selection", SystemClock.elapsedRealtime() < observedAt + 2_000)
        advance(2_001)
        assertEquals(0, pairing(first).requests)
        assertEquals(0, pairing(second).requests)
        assertTrue(adapter.isDiscovering)
    }

    @Test fun namesResolveOnlyForClassicAddressesFoundInThisScan() {
        connect()
        val unrelated = device(2)
        nameChanged(unrelated, "AirPods Pro 3")
        advance(2_000)
        assertEquals(0, pairing(unrelated).requests)

        val candidate = device(1, name = null)
        found(candidate)
        advance(500)
        nameChanged(candidate, "Studio AirPods Pro 3")
        advance(1_999)
        assertEquals(0, pairing(candidate).requests)
        advance(1)
        assertEquals("A delayed name must not wait for another discovery cycle", 1, pairing(candidate).requests)
    }

    @Test fun aConflictingResolvedNameRetiresThePendingSelection() {
        connect()
        val candidate = device(1)
        found(candidate)
        advance(1_000)
        nameChanged(candidate, "AirPods Max")
        advance(2_000)
        assertEquals(0, pairing(candidate).requests)
    }

    @Test fun leDiscoveryCannotBecomeAClassicCandidateThroughCachedDualTypeOrNameEvents() {
        connect()
        val candidate = device(1)
        found(candidate, null)
        found(candidate, BluetoothDevice.DEVICE_TYPE_LE)
        nameChanged(candidate, "AirPods Pro 3")
        advance(2_000)
        assertEquals(0, pairing(candidate).requests)
        found(candidate, BluetoothDevice.DEVICE_TYPE_CLASSIC)
        advance(2_000)
        assertEquals(1, pairing(candidate).requests)
    }

    @Test fun canceledInvitationRetiresItsReceiverAndPendingSelectionBeforeReopening() {
        connect()
        val previous = device(1)
        found(previous)
        val oldReceiver = field("receiver") as BroadcastReceiver
        advance(1_000)
        controller.cancelPending()
        idle()
        connect()
        oldReceiver.onReceive(context, discovery(previous, BluetoothDevice.DEVICE_TYPE_CLASSIC))
        val current = device(2)
        found(current)
        advance(2_000)
        assertEquals(0, pairing(previous).requests)
        assertEquals(1, pairing(current).requests)
    }

    @Test fun globalDeadlineAndForeignDiscoveryOwnershipSurviveTheFastPath() {
        adapter.startDiscovery()
        connect()
        assertTrue("Connect must not cancel another app's discovery", adapter.isDiscovering)
        assertEquals(PopupWindow.Stage.RETRY, stage())
        controller.close()
        assertTrue("Closing the invitation must not cancel another app's discovery", adapter.isDiscovering)
        adapter.cancelDiscovery()

        controller = newController()
        connect()
        advance(59_000)
        val late = device(1)
        found(late)
        advance(2_000)
        assertEquals("Selection must not outlive the global 60s request deadline", 0, pairing(late).requests)
        assertFalse(adapter.isDiscovering)
        assertTrue(button().isEnabled)
        assertEquals(PopupWindow.Stage.RETRY, stage())
    }

    @Test fun aConfirmedBondShowsContinueWithoutDeferringThePairedCallback() {
        controller.close()
        val paired = mutableListOf<BluetoothDevice>()
        controller = NearbyPairingController(context, confirmConsent = { _, _ -> false }, onPaired = { paired.add(it) })
        connect()
        val selected = device(1)
        found(selected)
        advance(2_000)
        assertEquals(PopupWindow.Stage.CONNECTING, stage())
        assertTrue(paired.isEmpty())

        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        bondChanged(selected, BluetoothDevice.BOND_BONDING)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDED)
        bondChanged(selected, BluetoothDevice.BOND_BONDED)

        assertEquals(listOf(selected), paired)
        assertEquals(PopupWindow.Stage.COMPLETE, stage())
        assertEquals(context.getString(R.string.pairing_continue), button().text.toString())
        assertTrue(button().performClick())
        idle()
        assertNull(field("popup"))
        advance(3_000)
        assertEquals("Continue must not repeat the paired callback", listOf(selected), paired)
    }

    @Test fun aConfirmedBondWaitsForContinueOrThePhysicalLidClose() {
        controller.close()
        var pairedAt = 0L
        controller = NearbyPairingController(context, confirmConsent = { _, _ -> false }, onPaired = {
            pairedAt = SystemClock.elapsedRealtime()
        })
        connect()
        val selected = device(1)
        found(selected)
        advance(2_000)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        bondChanged(selected, BluetoothDevice.BOND_BONDING)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDED)
        bondChanged(selected, BluetoothDevice.BOND_BONDED)
        advanceTo(pairedAt + 90_000)
        assertEquals(PopupWindow.Stage.COMPLETE, stage())
        controller.onNearbyDevice(NearbyAirPods.Event(0x2720, false))
        idle()
        assertNull(field("popup"))
    }

    @Test fun successBatteryReadingsBelongToTheExactNewBondAndCannotReopenAClosedCard() {
        val selected = bondingCandidate()
        val oldReading = (field("batteryObservedAfter") as Long) - 1
        val old = setOf(Battery(BatteryComponent.CASE, 25, BatteryStatus.NOT_CHARGING, oldReading))
        controller.updatePairingBattery(selected, old)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDED)
        bondChanged(selected, BluetoothDevice.BOND_BONDED)
        controller.updatePairingBattery(selected, old)
        assertEquals("—", popupView().findViewById<TextView>(R.id.pairing_case_battery_text).text.toString())

        val foreign = device(2)
        pairing(foreign).setBondState(BluetoothDevice.BOND_BONDED)
        val live = setOf(
            Battery(BatteryComponent.LEFT, 80, BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.RIGHT, 80, BatteryStatus.NOT_CHARGING),
            Battery(BatteryComponent.CASE, 70, BatteryStatus.CHARGING))
        controller.updatePairingBattery(foreign, live)
        assertEquals("—", popupView().findViewById<TextView>(R.id.pairing_case_battery_text).text.toString())
        controller.updatePairingBattery(selected, live)
        assertEquals("80%", popupView().findViewById<TextView>(R.id.pairing_buds_battery_text).text.toString())
        assertEquals("70%", popupView().findViewById<TextView>(R.id.pairing_case_battery_text).text.toString())

        controller.cancelPending()
        idle()
        assertEquals(0L, field("batteryObservedAfter"))
        controller.updatePairingBattery(selected, live)
        assertNull(field("popup"))
        connect()
        controller.updatePairingBattery(selected, live)
        assertEquals(PopupWindow.Stage.SETUP, stage())
        assertEquals(View.GONE, popupView().findViewById<View>(R.id.pairing_battery_row).visibility)
    }

    @Test fun verifiedInvitationAndPreConnectReadingsCanSeedTheCompletedCardBeforeAnotherReport() {
        controller.close()
        var verified = emptySet<Battery>()
        controller = NearbyPairingController(context, confirmConsent = { _, _ -> false }, onPaired = {
            controller.updatePairingBattery(it, verified)
        })
        advance(1_000)
        controller.onNearbyDevice(NearbyAirPods.Event(0x2720, true), SystemClock.elapsedRealtimeNanos() - 500_000_000L)
        idle()
        assertEquals(PopupWindow.Stage.NEARBY, stage())
        val observed = field("batteryObservedAfter") as Long
        verified = setOf(Battery(BatteryComponent.LEFT, 82, BatteryStatus.NOT_CHARGING, observed + 100),
            Battery(BatteryComponent.RIGHT, 82, BatteryStatus.NOT_CHARGING, observed + 100),
            Battery(BatteryComponent.CASE, 76, BatteryStatus.CHARGING, observed))
        assertTrue("The replay fixture must precede tapping Connect", verified.all { it.observedAt!! < System.currentTimeMillis() })
        assertTrue(button().performClick())
        idle()
        assertEquals("Initial Connect must retain the accepted invitation's observation floor", observed, field("batteryObservedAfter"))
        val selected = device(1)
        found(selected)
        advance(2_000)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        bondChanged(selected, BluetoothDevice.BOND_BONDING)
        controller.updatePairingBattery(selected, verified)
        assertEquals(PopupWindow.Stage.CONNECTING, stage())
        assertEquals(View.GONE, popupView().findViewById<View>(R.id.pairing_battery_row).visibility)

        pairing(selected).setBondState(BluetoothDevice.BOND_BONDED)
        bondChanged(selected, BluetoothDevice.BOND_BONDED)
        assertEquals(PopupWindow.Stage.COMPLETE, stage())
        assertEquals("82%", popupView().findViewById<TextView>(R.id.pairing_buds_battery_text).text.toString())
        assertEquals("76%", popupView().findViewById<TextView>(R.id.pairing_case_battery_text).text.toString())
        idle()
        assertEquals("Success must not clear a synchronous verified seed afterward", "76%",
            popupView().findViewById<TextView>(R.id.pairing_case_battery_text).text.toString())
        controller.close()
        assertEquals(0L, field("batteryObservedAfter"))
        controller.updatePairingBattery(selected, verified)
        assertNull(field("popup"))
    }

    @Test fun retryRetiresThePreviousBatteryFloorAndRejectsEarlierReadings() {
        val selected = bondingCandidate()
        advance(60_000)
        assertEquals(PopupWindow.Stage.RETRY, stage())
        assertEquals(0L, field("batteryObservedAfter"))
        assertTrue(button().performClick())
        idle()
        val observed = field("batteryObservedAfter") as Long
        assertTrue(observed > 0L)
        pairing(selected).setBondState(BluetoothDevice.BOND_NONE)
        found(selected)
        advance(2_000)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        bondChanged(selected, BluetoothDevice.BOND_BONDING)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDED)
        bondChanged(selected, BluetoothDevice.BOND_BONDED)
        controller.updatePairingBattery(selected, setOf(Battery(BatteryComponent.CASE, 25,
            BatteryStatus.NOT_CHARGING, observed - 1)))
        assertEquals("—", popupView().findViewById<TextView>(R.id.pairing_case_battery_text).text.toString())
        controller.updatePairingBattery(selected, setOf(Battery(BatteryComponent.CASE, 73,
            BatteryStatus.NOT_CHARGING, observed)))
        assertEquals("73%", popupView().findViewById<TextView>(R.id.pairing_case_battery_text).text.toString())
    }

    @Test fun removingTheCompletedBondClosesOnlyItsOwnSuccessCard() {
        val selected = bondingCandidate()
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDED)
        bondChanged(selected, BluetoothDevice.BOND_BONDED)
        controller.updatePairingBattery(device(2), emptySet())
        assertEquals(PopupWindow.Stage.COMPLETE, stage())
        pairing(selected).setBondState(BluetoothDevice.BOND_NONE)
        controller.updatePairingBattery(selected, emptySet())
        assertNull(field("popup"))
        assertEquals(0L, field("batteryObservedAfter"))
    }

    @Test
    @Config(sdk = [35])
    fun olderAndroidRequiresAClassicCapableDeviceAndUsesTheLegacyBondApi() {
        connect()
        val le = device(1, type = BluetoothDevice.DEVICE_TYPE_LE)
        val unknown = device(2, type = BluetoothDevice.DEVICE_TYPE_UNKNOWN)
        val cachedDual = device(4)
        found(le, null)
        found(unknown, null)
        found(cachedDual, BluetoothDevice.DEVICE_TYPE_LE)
        advance(2_000)
        assertEquals(0, pairing(le).requests)
        assertEquals(0, pairing(unknown).requests)
        assertEquals(0, pairing(cachedDual).requests)
        val classic = device(3, type = BluetoothDevice.DEVICE_TYPE_CLASSIC)
        found(classic, null)
        advance(2_000)
        assertEquals(1, pairing(classic).requests)
        assertNull(pairing(classic).transport)
    }

    @Test fun consentConfirmationRequiresTheSelectedBondingDeviceAndRetiresWithItsExactReceiver() {
        controller.close()
        val entered = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val calls = AtomicInteger()
        val guard = AtomicReference<(() -> Boolean)?>()
        controller = NearbyPairingController(context, confirmConsent = { _, stillCurrent ->
            calls.incrementAndGet()
            guard.set(stillCurrent)
            entered.countDown()
            try { awaitCancellation() } finally { canceled.countDown() }
        })
        connect()
        val selected = device(1)
        found(selected)
        advance(2_000)
        val firstReceiver = field("receiver") as BroadcastReceiver
        pairingRequest(selected, 3) // No real BOND_BONDING state yet.
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        bondChanged(selected, BluetoothDevice.BOND_BONDING)
        pairingRequest(device(2), 3)
        pairingRequest(device(3), 1) // PIN/passkey variants always remain Android's responsibility.
        assertEquals(0, calls.get())
        pairingRequest(selected, 3)
        assertTrue("Consent job did not run", entered.await(2, TimeUnit.SECONDS))
        assertTrue(requireNotNull(guard.get()).invoke())
        pairingRequest(selected, 3)
        assertEquals(1, calls.get())

        pairing(selected).setBondState(BluetoothDevice.BOND_NONE)
        bondChanged(selected, BluetoothDevice.BOND_NONE)
        assertFalse(requireNotNull(guard.get()).invoke())
        assertTrue("Retiring a bond receiver must cancel confirmation work", canceled.await(2, TimeUnit.SECONDS))
        advance(2_000)
        found(selected)
        advance(2_000)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        firstReceiver.onReceive(context, request(selected, 3))
        assertFalse("An old consent guard must reject a later bond to the same address", requireNotNull(guard.get()).invoke())
        assertEquals(1, calls.get())
    }

    @Test fun orderedConsentWaitsForConfirmationAndSkipsTheNativeDialogOnlyOnSuccess() {
        controller.close()
        val answer = CompletableDeferred<Boolean>()
        val entered = CountDownLatch(1)
        controller = NearbyPairingController(context, confirmConsent = { _, _ ->
            entered.countDown()
            answer.await()
        })
        val native = nativeFallback()
        val selected = bondingCandidate()
        orderedPairingRequest(selected, 3)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val held = nativeConsent()
        assertEquals(0, native.get())

        answer.complete(true)
        awaitMainCondition { field("pendingConsent") == null }
        assertFinishedConsent(held, aborted = true)
        advance(1_500)
        assertFinishedConsent(held, aborted = true)
    }

    @Test fun aBondedResultAbortsHeldConsentBeforeRetiringItsConfirmationJob() {
        controller.close()
        val entered = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        controller = NearbyPairingController(context, confirmConsent = { _, _ ->
            entered.countDown()
            try { awaitCancellation() } finally { canceled.countDown() }
        })
        val native = nativeFallback()
        val selected = bondingCandidate()
        orderedPairingRequest(selected, 3)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val held = nativeConsent()
        assertEquals(0, native.get())

        // Exercise an early bond callback before its confirmation IPC has returned.
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDED)
        (field("receiver") as BroadcastReceiver).onReceive(context,
            Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED).putExtra(BluetoothDevice.EXTRA_DEVICE, selected)
                .putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED))
        idle()
        assertNull(field("pendingConsent"))
        assertTrue(canceled.await(2, TimeUnit.SECONDS))
        idle()
        assertFinishedConsent(held, aborted = true)
        assertEquals(PopupWindow.Stage.COMPLETE, stage())
    }

    @Test fun closingHeldConsentReturnsItToAndroidAndRejectsLateConfirmation() {
        controller.close()
        val entered = CountDownLatch(1)
        val answer = CompletableDeferred<Boolean>()
        val guard = AtomicReference<(() -> Boolean)?>()
        controller = NearbyPairingController(context, confirmConsent = { _, stillCurrent ->
            guard.set(stillCurrent)
            entered.countDown()
            withContext(NonCancellable) { answer.await() }
        })
        val native = nativeFallback()
        val selected = bondingCandidate()
        orderedPairingRequest(selected, 3)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val held = nativeConsent()
        val confirming = field("consentJob") as Job
        controller.cancelPending()
        idle()
        assertNull(field("pendingConsent"))
        assertEquals(1, native.get())
        assertFalse(requireNotNull(guard.get()).invoke())
        assertFinishedConsent(held, aborted = false)
        answer.complete(true)
        awaitMainCondition { confirming.isCompleted }
        assertFinishedConsent(held, aborted = false)
        advance(1_500)
        assertEquals("Cancellation and timeout must finish a held request only once", 1, native.get())
    }

    @Test fun heldConsentHasAnIndependentFifteenHundredMillisecondNativeFallback() {
        controller.close()
        val entered = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val guard = AtomicReference<(() -> Boolean)?>()
        controller = NearbyPairingController(context, confirmConsent = { _, stillCurrent ->
            guard.set(stillCurrent)
            entered.countDown()
            try { awaitCancellation() } finally { canceled.countDown() }
        })
        val native = nativeFallback()
        val selected = bondingCandidate()
        val startedAt = SystemClock.elapsedRealtime()
        orderedPairingRequest(selected, 3)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        advanceTo(startedAt + 1_499)
        assertNotNull(field("pendingConsent"))
        assertEquals(0, native.get())
        advanceTo(startedAt + 1_500)
        assertNull(field("pendingConsent"))
        assertEquals(1, native.get())
        assertFalse(requireNotNull(guard.get()).invoke())
        assertTrue(canceled.await(2, TimeUnit.SECONDS))
    }

    @Test fun deniedOrFailedConfirmationLeavesTheNativeDialogAvailable() {
        val native = nativeFallback()
        for (fail in listOf(false, true)) {
            controller.close()
            controller = NearbyPairingController(context, confirmConsent = { _, _ ->
                if (fail) throw IllegalStateException("Bridge unavailable")
                false
            })
            val selected = bondingCandidate()
            orderedPairingRequest(selected, 3)
            awaitMainCondition { native.get() == if (fail) 2 else 1 }
            assertNull(field("pendingConsent"))
        }
    }

    @Test fun unrelatedAndNonConsentRequestsNeverWaitForOurConfirmation() {
        controller.close()
        val calls = AtomicInteger()
        controller = NearbyPairingController(context, confirmConsent = { _, _ ->
            calls.incrementAndGet()
            true
        })
        val native = nativeFallback()
        val selected = bondingCandidate()
        orderedPairingRequest(device(2), 3)
        orderedPairingRequest(selected, 2)
        assertEquals(2, native.get())
        assertEquals(0, calls.get())
        assertNull(field("pendingConsent"))
    }

    @Test fun incomingConsentWaitsForFreshStableSelectionWithoutRestartingTheNativeBond() {
        val native = nativeFallback()
        for (foundFirst in listOf(true, false)) {
            controller.close()
            val entered = CountDownLatch(1)
            var completed = 0
            controller = NearbyPairingController(context, confirmConsent = { _, current ->
                assertTrue(current())
                entered.countDown()
                true
            }, onPaired = { completed++ })
            connect()
            val selected = device(1)
            if (foundFirst) found(selected)
            pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
            bondChanged(selected, BluetoothDevice.BOND_BONDING)
            val original = field("receiver")
            val before = native.get()
            orderedPairingRequest(selected, 3)
            assertEquals("An unselected request must remain Android's normal prompt", before + 1, native.get())
            assertNull(field("pendingConsent"))
            if (!foundFirst) found(selected)
            val first = field("firstCandidateAt") as Long
            advanceTo(first + 1_900)
            assertEquals(1L, entered.count)
            assertNull(field("consentJob"))
            advanceTo(first + 2_000)
            assertTrue("Selected incoming consent must reach the same helper path", entered.await(2, TimeUnit.SECONDS))
            assertSame("Selection must retain the receiver that observed the native cycle", original, field("receiver"))
            assertEquals("An incoming native bond must not call createBond again", 0, pairing(selected).requests)
            assertEquals(PopupWindow.Stage.CONNECTING, stage())
            assertNull("Captured requests must not be replayed as ordered broadcasts", field("pendingConsent"))
            pairing(selected).setBondState(BluetoothDevice.BOND_BONDED)
            bondChanged(selected, BluetoothDevice.BOND_BONDED)
            bondChanged(selected, BluetoothDevice.BOND_BONDED)
            assertEquals(PopupWindow.Stage.COMPLETE, stage())
            assertEquals(1, completed)
        }
    }

    @Test fun capturedConsentCannotCrossCycleVariantDismissalOrAmbiguity() {
        for (retirement in listOf("cycle", "variant", "dismiss", "ambiguity")) {
            controller.close()
            val calls = AtomicInteger()
            controller = NearbyPairingController(context, confirmConsent = { _, _ -> calls.incrementAndGet(); true })
            connect()
            val selected = device(1)
            pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
            bondChanged(selected, BluetoothDevice.BOND_BONDING)
            orderedPairingRequest(selected, 3)
            val retiredReceiver = field("receiver") as BroadcastReceiver
            when (retirement) {
                "cycle" -> {
                    pairing(selected).setBondState(BluetoothDevice.BOND_NONE)
                    bondChanged(selected, BluetoothDevice.BOND_NONE)
                    pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
                    bondChanged(selected, BluetoothDevice.BOND_BONDING)
                }
                "variant" -> orderedPairingRequest(selected, 2)
                "dismiss" -> {
                    controller.cancelPending()
                    idle()
                    connect()
                }
            }
            found(selected)
            if (retirement == "ambiguity") found(device(2))
            advance(2_000)
            assertEquals("$retirement must retire the captured consent", 0, calls.get())
            assertNull("$retirement must not start confirmation work", field("consentJob"))
            assertEquals(0, pairing(selected).requests)
            if (retirement == "ambiguity") assertEquals(PopupWindow.Stage.SETUP, stage())
            if (retirement == "dismiss") {
                retiredReceiver.onReceive(context, request(selected, 3))
                assertNull("A retired receiver must not revive its captured request", field("consentJob"))
            }
            if (retirement == "cycle") {
                orderedPairingRequest(selected, 3)
                awaitMainCondition { calls.get() == 1 }
            } else if (retirement == "variant") {
                orderedPairingRequest(selected, 3)
                assertNull("Duplicates cannot renew a retired hint in the same native cycle", field("consentJob"))
                assertEquals(0, calls.get())
            }
        }
    }

    @Test fun alreadyCompletedIncomingBondRequiresThisAttemptsNativeBondingEvidence() {
        for (observed in listOf(true, false)) {
            controller.close()
            var completed = 0
            controller = NearbyPairingController(context, confirmConsent = { _, _ -> false }, onPaired = { completed++ })
            connect()
            val selected = device(1)
            if (observed) {
                pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
                bondChanged(selected, BluetoothDevice.BOND_BONDING)
            }
            pairing(selected).setBondState(BluetoothDevice.BOND_BONDED)
            bondChanged(selected, BluetoothDevice.BOND_BONDED)
            found(selected)
            advance(2_000)
            assertEquals(0, pairing(selected).requests)
            assertEquals(if (observed) 1 else 0, completed)
            assertEquals(if (observed) PopupWindow.Stage.COMPLETE else PopupWindow.Stage.SETUP, stage())
        }
    }

    @Test fun aChangedVariantRetiresInFlightConsentWithoutAbortingItsNativeFallback() {
        controller.close()
        val entered = CountDownLatch(1)
        val answer = CompletableDeferred<Boolean>()
        val guard = AtomicReference<(() -> Boolean)?>()
        controller = NearbyPairingController(context, confirmConsent = { _, current ->
            guard.set(current)
            entered.countDown()
            withContext(NonCancellable) { answer.await() }
        })
        val selected = bondingCandidate()
        orderedPairingRequest(selected, 3)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val held = nativeConsent()
        val confirming = field("consentJob") as Job
        orderedPairingRequest(selected, 2)
        assertFalse(requireNotNull(guard.get()).invoke())
        assertNull(field("pendingConsent"))
        assertFinishedConsent(held, aborted = false)
        answer.complete(true)
        awaitMainCondition { confirming.isCompleted }
        assertFinishedConsent(held, aborted = false)
    }

    @Test fun capturedIncomingConsentKeepsItsOwnHelperDeadlineWithoutHoldingTheNativePrompt() {
        controller.close()
        val entered = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val guard = AtomicReference<(() -> Boolean)?>()
        controller = NearbyPairingController(context, confirmConsent = { _, current ->
            guard.set(current)
            entered.countDown()
            try { awaitCancellation() } finally { canceled.countDown() }
        })
        val native = nativeFallback()
        connect()
        val selected = device(1)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        bondChanged(selected, BluetoothDevice.BOND_BONDING)
        orderedPairingRequest(selected, 3)
        found(selected)
        advance(2_000)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertEquals(1, native.get())
        assertNull(field("pendingConsent"))
        advance(1_500)
        assertTrue(canceled.await(2, TimeUnit.SECONDS))
        assertFalse(requireNotNull(guard.get()).invoke())
        assertNull(field("consentJob"))
        assertEquals("The original native request remains available", 1, native.get())
    }

    @Test fun aQueuedNoneCannotCarryOldConsentIntoASynchronouslyStartedLocalBond() {
        controller.close()
        val calls = AtomicInteger()
        controller = NearbyPairingController(context, confirmConsent = { _, current ->
            assertTrue(current())
            calls.incrementAndGet()
            true
        })
        connect()
        val selected = device(1)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        bondChanged(selected, BluetoothDevice.BOND_BONDING)
        orderedPairingRequest(selected, 3)
        // Native state can reach NONE before the corresponding broadcast is delivered.
        pairing(selected).setBondState(BluetoothDevice.BOND_NONE)
        pairing(selected).onCreateBond = { pairing(selected).setBondState(BluetoothDevice.BOND_BONDING) }
        found(selected)
        advance(2_000)
        assertEquals(1, pairing(selected).requests)
        assertEquals("A new local bond must not reuse the prior native cycle's consent", 0, calls.get())
        assertNull(field("consentJob"))
        bondChanged(selected, BluetoothDevice.BOND_BONDING)
        orderedPairingRequest(selected, 3)
        awaitMainCondition { calls.get() == 1 }
    }

    @Test fun twoPreselectionNativeCyclesKeepDelayedConsentThroughoutOneContinuousInquiry() {
        val native = nativeFallback()
        for (bondingBroadcastFirst in listOf(true, false)) {
            controller.close()
            val entered = CountDownLatch(1)
            val calls = AtomicInteger()
            controller = NearbyPairingController(context, confirmConsent = { _, current ->
                assertTrue(current())
                calls.incrementAndGet()
                entered.countDown()
                true
            })
            connect()
            val selected = device(1)
            advance(3_624)
            pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
            bondChanged(selected, BluetoothDevice.BOND_BONDING)
            advance(106)
            pairing(selected).setBondState(BluetoothDevice.BOND_NONE)
            bondChanged(selected, BluetoothDevice.BOND_NONE)
            assertTrue("An unselected failed native cycle must not stop inquiry", adapter.isDiscovering)
            advance(1_808)
            pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
            if (bondingBroadcastFirst) bondChanged(selected, BluetoothDevice.BOND_BONDING)
            val beforeNative = native.get()
            orderedPairingRequest(selected, 3)
            if (!bondingBroadcastFirst) bondChanged(selected, BluetoothDevice.BOND_BONDING)
            assertEquals("Unselected consent remains available to Android", beforeNative + 1, native.get())
            assertNull(field("pendingConsent"))
            assertNull(field("consentJob"))
            advance(3_660)
            found(selected)
            val foundAt = field("firstCandidateAt") as Long
            advanceTo(foundAt + 1_900)
            assertTrue(adapter.isDiscovering)
            assertEquals(0, calls.get())
            assertEquals(0, pairing(selected).requests)
            advanceTo(foundAt + 2_000)
            assertTrue("The second cycle's consent must survive either broadcast ordering", entered.await(2, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            assertEquals("The native bond must be adopted without createBond", 0, pairing(selected).requests)
            assertFalse(adapter.isDiscovering)
            assertEquals(PopupWindow.Stage.CONNECTING, stage())
            pairing(selected).setBondState(BluetoothDevice.BOND_BONDED)
            bondChanged(selected, BluetoothDevice.BOND_BONDED)
            assertEquals(PopupWindow.Stage.COMPLETE, stage())
        }
    }

    @Test fun duplicateConsentCannotRenewTheFifteenSecondWindowAtItsExactBoundary() {
        controller.close()
        val calls = AtomicInteger()
        controller = NearbyPairingController(context, confirmConsent = { _, _ -> calls.incrementAndGet(); true })
        connect()
        val selected = device(1)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        bondChanged(selected, BluetoothDevice.BOND_BONDING)
        val receivedAt = SystemClock.elapsedRealtime()
        orderedPairingRequest(selected, 3)
        assertEquals("The fixture must retain the request's exact receipt clock", receivedAt, SystemClock.elapsedRealtime())
        val original = field("receiver")
        advanceTo(receivedAt + 13_000)
        found(selected)
        assertEquals(receivedAt + 13_000, field("firstCandidateAt"))
        advanceTo(receivedAt + 14_900)
        orderedPairingRequest(selected, 3)
        assertEquals(0, calls.get())
        advanceTo(receivedAt + 15_000)
        assertSame("Inquiry completion must not mask hint expiry by retiring the receiver", original, field("receiver"))
        assertEquals(BluetoothDevice.BOND_BONDING, selected.bondState)
        assertEquals(PopupWindow.Stage.CONNECTING, stage())
        assertEquals("Exactly fifteen seconds expires the original request despite its duplicate", 0, calls.get())
        assertNull(field("consentJob"))
        assertEquals(0, pairing(selected).requests)
        orderedPairingRequest(selected, 3)
        assertNull("A duplicate after expiry cannot revive that cycle's request", field("consentJob"))
        assertEquals(0, calls.get())
    }

    @Test fun lateSelectedConsentGetsOnlyItsOriginalRemainingHintTime() {
        controller.close()
        val entered = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val guard = AtomicReference<(() -> Boolean)?>()
        val calls = AtomicInteger()
        controller = NearbyPairingController(context, confirmConsent = { _, current ->
            calls.incrementAndGet()
            guard.set(current)
            entered.countDown()
            try { awaitCancellation() } finally { canceled.countDown() }
        })
        connect()
        val selected = device(1)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        bondChanged(selected, BluetoothDevice.BOND_BONDING)
        val receivedAt = SystemClock.elapsedRealtime()
        orderedPairingRequest(selected, 3)
        assertEquals(receivedAt, SystemClock.elapsedRealtime())
        val original = field("receiver")
        advanceTo(receivedAt + 12_500)
        found(selected)
        assertEquals(receivedAt + 12_500, field("firstCandidateAt"))
        advanceTo(receivedAt + 14_500)
        assertTrue("A valid late selection must still reach confirmation", entered.await(2, TimeUnit.SECONDS))
        assertSame(original, field("receiver"))
        advanceTo(receivedAt + 14_900)
        orderedPairingRequest(selected, 3)
        advanceTo(receivedAt + 14_999)
        assertTrue(requireNotNull(guard.get()).invoke())
        assertNotNull(field("consentJob"))
        advanceTo(receivedAt + 15_000)
        assertTrue("The helper gets the remaining 500ms, not a fresh 1500ms", canceled.await(2, TimeUnit.SECONDS))
        assertFalse(requireNotNull(guard.get()).invoke())
        assertNull(field("consentJob"))
        assertEquals("A duplicate must not start or extend another confirmation", 1, calls.get())
        assertEquals(BluetoothDevice.BOND_BONDING, selected.bondState)
    }

    @Test fun workerHeldConsentWaitsForDelayedFreshSelectionBeforeSkippingTheNativePrompt() {
        controller.close()
        val gate = TestConsentWatch()
        val entered = CountDownLatch(1)
        controller = NearbyPairingController(context, confirmConsent = { _, current ->
            assertTrue(current())
            entered.countDown()
            true
        }, consentGateFactory = { _, callback -> gate.apply { offer = callback } })
        connect()
        val selected = device(1)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        bondChanged(selected, BluetoothDevice.BOND_BONDING)
        pairingRequest(selected, 3)
        val pending = gate.send(selected)
        val owner = field("receiver")
        advance(3_660)
        assertNull(field("consentJob"))
        found(selected)
        val first = field("firstCandidateAt") as Long
        advanceTo(first + 1_999)
        assertEquals(1L, entered.count)
        advanceTo(first + 2_000)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        awaitMainCondition { pending.answers.isNotEmpty() }
        assertEquals(listOf(true), pending.answers)
        assertSame(owner, field("receiver"))
        assertEquals(0, pairing(selected).requests)
        controller.cancelPending()
        idle()
        assertTrue(gate.closed)
        assertEquals("Retiring the gate must not finish accepted consent twice", listOf(true), pending.answers)
    }

    @Test fun anArmedWorkerCapturesTheSelectedRequestBeforeAnyShizukuConfirmation() {
        controller.close()
        val gate = TestConsentWatch()
        val calls = AtomicInteger()
        controller = NearbyPairingController(context, confirmConsent = { _, current ->
            assertTrue(current())
            calls.incrementAndGet()
            true
        }, consentGateFactory = { _, callback -> gate.apply { offer = callback } })
        val selected = bondingCandidate()
        orderedPairingRequest(selected, 3)
        assertNull(field("consentJob"))
        assertNull(field("pendingConsent"))
        assertEquals(0, calls.get())
        val pending = gate.send(selected)
        awaitMainCondition { pending.answers.isNotEmpty() }
        assertEquals(1, calls.get())
        assertEquals(listOf(true), pending.answers)
    }

    @Test fun workerConsentRetirementAndLateCallbacksAlwaysLeaveTheNativeFallbackAvailable() {
        for (retirement in listOf("cycle", "variant", "ambiguity", "foreign", "dismiss")) {
            controller.close()
            val gate = TestConsentWatch()
            val calls = AtomicInteger()
            controller = NearbyPairingController(context, confirmConsent = { _, _ -> calls.incrementAndGet(); true },
                consentGateFactory = { _, callback -> gate.apply { offer = callback } })
            connect()
            val selected = device(1)
            pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
            bondChanged(selected, BluetoothDevice.BOND_BONDING)
            pairingRequest(selected, 3)
            val receipt = SystemClock.elapsedRealtime()
            val pending = gate.send(selected, receipt)
            val originalOffer = gate.offer
            assertSame(pending, field("pendingConsent"))
            when (retirement) {
                "cycle" -> {
                    pairing(selected).setBondState(BluetoothDevice.BOND_NONE)
                    bondChanged(selected, BluetoothDevice.BOND_NONE)
                    pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
                    bondChanged(selected, BluetoothDevice.BOND_BONDING)
                    pairingRequest(selected, 3)
                }
                "variant" -> pairingRequest(selected, 2)
                "ambiguity" -> { found(selected); found(device(2)) }
                "foreign" -> { found(device(2)); advance(2_000) }
                "dismiss" -> { controller.cancelPending(); idle() }
            }
            assertEquals("$retirement must release the exact worker request", listOf(false), pending.answers)
            val late = TestConsent(receipt + 7_000).also { originalOffer(selected, receipt, it) }
            assertEquals("$retirement must reject a late callback", listOf(false), late.answers)
            assertEquals(0, calls.get())
        }
    }

    @Test fun workerHoldExpiryCannotBeRenewedAndLateSelectionUsesOnlyTheRemainingBudget() {
        controller.close()
        val gate = TestConsentWatch()
        val entered = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val guard = AtomicReference<(() -> Boolean)?>()
        controller = NearbyPairingController(context, confirmConsent = { _, current ->
            guard.set(current)
            entered.countDown()
            try { awaitCancellation() } finally { canceled.countDown() }
        }, consentGateFactory = { _, callback -> gate.apply { offer = callback } })
        connect()
        val selected = device(1)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        bondChanged(selected, BluetoothDevice.BOND_BONDING)
        pairingRequest(selected, 3)
        val pending = gate.send(selected)
        val deadline = pending.deadline
        advanceTo(deadline - 2_500)
        found(selected)
        advanceTo((field("firstCandidateAt") as Long) + 2_000)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertTrue(requireNotNull(guard.get()).invoke())
        pairingRequest(selected, 3)
        advanceTo(deadline - 1)
        assertTrue(pending.answers.isEmpty())
        advanceTo(deadline)
        assertEquals(listOf(false), pending.answers)
        assertFalse(requireNotNull(guard.get()).invoke())
        assertTrue(canceled.await(2, TimeUnit.SECONDS))
        val duplicate = gate.send(selected)
        assertEquals(listOf(false), duplicate.answers)
    }

    @Test fun anUnavailableShizukuBridgeNeverKeepsAWorkerRequestFromAndroid() {
        controller.close()
        val gate = TestConsentWatch()
        controller = NearbyPairingController(context,
            consentGateFactory = { _, callback -> gate.apply { offer = callback } })
        connect()
        val selected = device(1)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        bondChanged(selected, BluetoothDevice.BOND_BONDING)
        pairingRequest(selected, 3)
        val pending = gate.send(selected)
        assertEquals(listOf(false), pending.answers)
        assertNull(field("pendingConsent"))
        assertNull(field("consentJob"))
    }

    @Test fun aMissingWorkerCaptureFallsBackAfterOneShortGraceInsteadOfStrandingConsent() {
        controller.close()
        val gate = TestConsentWatch()
        val entered = CountDownLatch(1)
        controller = NearbyPairingController(context, confirmConsent = { _, current ->
            assertTrue(current())
            entered.countDown()
            true
        }, consentGateFactory = { _, callback -> gate.apply { offer = callback } })
        val selected = bondingCandidate()
        orderedPairingRequest(selected, 3)
        assertNull(field("consentJob"))
        advance(100)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        awaitMainCondition { field("consentJob") == null }
        assertNull(field("pendingConsent"))
    }

    private class TestConsentWatch : PairingConsentWatch {
        var closed = false
        override val isWatching get() = !closed
        lateinit var offer: (BluetoothDevice, Long, PairingConsentGate.Consent) -> Unit
        override fun close() { closed = true }
        fun send(device: BluetoothDevice, receivedAt: Long = SystemClock.elapsedRealtime()) = TestConsent(receivedAt + 7_000).also {
            offer(device, receivedAt, it)
        }
    }

    private class TestConsent(override val deadline: Long) : PairingConsentGate.Consent {
        val answers = mutableListOf<Boolean>()
        override fun finish(accepted: Boolean) { answers += accepted }
    }

    private fun nativeConsent(): BroadcastReceiver.PendingResult {
        val held = requireNotNull(field("pendingConsent"))
        return held.javaClass.getDeclaredField("result").apply { isAccessible = true }.get(held) as BroadcastReceiver.PendingResult
    }

    private fun connect() {
        controller.onNearbyDevice(NearbyAirPods.Event(0x2720, true))
        idle()
        assertEquals(PopupWindow.Stage.NEARBY, stage())
        assertTrue(button().performClick())
        idle()
    }

    private fun newController() = NearbyPairingController(context, confirmConsent = { _, _ -> false })

    private fun bondingCandidate(): BluetoothDevice {
        connect()
        val selected = device(1)
        found(selected)
        advance(2_000)
        pairing(selected).setBondState(BluetoothDevice.BOND_BONDING)
        bondChanged(selected, BluetoothDevice.BOND_BONDING)
        return selected
    }

    private fun nativeFallback(): AtomicInteger {
        val received = AtomicInteger()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { received.incrementAndGet() }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST), Context.RECEIVER_EXPORTED)
        nativeReceivers += receiver
        return received
    }

    private fun assertFinishedConsent(pending: BroadcastReceiver.PendingResult, aborted: Boolean) {
        // Robolectric 4.17 omits PendingResult's abort flag from its simulated ordered chain.
        assertEquals(aborted, pending.getAbortBroadcast())
        assertTrue(Shadow.extract<ShadowBroadcastPendingResult>(pending).future.isDone)
    }

    private fun field(name: String): Any? = NearbyPairingController::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(controller)

    private fun button(): Button {
        return popupView().findViewById(R.id.pairing_primary)
    }

    private fun popupView(): View {
        val popup = field("popup") as PopupWindow
        return PopupWindow::class.java.getDeclaredField("mView").apply { isAccessible = true }.get(popup) as View
    }

    private fun stage(): PopupWindow.Stage = PopupWindow::class.java.getDeclaredField("stage")
        .apply { isAccessible = true }.get(field("popup")) as PopupWindow.Stage

    private fun device(id: Int, name: String? = "AirPods Pro 3", type: Int = BluetoothDevice.DEVICE_TYPE_DUAL): BluetoothDevice =
        adapter.getRemoteDevice("00:11:22:33:44:${id.toString(16).padStart(2, '0')}").also {
            pairing(it).setName(name)
            pairing(it).setType(type)
            pairing(it).setBondState(BluetoothDevice.BOND_NONE)
        }

    private fun discovery(device: BluetoothDevice, type: Int?): Intent = Intent(BluetoothDevice.ACTION_FOUND)
        .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
        .apply { if (type != null) putExtra(BluetoothDevice.EXTRA_DISCOVERY_RESULT_TYPE, type) }

    private fun found(device: BluetoothDevice, type: Int? = BluetoothDevice.DEVICE_TYPE_CLASSIC) {
        context.sendBroadcast(discovery(device, type))
        idle()
    }

    private fun nameChanged(device: BluetoothDevice, name: String) {
        pairing(device).setName(name)
        context.sendBroadcast(Intent(BluetoothDevice.ACTION_NAME_CHANGED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device).putExtra(BluetoothDevice.EXTRA_NAME, name))
        idle()
    }

    private fun request(device: BluetoothDevice, variant: Int) = Intent(BluetoothDevice.ACTION_PAIRING_REQUEST)
        .putExtra(BluetoothDevice.EXTRA_DEVICE, device).putExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, variant)

    private fun pairingRequest(device: BluetoothDevice, variant: Int) {
        context.sendBroadcast(request(device, variant))
        idle()
    }

    private fun orderedPairingRequest(device: BluetoothDevice, variant: Int) {
        context.sendOrderedBroadcast(request(device, variant), null)
        idle()
    }

    private fun bondChanged(device: BluetoothDevice, state: Int) {
        context.sendBroadcast(Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device).putExtra(BluetoothDevice.EXTRA_BOND_STATE, state))
        idle()
    }

    private fun pairing(device: BluetoothDevice): PairingDeviceShadow = Shadow.extract(device)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    private fun advanceTo(target: Long) {
        val remaining = target - SystemClock.elapsedRealtime()
        assertTrue("Fixture work crossed the requested timer boundary", remaining >= 0)
        advance(remaining)
    }

    private fun awaitMainCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(5)
        }
        assertTrue("Confirmation result did not return to the main thread", condition())
    }
}

@Implements(BluetoothDevice::class)
class PairingDeviceShadow : ShadowBluetoothDevice() {
    var requests = 0
    var transport: Int? = null
    var discoveringAtBond = false
    var bondAt = 0L
    var onCreateBond: (() -> Unit)? = null

    @Implementation public override fun createBond(): Boolean = record(null)
    @Implementation public override fun createBond(transport: Int): Boolean = record(transport)

    private fun record(selectedTransport: Int?): Boolean {
        requests++
        transport = selectedTransport
        bondAt = SystemClock.elapsedRealtime()
        discoveringAtBond = BluetoothAdapter.getDefaultAdapter()!!.isDiscovering
        onCreateBond?.invoke()
        return true
    }
}
