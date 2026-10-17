package me.kavishdevar.librepods.core.bluetooth.aacp

/** Lifecycle mutation and its state publication share this monitor; socket I/O stays outside it. */
internal inline fun <T : Any> publishForTransport(
    monitor: Any, expected: T?, current: () -> T?, publish: () -> Unit,
): Boolean = expected != null && retireForTransport(monitor, expected, current, publish)

/** Null is intentional here: an explicit disconnect can invalidate a pending socket creation. */
internal inline fun <T : Any> retireForTransport(
    monitor: Any, expected: T?, current: () -> T?, retire: () -> Unit,
): Boolean = synchronized(monitor) {
    if (current() !== expected) false
    else { retire(); true }
}
