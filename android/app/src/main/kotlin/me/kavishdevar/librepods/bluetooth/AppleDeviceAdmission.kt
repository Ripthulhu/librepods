package me.kavishdevar.librepods.bluetooth

/** A saved Apple identity can be restored before Android has cached SDP service UUIDs. */
internal fun canLoadAppleDevice(bonded: Boolean, savedAppleDevice: Boolean, advertisesAacp: Boolean): Boolean =
    bonded && (savedAppleDevice || advertisesAacp)
