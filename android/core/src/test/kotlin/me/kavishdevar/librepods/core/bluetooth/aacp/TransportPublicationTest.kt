package me.kavishdevar.librepods.core.bluetooth.aacp

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class TransportPublicationTest {
    @Test fun aResponseParsedBeforeReconnectCannotPublishIntoTheReplacementOrResolveItsRequest() {
        val monitor = Any()
        val first = Any(); val second = Any()
        val current = AtomicReference<Any?>(first)
        val report = AtomicInteger(0)
        val pending = AtomicInteger(7)
        val parsed = CountDownLatch(1); val resume = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val reader = thread {
            try {
                val captured = current.get()
                parsed.countDown()
                assertTrue(resume.await(2, TimeUnit.SECONDS))
                publishForTransport(monitor, captured, current::get) { report.set(1); pending.set(0) }
            } catch (error: Throwable) { failure.set(error) }
        }
        assertTrue(parsed.await(2, TimeUnit.SECONDS))
        synchronized(monitor) { current.set(second) }
        resume.countDown(); reader.join(2_000)
        assertFalse(reader.isAlive)
        assertNull(failure.get())
        assertEquals(0, report.get()); assertEquals(7, pending.get())
        assertTrue(publishForTransport(monitor, second, current::get) { report.set(2) })
        assertEquals(2, report.get())
    }

    @Test fun retiredReaderCannotRetireOrNotifyForAReplacementAndCleanupRunsAfterIdentityRetirement() {
        val monitor = Any()
        val first = Any(); val second = Any()
        var current: Any? = first
        val notifications = mutableListOf<Any>()
        assertTrue(retireForTransport(monitor, first, { current }) {
            current = null
            notifications += first
        })
        synchronized(monitor) { current = second }
        assertFalse(retireForTransport(monitor, first, { current }) {
            current = null
            notifications += first
        })
        assertSame(second, current)
        assertEquals(listOf(first), notifications)
        // Transport I/O/manager close occur after retirement; a late cleanup cannot publish.
        assertFalse(publishForTransport(monitor, first, { current }) { error("stale cleanup") })
        assertFalse(publishForTransport<Any>(monitor, null, { current }) { error("absent transport") })
    }

    @Test fun disconnectBeforeSocketCreationCanInvalidateThePendingConnectionWithoutPublishingAbsentResponses() {
        var current: Any? = null
        val monitor = Any()
        var epoch = 0
        assertTrue(retireForTransport(monitor, null, { current }) { epoch++ })
        assertEquals(1, epoch)
        assertFalse(publishForTransport(monitor, null, { current }) { error("absent response") })
    }
}
