/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.core.data.apple

import kotlin.math.abs

private const val COUNTER_MASK_16_BIT = 0xffffL
private const val COUNTER_MASK_32_BIT = 0xffffffffL
/** Captured Pro 3 counter ticks are approximately 5 ms. */
private const val COUNTER_TICK_NANOS = 5_000_000L
/** One tick of timing error is allowed when matching the counter step against the sensor clock. */
private const val COUNTER_TICK_TOLERANCE_NANOS = COUNTER_TICK_NANOS
private const val MAX_WRAP_ELAPSED_NANOS = 250_000_000L

/** Preserve raw counters; recognize only a short 16-bit crossing corroborated by the sensor clock. */
fun motionCounterStep(before: Long, after: Long, beforeTimeNs: Long?, afterTimeNs: Long?): Long =
    sixteenBitWrapStep(before, after, beforeTimeNs, afterTimeNs) ?: ((after - before) and COUNTER_MASK_32_BIT)

/** The wrapped step when both counters fit 16 bits, went backward, and the sensor clock agrees; else null. */
private fun sixteenBitWrapStep(before: Long, after: Long, beforeTimeNs: Long?, afterTimeNs: Long?): Long? {
    val bothFitSixteenBits = before in 0..COUNTER_MASK_16_BIT && after in 0..COUNTER_MASK_16_BIT
    if (!bothFitSixteenBits || after >= before) return null
    if (beforeTimeNs == null || afterTimeNs == null) return null
    if (beforeTimeNs <= 0 || afterTimeNs <= beforeTimeNs) return null
    val elapsed = afterTimeNs - beforeTimeNs
    val wrapped = (after - before) and COUNTER_MASK_16_BIT
    val clockAgrees = abs(elapsed - wrapped * COUNTER_TICK_NANOS) <= COUNTER_TICK_TOLERANCE_NANOS
    return wrapped.takeIf { elapsed < MAX_WRAP_ELAPSED_NANOS && wrapped > 0 && clockAgrees }
}
