package me.kavishdevar.librepods.shizuku

import org.junit.Assert.*
import org.junit.Test

class AudioRouteCleanupTest {
    @Test fun releasesPolicyExactlyOnceEvenWhenTwoOwnersCloseIt() {
        var removals = 0
        val route = AudioRouting.Route(unregister = { removals++ })
        route.close()
        route.close()
        assertEquals(1, removals)
    }

    @Test fun aFailedRemovalIsReportedAndNeverRepeatedAgainstAReplacement() {
        var removals = 0
        val route = AudioRouting.Route(unregister = { removals++; error("Native policy removal failed") })
        assertThrows(AudioRoutingCleanupException::class.java) { route.close() }
        route.close()
        assertEquals(1, removals)
    }
}
