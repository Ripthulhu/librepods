package me.kavishdevar.librepods.core.bluetooth.ble

import org.junit.Assert.*
import org.junit.Test

class NearbyAirPodsTest {
    private fun report(model: Int = 0x2720, open: Boolean = true): ByteArray = ByteArray(27).apply {
        this[0] = 7
        this[1] = 25
        this[2] = 1
        this[3] = (model shr 8).toByte()
        this[4] = model.toByte()
        this[5] = 0x35
        this[8] = if (open) 0x31 else 0x38
    }

    @Test fun acceptsOnlyCompleteKnownLidReports() {
        val good = report()
        assertEquals(NearbyAirPods.Event(0x2720, true), NearbyAirPods.lidEvent(good))
        assertEquals(NearbyAirPods.Event(0x2720, false), NearbyAirPods.lidEvent(report(open = false)))
        for (size in 0 until good.size) assertNull(NearbyAirPods.lidEvent(good.copyOf(size)))
        for ((offset, value) in listOf(1 to 24, 1 to 26, 2 to 0, 2 to 7, 5 to 0x20)) {
            assertNull(NearbyAirPods.lidEvent(good.copyOf().also { it[offset] = value.toByte() }))
        }
        assertNull(NearbyAirPods.lidEvent(report(0x0a20)))
        assertNull(NearbyAirPods.lidEvent(report(0xffff)))
        assertNull(NearbyAirPods.lidEvent(ByteArray(255)))
    }

    @Test fun validatesWholeCompoundAdvertisementAndRejectsDuplicateRecords() {
        val compound = byteArrayOf(0x10, 1, 0x42) + report() + byteArrayOf(0x15, 0)
        assertNotNull(NearbyAirPods.lidEvent(compound))
        assertNull(NearbyAirPods.lidEvent(compound + byteArrayOf(0)))
        assertNull(NearbyAirPods.lidEvent(report() + report()))
    }

    @Test fun siblingsDoNotRepeatInvitationsAndLidCloseRearmsThem() {
        val visits = NearbyAirPods.Visits()
        val open = NearbyAirPods.Event(0x2720, true)
        val closed = open.copy(lidOpen = false)
        assertEquals(open, visits.observe(open, 0, false))
        assertNull(visits.observe(open, 100, false))
        assertEquals(closed, visits.observe(closed, 200, false))
        assertNull(visits.observe(closed, 201, false))
        assertEquals(open, visits.observe(open, 300, false))
        assertNull(visits.observe(open, 400, false))
        assertEquals(listOf(closed), visits.expire(30_400))
        assertEquals(emptyList<NearbyAirPods.Event>(), visits.expire(30_500))
        assertEquals(open, visits.observe(open, 30_600, false))
    }

    @Test fun trustedBondSuppressesInvitationWithoutSuppressingOtherModels() {
        val visits = NearbyAirPods.Visits()
        val ours = NearbyAirPods.Event(0x2720, true)
        assertNull(visits.observe(ours, 0, true))
        assertEquals(NearbyAirPods.Event(0x1920, true), visits.observe(NearbyAirPods.Event(0x1920, true), 1, false))
        visits.observe(ours.copy(lidOpen = false), 2, true)
        assertEquals(ours, visits.observe(ours, 3, false))
    }

    @Test fun closingOneModelDoesNotCancelANewerInvitation() {
        val state = NearbyAirPods.Session()
        val first = state.open(0x2720)!!
        state.close()
        val second = state.open(0x1920)!!
        assertFalse(state.isCurrent(first))
        assertTrue(state.isCurrent(second))
        assertFalse(state.close(0x2720))
        assertTrue(state.isCurrent(second))
        assertTrue(state.close(0x1920))
        assertFalse(state.isCurrent(second))
        state.open(0x1920)
        assertFalse(state.isCurrent(second))
    }

    @Test fun namesAreOnlyCandidateHintsAndExcludeUnrelatedDeviceFamilies() {
        assertTrue(NearbyAirPods.matchesName(0x2720, "AirPods Pro"))
        assertTrue(NearbyAirPods.matchesName(0x2720, "AirPods Pro 3"))
        assertTrue(NearbyAirPods.matchesName(0x2720, "Dylan’s AirPods Pro"))
        assertTrue(NearbyAirPods.matchesName(0x1920, "AirPods 4"))
        assertFalse(NearbyAirPods.matchesName(0x2720, "AirPods 4"))
        assertFalse(NearbyAirPods.matchesName(0x1920, "AirPods Pro"))
        assertFalse(NearbyAirPods.matchesName(0x2720, "AirPods Max"))
        assertFalse(NearbyAirPods.matchesName(0xffff, "AirPods"))
        assertFalse(NearbyAirPods.matchesName(0x2720, null))
        assertFalse(NearbyAirPods.matchesName(0x2720, "Pixel Buds Pro"))
        assertTrue(NearbyAirPods.matchesName(0x1920, "Proctor’s AirPods"))
        assertTrue(NearbyAirPods.matchesName(0x2720, "Proctor’s AirPods"))
        assertTrue(NearbyAirPods.matchesName(0x1920, "Maxwell’s AirPods"))
        assertTrue(NearbyAirPods.matchesName(0x2720, "Maxwell’s AirPods Pro"))
        assertFalse(NearbyAirPods.matchesName(0x2720, "AirPods Pro Max"))
    }

    @Test fun renamedProWithoutAFamilySuffixRemainsAnEligibleClassicCandidate() {
        val discovery = NearbyAirPods.Discovery(0x2720)
        assertTrue(discovery.observe("AA:BB:CC:DD:EE:01", "Dylan's Airpods", 100))
        discovery.finish()
        assertEquals("AA:BB:CC:DD:EE:01", discovery.automatic(2_100))
        assertTrue(NearbyAirPods.matchesName(0x2720, "AirPods"))
        assertTrue(NearbyAirPods.matchesName(0x2720, "Max’s AirPods"))
        assertTrue(NearbyAirPods.matchesName(0x1920, "Pro’s AirPods"))
        assertFalse(NearbyAirPods.matchesName(0x2720, "Dylan’s AirPods 3"))
        assertFalse(NearbyAirPods.matchesName(0x2720, "Dylan’s AirPods Pro 2"))
    }

    @Test fun renamedAndExplicitProCandidatesAreStillAmbiguous() {
        val discovery = NearbyAirPods.Discovery(0x2720)
        discovery.observe("AA:BB:CC:DD:EE:01", "Dylan's Airpods", 100)
        discovery.observe("AA:BB:CC:DD:EE:02", "Other AirPods Pro 3", 200)
        discovery.finish()
        assertTrue(discovery.ambiguous)
        assertNull(discovery.automatic(20_000))
    }

    @Test fun onlyASoleStableClassicIdentityCanPairAutomatically() {
        val discovery = NearbyAirPods.Discovery(0x2720)
        assertFalse(discovery.observe("unrelated", "Pixel Buds Pro", 0))
        assertNull(discovery.readyInMillis(10_000))
        assertNull(discovery.automatic(10_000))
        assertTrue(discovery.observe("AA:BB:CC:DD:EE:01", "AirPods Pro", 100))
        assertEquals(2_000L, discovery.readyInMillis(100))
        assertEquals(1L, discovery.readyInMillis(2_099))
        assertNull(discovery.automatic(2_099))
        discovery.observe("AA:BB:CC:DD:EE:01", "AirPods Pro", 1_000)
        assertEquals(0L, discovery.readyInMillis(2_100))
        assertEquals("AA:BB:CC:DD:EE:01", discovery.automatic(2_100))
        discovery.finish()
        assertEquals("AA:BB:CC:DD:EE:01", discovery.automatic(2_100))
        assertFalse(discovery.observe("AA:BB:CC:DD:EE:02", "AirPods Pro", 15_001))
        assertFalse(discovery.observe("AA:BB:CC:DD:EE:01", "Pixel Buds Pro", 15_002))
        assertEquals("AA:BB:CC:DD:EE:01", discovery.automatic(15_002))
    }

    @Test fun aSecondCandidateBeforeTheSoleDeadlinePreventsAutomaticPairing() {
        val discovery = NearbyAirPods.Discovery(0x2720)
        discovery.observe("AA:BB:CC:DD:EE:01", "AirPods Pro", 100)
        assertNull(discovery.automatic(2_099))
        discovery.observe("AA:BB:CC:DD:EE:02", "AirPods Pro", 2_099)
        assertNull(discovery.readyInMillis(2_100))
        discovery.finish()
        assertNull(discovery.automatic(20_000))
    }

    @Test fun conflictingClassicNameRemovesOnlyThatCandidateAndRestartsTheSurvivorsDeadline() {
        val discovery = NearbyAirPods.Discovery(0x2720)
        discovery.observe("first", "AirPods Pro", 0)
        discovery.observe("second", "Other AirPods Pro", 1_000)
        assertTrue(discovery.ambiguous)
        assertFalse(discovery.observe("second", "Pixel Buds Pro", 2_000))
        assertFalse(discovery.ambiguous)
        assertEquals(2_000L, discovery.readyInMillis(2_000))
        assertNull(discovery.automatic(3_999))
        assertEquals("first", discovery.automatic(4_000))
        assertFalse(discovery.observe("first", "AirPods Pro 2", 4_001))
        assertNull(discovery.readyInMillis(10_000))
        assertNull(discovery.automatic(10_000))
        assertTrue(discovery.observe("first", "AirPods Pro 3", 10_001))
        assertEquals(2_000L, discovery.readyInMillis(10_001))
        assertNull(discovery.automatic(12_000))
        assertEquals("first", discovery.automatic(12_001))
    }

    @Test fun missingNamesAndDuplicateReportsDoNotEraseOrPostponeAKnownClassicCandidate() {
        val discovery = NearbyAirPods.Discovery(0x2720)
        assertFalse(discovery.observe("unknown", null, 0))
        assertTrue(discovery.observe("first", "AirPods Pro 3", 100))
        assertTrue(discovery.observe("first", null, 1_000))
        assertTrue(discovery.observe("first", "Renamed AirPods", 2_099))
        assertEquals(1L, discovery.readyInMillis(2_099))
        assertEquals("first", discovery.automatic(2_100))
    }

    @Test fun aClockBeforeTheSoleObservationCannotMakeTheCandidateReady() {
        val discovery = NearbyAirPods.Discovery(0x2720)
        discovery.observe("first", "AirPods Pro", 100)
        assertEquals(2_000L, discovery.readyInMillis(99))
        assertEquals(2_000L, discovery.readyInMillis(Long.MIN_VALUE))
        assertNull(discovery.automatic(99))
        assertNull(discovery.automatic(Long.MIN_VALUE))
        assertEquals("first", discovery.automatic(Long.MAX_VALUE))
    }

    @Test fun twoClassicAddressesStayAmbiguousRegardlessOfOrderOrRepeatedNames() {
        for (addresses in listOf(listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"),
            listOf("AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:01"))) {
            val discovery = NearbyAirPods.Discovery(0x2720)
            for (index in 0..12) {
                discovery.observe(addresses[index % 2], "AirPods Pro", index * 1_000L)
            }
            discovery.finish()
            assertNull(discovery.automatic(20_000))
        }
    }

    @Test fun reopeningTheSameModelInvalidatesPendingDiscoveryAndBondCallbacks() {
        val session = NearbyAirPods.Session()
        val oldToken = session.open(0x2720)!!
        session.close(0x2720)
        val newToken = session.open(0x2720)!!
        assertFalse(session.isCurrent(oldToken))
        assertTrue(session.isCurrent(newToken))
        assertNull(session.open(0x2720))
        assertTrue(session.isCurrent(newToken))
        session.close()
        val replacedToken = session.open(0x2720)!!
        assertFalse(session.isCurrent(newToken))
        assertTrue(session.isCurrent(replacedToken))
        session.close()
        assertFalse(session.isCurrent(replacedToken))
    }

    @Test fun freshPresenceKeepsAnInvitationFromExpiringAtTheOldDeadline() {
        val visits = NearbyAirPods.Visits()
        val open = NearbyAirPods.Event(0x2720, true)
        assertEquals(open, visits.observe(open, 0, false))
        assertNull(visits.observe(open, 29_999, false))
        assertTrue(visits.expire(30_000).isEmpty())
        assertTrue(visits.expire(59_998).isEmpty())
        assertEquals(listOf(open.copy(lidOpen = false)), visits.expire(59_999))
    }

    @Test fun retryAndBondAttemptsRejectCallbacksFromEarlierWorkWithoutClosingThePopup() {
        val session = NearbyAirPods.Session()
        val invitation = session.open(0x2720)!!
        val discovery = session.beginAttempt(invitation)!!
        val retry = session.beginAttempt(invitation)!!
        assertTrue(session.isCurrent(invitation))
        assertFalse(session.isCurrentAttempt(invitation, discovery))
        assertTrue(session.isCurrentAttempt(invitation, retry))
        val bond = session.beginAttempt(invitation)!!
        assertFalse(session.isCurrentAttempt(invitation, retry))
        assertTrue(session.isCurrentAttempt(invitation, bond))
        session.close()
        assertNull(session.beginAttempt(invitation))
        assertFalse(session.isCurrentAttempt(invitation, bond))
    }

    @Test fun delayedLidReportsCannotReopenOrCloseANewerVisit() {
        val visits = NearbyAirPods.Visits()
        val open = NearbyAirPods.Event(0x2720, true)
        val closed = open.copy(lidOpen = false)
        assertEquals(open, visits.observe(open, 100, false))
        assertNull(visits.observe(closed, 99, false))
        assertEquals(closed, visits.observe(closed, 200, false))
        assertNull(visits.observe(open, 150, false))
        assertEquals(open, visits.observe(open, 201, false))
    }

    @Test fun recognizingTheAlreadyPairedIdentityClosesAnOutstandingInvitationOnce() {
        val visits = NearbyAirPods.Visits()
        val open = NearbyAirPods.Event(0x2720, true)
        assertEquals(open, visits.observe(open, 0, false))
        assertEquals(open.copy(lidOpen = false), visits.observe(open, 1, true))
        assertNull(visits.observe(open, 2, true))
        assertTrue(visits.expire(30_002).isEmpty())
    }

    @Test fun activePairingKeepsRealLidEventsAfterOwnershipBecomesKnown() {
        val visits = NearbyAirPods.Visits()
        val open = NearbyAirPods.Event(0x2720, true)
        val closed = open.copy(lidOpen = false)
        assertEquals(open, visits.observe(open, 0, false))
        assertNull(visits.observe(open, 1, true, protectedModel = open.modelId))
        assertNull(visits.observe(open, 2, true, protectedModel = open.modelId))
        assertTrue(visits.expire(30_002, protectedModel = open.modelId).isEmpty())
        assertEquals(closed, visits.observe(closed, 30_003, true, protectedModel = open.modelId))
        assertNull(visits.observe(closed, 30_004, true, protectedModel = open.modelId))
        assertNull(visits.observe(open, 30_005, true))
    }

    @Test fun scannerShutdownClosesOnlyActiveInvitationsAndClearsTheirDeduplication() {
        val visits = NearbyAirPods.Visits()
        val open = NearbyAirPods.Event(0x2720, true)
        visits.observe(open, 0, false)
        visits.observe(NearbyAirPods.Event(0x1920, true), 1, true)
        assertEquals(listOf(open.copy(lidOpen = false)), visits.clear())
        assertTrue(visits.clear().isEmpty())
        assertEquals(open, visits.observe(open, 2, false))
    }

    @Test fun duplicateAndDifferentModelReportsCannotReplaceAnOpenInvitation() {
        val session = NearbyAirPods.Session()
        assertNull(session.open(0xffff))
        val token = session.open(0x2720)!!
        val attempt = session.beginAttempt(token, 100)!!
        assertNull(session.open(0x2720))
        assertNull(session.open(0x1920))
        assertEquals(0x2720, session.modelId)
        assertTrue(session.isCurrentAttempt(token, attempt))
    }

    @Test fun microphoneOrRecoveryBlockRetiresWorkAndDoesNotReopenItAfterUnblocking() {
        val session = NearbyAirPods.Session()
        val token = session.open(0x2720)!!
        val attempt = session.beginAttempt(token, 100)!!
        session.setBlocked(true)
        assertNull(session.modelId)
        assertFalse(session.isCurrentAttempt(token, attempt))
        assertEquals(0L, session.remainingMillis(token, attempt, 101))
        assertNull(session.open(0x2720))
        assertNull(session.beginAttempt(token, 102))
        session.setBlocked(false)
        assertFalse(session.isCurrent(token))
        val reopened = session.open(0x2720)!!
        assertNotEquals(token, reopened)
        assertTrue(session.isCurrent(reopened))
    }

    @Test fun discoveryAndBondingShareOneSixtySecondRequestUntilExplicitRetry() {
        val session = NearbyAirPods.Session()
        val token = session.open(0x2720)!!
        val attempt = session.beginAttempt(token, 10_000)!!
        assertEquals(60_000L, session.remainingMillis(token, attempt, 10_000))
        assertEquals(45_000L, session.remainingMillis(token, attempt, 25_000))
        assertEquals(1L, session.remainingMillis(token, attempt, 69_999))
        assertEquals(0L, session.remainingMillis(token, attempt, 70_000))
        assertEquals(0L, session.remainingMillis(token, attempt, Long.MAX_VALUE))
        assertEquals(60_000L, session.remainingMillis(token, attempt, 9_999))
        val retry = session.beginAttempt(token, 80_000)!!
        assertEquals(0L, session.remainingMillis(token, attempt, 80_000))
        assertEquals(60_000L, session.remainingMillis(token, retry, 80_000))
    }

    @Test fun closingThenReopeningDuringOldDismissalCannotRetireTheNewAttempt() {
        val session = NearbyAirPods.Session()
        val old = session.open(0x2720)!!
        val oldAttempt = session.beginAttempt(old, 0)!!
        session.close(0x2720)
        val current = session.open(0x2720)!!
        val attempt = session.beginAttempt(current, 10)!!
        assertFalse(session.isCurrent(old))
        assertFalse(session.isCurrentAttempt(old, oldAttempt))
        assertEquals(0L, session.remainingMillis(old, oldAttempt, 20))
        assertTrue(session.isCurrentAttempt(current, attempt))
    }

    @Test fun closingOtherCasesAllowsAFreshUnambiguousDiscoveryRound() {
        val ambiguous = NearbyAirPods.Discovery(0x2720)
        ambiguous.observe("first", "AirPods Pro 3", 0)
        ambiguous.observe("second", "Other AirPods Pro", 1)
        ambiguous.finish()
        assertTrue(ambiguous.ambiguous)
        assertNull(ambiguous.automatic(10_000))
        val next = NearbyAirPods.Discovery(0x2720)
        next.observe("first", "AirPods Pro 3", 12_000)
        next.finish()
        assertFalse(next.ambiguous)
        assertNull(next.automatic(13_999))
        assertEquals("first", next.automatic(14_000))
        assertNull(ambiguous.automatic(14_000))
    }

    @Test fun modelSelectsGestureAndRejectsExplicitlyDifferentClassicGenerations() {
        for (model in listOf(0x1920, 0x1b20, 0x2720, 0x3020, 0x3620)) {
            assertTrue(NearbyAirPods.usesDoubleTap(model))
        }
        for (model in listOf(0x0220, 0x0f20, 0x1320, 0x0e20, 0x1420, 0x2420)) {
            assertFalse(NearbyAirPods.usesDoubleTap(model))
        }
        assertFalse(NearbyAirPods.matchesName(0x2720, "Dylan’s AirPods Pro 2"))
        assertFalse(NearbyAirPods.matchesName(0x1920, "AirPods 3"))
        assertTrue(NearbyAirPods.matchesName(0x2420, "AirPods Pro 2"))
        assertTrue(NearbyAirPods.matchesName(0x1b20, "AirPods 4"))
        assertTrue(NearbyAirPods.matchesName(0x2720, "Dylan’s AirPods Pro"))
    }

    @Test fun interactionPausesAbsenceExpiryButNeverSuppressesPhysicalLidClose() {
        val visits = NearbyAirPods.Visits()
        val active = NearbyAirPods.Event(0x2720, true)
        val other = NearbyAirPods.Event(0x1920, true)
        visits.observe(active, 0, false)
        visits.observe(other, 1, false)
        assertEquals(listOf(other.copy(lidOpen = false)), visits.expire(30_001, active.modelId))
        assertTrue(visits.expire(60_000, active.modelId).isEmpty())
        assertEquals(active.copy(lidOpen = false), visits.observe(active.copy(lidOpen = false), 60_001, false))
        assertEquals(active, visits.observe(active, 60_002, false))
        assertTrue(visits.expire(90_002, active.modelId).isEmpty())
        assertEquals(listOf(active.copy(lidOpen = false)), visits.expire(90_002))
    }
}
