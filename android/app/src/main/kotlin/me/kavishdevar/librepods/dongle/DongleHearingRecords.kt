package me.kavishdevar.librepods.dongle

import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import me.kavishdevar.librepods.core.bluetooth.att.types.HearingDiagnosticUuids
import me.kavishdevar.librepods.core.bluetooth.att.types.accommodationsTransparencyPreset
import me.kavishdevar.librepods.core.bluetooth.att.types.customTransparencyEnabled
import me.kavishdevar.librepods.core.bluetooth.att.types.hearingAidSettingsWritable
import me.kavishdevar.librepods.core.bluetooth.att.types.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.parseSavedHearingDiagnostics
import me.kavishdevar.librepods.core.bluetooth.att.types.parseTransparencySettingsResponse
import me.kavishdevar.librepods.core.devices.AccommodationsAudiogram
import me.kavishdevar.librepods.core.devices.AccommodationsPlan
import me.kavishdevar.librepods.core.devices.AccommodationsPreset
import me.kavishdevar.librepods.core.devices.AccommodationsReport
import me.kavishdevar.librepods.core.devices.AccommodationsRequestState
import me.kavishdevar.librepods.core.devices.AccommodationsResult
import me.kavishdevar.librepods.core.devices.AccommodationsSendOutcome
import me.kavishdevar.librepods.core.devices.HearingMode
import me.kavishdevar.librepods.core.devices.HearingModes
import me.kavishdevar.librepods.core.devices.ReceivedAccommodations
import me.kavishdevar.librepods.core.devices.SettingsReadStatus
import me.kavishdevar.librepods.dongle.DongleControlProtocol.CachedSlot
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Rpc

/** Numbers handed out to control sessions, shared by every controller in the process. */
private val accommodationsSessions = AtomicLong(0)

/**
 * The hearing profile, Custom Transparency, accommodations and saved diagnostics records, read and
 * written over ATT and AACP through the dongle.
 *
 * Owns the last hearing profile read, the accommodations write awaiting its device report and the
 * cached report revisions of the hearing slots. Callers hold the controller's io lock; every state
 * change is published through [lease], under the same lifecycle guard as before.
 */
internal class DongleHearingRecords(
    private val state: MutableStateFlow<DongleState>,
    private val lease: DongleEditingLease,
) {
    private data class AccommodationsWriteBase(val client: DongleSession, val session: Long, val revision: Long,
        val plan: AccommodationsPlan, val clinical: ByteArray)

    /** The hearing profile bytes last read or confirmed. */
    var profileData: ByteArray? = null
        private set
    private var profileCharacteristic: DongleAtt.Characteristic? = null
    private var accommodationsWriteBase: AccommodationsWriteBase? = null
    /** Report revision last seen per hearing cached slot. */
    private val cachedRevisions = mutableMapOf<Int, Long>()

    fun nextControlSession(): Long = accommodationsSessions.incrementAndGet()

    fun clear() {
        cachedRevisions.clear()
        profileData = null
        profileCharacteristic = null
        accommodationsWriteBase = null
    }

    fun supported(): Boolean = state.value.metadata?.modelNumber in hearingModelNumbers

    private fun att(client: DongleSession) = DongleAtt { client.transact(Rpc.Operation.SEND_ATT_REQUEST, it) }

    suspend fun readProfile(client: DongleSession): ByteArray {
        lease.publish(client) { state.update { it.copy(hearingAidReadStatus = SettingsReadStatus.READING) } }
        try {
            val read = checkNotNull(att(client).read(HearingDiagnosticUuids.fitting)) { "Hearing profile is unavailable" }
            val profile = checkNotNull(parseHearingAidSettingsResponse(read.second)) { "Unsupported hearing profile" }
            lease.publish(client) {
                profileCharacteristic = read.first
                profileData = read.second.copyOf()
                state.update {
                    it.copy(hearingProfile = profile, hearingAidData = read.second.copyOf(),
                        hearingAidReadStatus = SettingsReadStatus.AVAILABLE)
                }
            }
            return read.second
        } catch (error: Exception) {
            runCatching {
                lease.publish(client) {
                    profileData = null
                    profileCharacteristic = null
                    val readStatus = if (error is CancellationException) SettingsReadStatus.WAITING else SettingsReadStatus.UNAVAILABLE
                    state.update { it.copy(hearingProfile = null, hearingAidData = byteArrayOf(), hearingAidReadStatus = readStatus) }
                }
            }
            throw error
        }
    }

    suspend fun readTransparency(client: DongleSession): Pair<DongleAtt.Characteristic, ByteArray> {
        lease.publish(client) { state.update { it.copy(transparencyReadStatus = SettingsReadStatus.READING) } }
        try {
            val read = checkNotNull(att(client).read(TRANSPARENCY_SETTINGS)) { "Custom Transparency is unavailable" }
            check(validTransparency(read.second))
            lease.publish(client) {
                state.update {
                    it.copy(transparencyData = read.second.copyOf(), transparencyReadStatus = SettingsReadStatus.AVAILABLE)
                }
            }
            return read
        } catch (error: Exception) {
            runCatching {
                lease.publish(client) {
                    val readStatus = if (error is CancellationException) SettingsReadStatus.WAITING else SettingsReadStatus.UNAVAILABLE
                    state.update { it.copy(transparencyData = byteArrayOf(), transparencyReadStatus = readStatus) }
                }
            }
            throw error
        }
    }

    private suspend fun writeTransparency(client: DongleSession, characteristic: DongleAtt.Characteristic,
        baseline: ByteArray, requested: ByteArray) {
        check(baseline.contentEquals(att(client).read(characteristic))) { "Custom Transparency changed" }
        att(client).write(characteristic, requested)
        repeat(READBACK_ATTEMPTS) { attempt ->
            val readback = att(client).read(characteristic)
            check(validTransparency(readback))
            if (readback.contentEquals(requested)) {
                lease.publish(client) {
                    state.update {
                        it.copy(transparencyData = readback.copyOf(), transparencyReadStatus = SettingsReadStatus.AVAILABLE)
                    }
                }
                return
            }
            check(readback.contentEquals(baseline) && attempt < READBACK_ATTEMPTS - 1) { "Custom Transparency was not confirmed" }
            delay(READBACK_RETRY_DELAY_MILLISECONDS)
        }
    }

    /** Reads the cached hearing reports and settles an accommodations write their report answered. */
    suspend fun refresh(client: DongleSession) {
        lease.publish(client) {
            if (state.value.controlSession == 0L) state.update { it.copy(controlSession = nextControlSession()) }
        }
        var modes = lease.publish(client) { state.value.hearingModes }
        for (slot in hearingSlots) {
            val cached = client.cached(slot)
            lease.publish(client) {
                if (cached == null) {
                    if (cachedRevisions.remove(slot) != null) modes = HearingModes.empty()
                } else if (cachedRevisions[slot] != cached.revision) {
                    cachedRevisions[slot] = cached.revision
                    val next = modes.withReport(cached.packet)
                    if (next === modes) {
                        modes = HearingModes.empty()
                        cachedRevisions.keys.removeAll(hearingSlots)
                    } else {
                        modes = next
                    }
                }
            }
        }
        lease.publish(client) {
            val base = accommodationsWriteBase
            val request = state.value.accommodationsRequest
            val report = modes.accommodationsReport(state.value.controlSession)
            if (base != null && !request.pending && report != null && report.receptionRevision > request.baselineRevision) {
                val outcome = base.plan.result(report, AccommodationsSendOutcome.COMPLETE)
                accommodationsWriteBase = null
                val settled = AccommodationsRequestState(false, outcome, request.baselineRevision,
                    editRevision = request.editRevision + 1)
                state.update { it.copy(accommodationsRequest = settled) }
            }
            state.update { it.copy(hearingModes = modes) }
        }
    }

    suspend fun readAccommodations(client: DongleSession) {
        client.acquireSettingsLease()
        refresh(client)
        readTransparency(client)
    }

    suspend fun writeAccommodations(client: DongleSession, expected: AccommodationsReport,
        phone: Boolean?, media: Boolean?, preset: AccommodationsPreset?, editRevision: Long, audiogram: Boolean) {
        val snapshot = state.value
        check(!(preset != null && audiogram) && editRevision == snapshot.accommodationsRequest.editRevision)
        client.acquireSettingsLease()
        val target = client.refresh()
        check(!target.microphoneActive)
        refresh(client)
        val original = checkNotNull(state.value.accommodationsReport)
        val receivedPlan = expected.plan(phone ?: expected.phoneEnabled, media ?: expected.mediaEnabled, preset)
        check(receivedPlan.canStart(original) && state.value.accommodationsRequest.editable(original))
        val previous = accommodationsWriteBase?.takeIf {
            it.client === client && it.session == snapshot.controlSession && it.revision == editRevision &&
                snapshot.accommodationsRequest.requested != null && it.plan.canStart(original)
        }
        var plan = previous?.plan?.followup(phone, media, preset) ?: receivedPlan
        val transparency = readTransparency(client)
        val clinical = readProfile(client)
        if (previous != null && !previous.clinical.contentEquals(clinical)) {
            accommodationsWriteBase = null
            val unconfirmed = AccommodationsRequestState(false, AccommodationsResult.UNCONFIRMED, original.receptionRevision,
                editRevision = editRevision + 1)
            lease.publish(client) { state.update { it.copy(accommodationsRequest = unconfirmed) } }
            return
        }
        val source = AccommodationsAudiogram.parse(clinical, snapshot.controlSession)
        if (audiogram) plan = plan.withAudiogram(checkNotNull(source))
        val requestedTransparency = when {
            audiogram -> checkNotNull(source?.transparency(transparency.second))
            preset != null -> checkNotNull(accommodationsTransparencyPreset(transparency.second, preset))
            else -> transparency.second
        }
        val previouslySelectedAudiogram = previous?.let { snapshot.accommodationsRequest.requested?.audiogram }
        val selectedAudiogram = preset == null && (audiogram || (previouslySelectedAudiogram
            ?: (ReceivedAccommodations.parse(plan.packet(), snapshot.controlSession, receptionRevision = 1, source)?.audiogram == true)))
        refresh(client)
        check(state.value.accommodationsReport?.let(plan::canStart) == true)
        check(!client.refresh().microphoneActive)
        val transparencyChanges = !requestedTransparency.contentEquals(transparency.second)
        if (!plan.changesRequired && !transparencyChanges) {
            lease.publish(client) {
                state.update {
                    it.copy(accommodationsRequest = it.accommodationsRequest.copy(
                        pending = false, result = AccommodationsResult.NO_CHANGE, baselineRevision = original.receptionRevision))
                }
            }
            return
        }
        lease.publish(client) {
            state.update {
                it.copy(accommodationsRequest = it.accommodationsRequest.copy(
                    pending = true, result = null, baselineRevision = original.receptionRevision))
            }
        }
        var attempted = false
        var result = AccommodationsResult.NOT_SENT
        try {
            if (plan.changesRequired) {
                attempted = true
                client.transact(Rpc.Operation.SEND_AACP_PACKET, plan.packet())
            }
            if (transparencyChanges) {
                attempted = true
                writeTransparency(client, transparency.first, transparency.second, requestedTransparency)
            }
            check(client.refresh().sameConnection(target))
            val actualTransparency = readTransparency(client).second
            val actualClinical = readProfile(client)
            check(requestedTransparency.contentEquals(actualTransparency) && clinical.contentEquals(actualClinical))
            refresh(client)
            val current = state.value.accommodationsReport
            val sendOutcome = if (plan.changesRequired) AccommodationsSendOutcome.COMPLETE else AccommodationsSendOutcome.NOT_ATTEMPTED
            val reported = plan.result(current, sendOutcome)
            val fresh = current != null && current.receptionRevision > original.receptionRevision
            result = when {
                reported == AccommodationsResult.CONFIRMED -> AccommodationsResult.CONFIRMED
                fresh && reported != AccommodationsResult.NO_CHANGE -> AccommodationsResult.UNCONFIRMED
                previous != null || plan.changesRequired -> AccommodationsResult.SENT
                else -> AccommodationsResult.CONFIRMED
            }
            check(result != AccommodationsResult.UNCONFIRMED)
            if (result == AccommodationsResult.SENT) {
                val revision = editRevision + 1
                accommodationsWriteBase = AccommodationsWriteBase(client, snapshot.controlSession, revision, plan, clinical.copyOf())
                val requestedSelection = plan.selection.copy(preset = plan.selection.preset.takeUnless { selectedAudiogram },
                    audiogram = selectedAudiogram)
                lease.publish(client) {
                    state.update {
                        it.copy(accommodationsRequest = it.accommodationsRequest.copy(
                            requested = requestedSelection, editRevision = revision))
                    }
                }
            } else {
                accommodationsWriteBase = null
                lease.publish(client) {
                    state.update { it.copy(accommodationsRequest = it.accommodationsRequest.copy(requested = null)) }
                }
            }
        } finally {
            val settled = listOf(AccommodationsResult.CONFIRMED, AccommodationsResult.SENT, AccommodationsResult.NO_CHANGE)
            if (attempted && result !in settled) result = AccommodationsResult.UNCONFIRMED
            if (result == AccommodationsResult.UNCONFIRMED) accommodationsWriteBase = null
            runCatching {
                lease.publish(client) {
                    state.update {
                        val unconfirmed = result == AccommodationsResult.UNCONFIRMED
                        it.copy(accommodationsRequest = it.accommodationsRequest.copy(
                            pending = false, result = result, baselineRevision = original.receptionRevision,
                            requested = it.accommodationsRequest.requested.takeUnless { unconfirmed },
                            editRevision = it.accommodationsRequest.editRevision + if (unconfirmed) 1 else 0))
                    }
                }
            }
        }
    }

    suspend fun updateTransparency(client: DongleSession, expected: ByteArray, requested: ByteArray,
        canSend: () -> Boolean = { true }) {
        client.acquireSettingsLease()
        val target = client.refresh()
        check(!target.microphoneActive && expected.size == RECORD_SIZE && requested.size == RECORD_SIZE)
        val settings = checkNotNull(parseTransparencySettingsResponse(requested))
        refresh(client)
        check(!settings.enabled || state.value.hearingModes.customTransparencyAllowed)
        val read = readTransparency(client)
        check(expected.contentEquals(read.second))
        check(canSend())
        var attempted = false
        var confirmed = false
        try {
            if (!requested.contentEquals(expected)) {
                attempted = true
                writeTransparency(client, read.first, expected, requested)
            }
            val current = client.refresh()
            check(current.sameConnection(target) && !current.microphoneActive && canSend())
            confirmed = true
        } finally {
            val transparencyResult = when {
                confirmed -> AccommodationsResult.CONFIRMED
                attempted -> AccommodationsResult.UNCONFIRMED
                else -> AccommodationsResult.NOT_SENT
            }
            runCatching {
                lease.publish(client) {
                    state.update {
                        it.copy(
                            transparencyData = if (confirmed) it.transparencyData else byteArrayOf(),
                            transparencyReadStatus = if (confirmed) it.transparencyReadStatus else SettingsReadStatus.UNAVAILABLE,
                            transparencyResult = transparencyResult)
                    }
                }
            }
        }
    }

    suspend fun changeHearingMode(client: DongleSession, mode: HearingMode, enabled: Boolean) {
        check(supported()) { "Hearing assistance is unavailable on this model" }
        client.acquireSettingsLease()
        val generation = client.refresh().generation
        refresh(client)
        val original = state.value.hearingModes
        val plan = checkNotNull(original.plan(mode, enabled)) { "Read hearing settings first" }
        val fitting = if (enabled) readProfile(client).also {
            check(hearingAidSettingsWritable(it)) { "AirPods have no writable hearing fitting" }
            val transparency = att(client).read(TRANSPARENCY_SETTINGS)?.second
            check(transparency != null && customTransparencyEnabled(transparency) == false)
        } else null
        check(!client.refresh().microphoneActive)
        lease.publish(client) { state.update { it.copy(hearingModes = original.withRequestState(mode, original.uncertain)) } }
        var attempted = false
        var confirmed = false
        try {
            for (packet in plan.packets) {
                attempted = true
                client.transact(Rpc.Operation.SEND_AACP_PACKET, packet)
            }
            val deadline = SystemClock.elapsedRealtime() + HEARING_MODE_CONFIRMATION_MILLISECONDS
            var matched = false
            while (SystemClock.elapsedRealtime() < deadline) {
                check(client.refresh().generation == generation)
                refresh(client)
                if (state.value.hearingModes.confirms(plan)) {
                    matched = true
                    break
                }
                delay(HEARING_MODE_POLL_MILLISECONDS)
            }
            check(matched)
            if (fitting != null) check(fitting.contentEquals(readProfile(client)))
            confirmed = true
        } finally {
            val uncertainMode = if (attempted && !confirmed) mode else null
            runCatching {
                lease.publish(client) {
                    state.update { it.copy(hearingModes = it.hearingModes.withRequestState(null, uncertainMode)) }
                }
            }
        }
    }

    suspend fun updateHearing(client: DongleSession, baseline: ByteArray, requested: ByteArray,
        canSend: () -> Boolean = { true }) {
        check(supported() && hearingAidSettingsWritable(baseline) && hearingAidSettingsWritable(requested))
        client.acquireSettingsLease()
        check(!client.refresh().microphoneActive)
        refresh(client)
        check(state.value.hearingModes.enrolled == true && state.value.hearingModes.clinicalReportsKnown)
        val characteristic = checkNotNull(profileCharacteristic)
        check(baseline.contentEquals(att(client).read(characteristic))) { "Hearing profile changed" }
        check(canSend())
        att(client).write(characteristic, requested)
        repeat(READBACK_ATTEMPTS) { attempt ->
            check(canSend())
            val readback = att(client).read(characteristic)
            check(canSend() && hearingAidSettingsWritable(readback))
            val payload = hearingPayload(readback)
            if (payload.contentEquals(hearingPayload(requested))) {
                lease.publish(client) {
                    check(canSend())
                    profileData = readback.copyOf()
                    state.update {
                        it.copy(hearingProfile = checkNotNull(parseHearingAidSettingsResponse(readback)),
                            hearingAidData = readback.copyOf(), hearingAidReadStatus = SettingsReadStatus.AVAILABLE)
                    }
                }
                return
            }
            check(payload.contentEquals(hearingPayload(baseline)) && attempt < READBACK_ATTEMPTS - 1) {
                "Hearing profile was not confirmed"
            }
            delay(READBACK_RETRY_DELAY_MILLISECONDS)
        }
    }

    suspend fun readDiagnostics(client: DongleSession) {
        val product = diagnosticsProductByModelNumber[state.value.metadata?.modelNumber]
            ?: error("Saved hardware checks are unavailable on this model")
        val data = checkNotNull(att(client).read(HearingDiagnosticUuids.savedResults)).second
        val parsed = checkNotNull(parseSavedHearingDiagnostics(data, product))
        lease.publish(client) { state.update { it.copy(diagnostics = parsed) } }
    }

    companion object {
        /** Hearing profile and Custom Transparency records are both this long. */
        const val RECORD_SIZE = 104
        /** Hearing profile bytes 2 and 3 may differ between a write and its readback; the payload follows them. */
        private const val HEARING_PROFILE_FORMAT_SIZE = 2
        private const val HEARING_PROFILE_PAYLOAD_OFFSET = 4
        private const val READBACK_ATTEMPTS = 5
        private const val READBACK_RETRY_DELAY_MILLISECONDS = 75L
        private const val HEARING_MODE_CONFIRMATION_MILLISECONDS = 10_000L
        private const val HEARING_MODE_POLL_MILLISECONDS = 100L

        /** The Custom Transparency ATT characteristic (core's ATTHandle.TRANSPARENCY). */
        private val TRANSPARENCY_SETTINGS: UUID = UUID.fromString("d5621cc1-f7ab-45db-9403-9eaf744d5390")
        private val hearingSlots = CachedSlot.HEARING_AID..CachedSlot.PME

        // Apple Bluetooth product ids of the models with hearing features, keyed by model number.
        private const val AIRPODS_PRO_2_LIGHTNING_PRODUCT = 0x2014
        private const val AIRPODS_PRO_2_USBC_PRODUCT = 0x2024
        private const val AIRPODS_PRO_3_PRODUCT = 0x2027
        private val diagnosticsProductByModelNumber = mapOf(
            "A2931" to AIRPODS_PRO_2_LIGHTNING_PRODUCT, "A2699" to AIRPODS_PRO_2_LIGHTNING_PRODUCT,
            "A2698" to AIRPODS_PRO_2_LIGHTNING_PRODUCT,
            "A3047" to AIRPODS_PRO_2_USBC_PRODUCT, "A3048" to AIRPODS_PRO_2_USBC_PRODUCT, "A3049" to AIRPODS_PRO_2_USBC_PRODUCT,
            "A3063" to AIRPODS_PRO_3_PRODUCT, "A3064" to AIRPODS_PRO_3_PRODUCT, "A3065" to AIRPODS_PRO_3_PRODUCT,
        )
        private val hearingModelNumbers = diagnosticsProductByModelNumber.keys

        fun validTransparency(data: ByteArray) = data.size == RECORD_SIZE && parseTransparencySettingsResponse(data) != null

        fun hearingPayload(data: ByteArray): ByteArray = data.copyOfRange(HEARING_PROFILE_PAYLOAD_OFFSET, RECORD_SIZE)

        /** Whether a hearing profile readback matches [target], ignoring the bytes a write may change. */
        fun hearingProfileMatches(actual: ByteArray, target: ByteArray): Boolean =
            actual.copyOfRange(0, HEARING_PROFILE_FORMAT_SIZE).contentEquals(target.copyOfRange(0, HEARING_PROFILE_FORMAT_SIZE)) &&
                hearingPayload(actual).contentEquals(hearingPayload(target))
    }
}
