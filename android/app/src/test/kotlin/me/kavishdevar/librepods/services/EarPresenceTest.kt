package me.kavishdevar.librepods.services

import me.kavishdevar.librepods.core.devices.ComponentStatus
import me.kavishdevar.librepods.core.devices.ComponentStatus.IN_CASE
import me.kavishdevar.librepods.core.devices.ComponentStatus.IN_EAR
import me.kavishdevar.librepods.core.devices.ComponentStatus.OUT_OF_EAR
import me.kavishdevar.librepods.core.devices.DeviceComponent
import me.kavishdevar.librepods.core.devices.DeviceComponent.HEADSET
import me.kavishdevar.librepods.core.devices.DeviceComponent.LEFT
import me.kavishdevar.librepods.core.devices.DeviceComponent.RIGHT
import me.kavishdevar.librepods.core.devices.DeviceComponentState
import org.junit.Assert.assertEquals
import org.junit.Test

class EarPresenceTest {
    private fun of(vararg parts: Pair<DeviceComponent, ComponentStatus>) =
        earPresenceOf(parts.map { (component, status) -> DeviceComponentState(component, status) }.toSet())

    @Test fun noReportOrNoBudInEarIsNone() {
        assertEquals(EarPresence.NONE, of())
        assertEquals(EarPresence.NONE, of(LEFT to OUT_OF_EAR, RIGHT to OUT_OF_EAR))
        assertEquals(EarPresence.NONE, of(LEFT to IN_CASE, RIGHT to IN_CASE))
    }

    @Test fun bothBudsInEarIsFull() {
        assertEquals(EarPresence.FULL, of(LEFT to IN_EAR, RIGHT to IN_EAR))
    }

    @Test fun oneBudInEarIsFullOnlyWhenTheOtherIsReportedInTheCase() {
        assertEquals(EarPresence.FULL, of(LEFT to IN_EAR, RIGHT to IN_CASE))
        assertEquals(EarPresence.PARTIAL, of(LEFT to IN_EAR, RIGHT to OUT_OF_EAR))
    }

    @Test fun aSingleBudReportWithoutItsPartnerIsOnlyPartial() {
        assertEquals(EarPresence.PARTIAL, of(LEFT to IN_EAR))
        assertEquals(EarPresence.PARTIAL, of(RIGHT to IN_EAR))
    }

    @Test fun aHeadsetReportIsCompleteOnItsOwn() {
        assertEquals(EarPresence.FULL, of(HEADSET to IN_EAR))
    }
}
