/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import java.util.concurrent.TimeUnit

internal data class IslandBatteryCell(val component: BatteryComponent, val level: Int?, val live: Boolean,
    val charging: Boolean, val ageSeconds: Long?)

internal fun islandAgeBucket(seconds: Long): Pair<Long, TimeUnit> = when {
    seconds < 60 -> 0L to TimeUnit.SECONDS
    seconds < 3600 -> seconds / 60 to TimeUnit.MINUTES
    seconds < 86400 -> seconds / 3600 to TimeUnit.HOURS
    else -> seconds / 86400 to TimeUnit.DAYS
}

internal fun islandCommonAge(cells: List<IslandBatteryCell>): Pair<Long, TimeUnit>? =
    cells.takeIf { it.size > 1 && it.all { cell -> !cell.live && cell.level != null && cell.ageSeconds != null } }
        ?.map { islandAgeBucket(requireNotNull(it.ageSeconds)) }?.distinct()?.singleOrNull()

internal fun islandBatteryCells(model: AirPodsModel, batteries: Set<Battery>, now: Long): List<IslandBatteryCell> {
    val components = if (model in setOf(AirPodsModel.AIRPODS_MAX_LIGHTNING, AirPodsModel.AIRPODS_MAX_USBC, AirPodsModel.AIRPODS_MAX_2))
        listOf(BatteryComponent.HEADSET) else listOf(BatteryComponent.LEFT, BatteryComponent.RIGHT, BatteryComponent.CASE)
    return components.map { component ->
        val battery = batteries.singleOrNull { it.component == component }
        val live = battery?.isLive(now) == true
        IslandBatteryCell(component, battery?.level?.takeIf { it in 0..100 && battery.observedAt != null }, live,
            live && battery?.status in setOf(BatteryStatus.CHARGING, BatteryStatus.OPTIMIZED_CHARGING),
            battery?.observedAt?.takeIf { now >= it }?.let { (now - it) / 1000 })
    }
}
