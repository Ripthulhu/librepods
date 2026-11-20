package me.kavishdevar.librepods.dongle

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.hostClockPacket
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Rpc

/**
 * The reads the polling loop makes on its own for each new editor owner: the settings snapshot,
 * and once per owner the host clock and hearing health reads, retried a few times after failures.
 *
 * Only the polling loop uses it, under the controller's io lock; its fields keep the access pattern
 * they had inside the controller, partly inside lifecycle publication.
 */
internal class DongleAutomaticReads(
    private val state: MutableStateFlow<DongleState>,
    private val lease: DongleEditingLease,
    private val hearing: DongleHearingRecords,
    private val callActive: () -> Boolean,
) {
    private var automaticHealthOwner: DongleEditorOwner? = null
    private var hostClockOwner: DongleEditorOwner? = null
    private var healthAttemptOwner: DongleEditorOwner? = null
    private var healthAttempts = 0
    private var healthRetryAfter = 0L
    private var automaticSettingsOwner: DongleEditorOwner? = null

    fun reset() {
        automaticHealthOwner = null
        hostClockOwner = null
        automaticSettingsOwner = null
        healthAttemptOwner = null
        healthAttempts = 0
        healthRetryAfter = 0
    }

    /** Whether the current owner still lacks its settings read or a due health read. Call inside publication. */
    fun wanted(): Boolean = automaticSettingsOwner != lease.currentOwner() ||
        hearing.supported() && automaticHealthOwner != lease.currentOwner() && healthRetryDue()

    /** Records that the settings were read for the current owner, then runs its health reads if due. */
    suspend fun afterSettingsRead(client: DongleSession, status: DongleProtocol.Status) {
        val owner = lease.currentOwner()
        automaticSettingsOwner = owner
        val healthWanted = owner != null && owner != automaticHealthOwner && hearing.supported() &&
            !status.microphoneActive && !callActive() && (owner != healthAttemptOwner || healthRetryDue())
        if (healthWanted) readHealth(client, status, checkNotNull(owner))
    }

    private fun healthRetryDue() = healthAttempts < MAXIMUM_HEALTH_ATTEMPTS && SystemClock.elapsedRealtime() >= healthRetryAfter

    private suspend fun readHealth(client: DongleSession, status: DongleProtocol.Status, owner: DongleEditorOwner) {
        if (healthAttemptOwner != owner) {
            healthAttemptOwner = owner
            healthAttempts = 0
            healthRetryAfter = 0
        }
        lease.withProtectedWork {
            healthAttempts++
            automaticHealthOwner = owner
            var failed = false
            lease.publish(client) { state.update { it.copy(busy = true) } }
            val sendHostClock: suspend () -> Unit = {
                if (hostClockOwner != owner) {
                    check(lease.writable(owner) && hearing.supported())
                    val packet = checkNotNull(hostClockPacket())
                    val response = client.transact(Rpc.Operation.SEND_AACP_PACKET, packet.rawPacket) {
                        check(lease.writable(owner) && hearing.supported() && client.status?.microphoneActive == false)
                    }
                    check(response.isEmpty())
                    lease.publish(client) {
                        check(lease.owns(owner))
                        hostClockOwner = owner
                    }
                }
            }
            val reads = listOf<suspend () -> Unit>(sendHostClock,
                { hearing.readProfile(client) },
                { hearing.readTransparency(client) },
                { hearing.readDiagnostics(client) })
            try {
                client.withTarget(status) {
                    lease.withVolumeCheckpoints(client) {
                        for (read in reads) {
                            try {
                                read()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (busy: DongleBusyException) {
                                throw busy
                            } catch (_: Exception) {
                                failed = true
                            }
                        }
                    }
                }
            } catch (busy: DongleBusyException) {
                lease.publish(client) {
                    if (lease.owns(owner) && healthAttemptOwner == owner) {
                        healthAttempts = (healthAttempts - 1).coerceAtLeast(0)
                        automaticHealthOwner = null
                        healthRetryAfter = SystemClock.elapsedRealtime() + HEALTH_RETRY_DELAY_MILLISECONDS
                    }
                }
                throw busy
            } finally {
                lease.publish(client) {
                    if (failed) {
                        automaticHealthOwner = null
                        healthRetryAfter = SystemClock.elapsedRealtime() + HEALTH_RETRY_DELAY_MILLISECONDS
                    }
                    state.update { it.copy(busy = false) }
                }
            }
        }
    }

    private companion object {
        const val MAXIMUM_HEALTH_ATTEMPTS = 3
        const val HEALTH_RETRY_DELAY_MILLISECONDS = 5_000L
    }
}
