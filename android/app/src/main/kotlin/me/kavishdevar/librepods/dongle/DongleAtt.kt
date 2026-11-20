package me.kavishdevar.librepods.dongle

import java.nio.ByteBuffer
import java.util.UUID

/** UUID discovery mirrors the native NTPods parser; handles are never guessed. */
internal class DongleAtt(private val exchange: suspend (ByteArray) -> ByteArray) {
    data class Characteristic(val handle: Int, val writeOpcode: Int?)
    private fun le(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())
    private fun uuid(value: UUID): ByteArray = ByteBuffer.allocate(16)
        .putLong(value.mostSignificantBits).putLong(value.leastSignificantBits).array().reversedArray()

    suspend fun discover(id: UUID): Characteristic? {
        val service = byteArrayOf(FIND_BY_TYPE_VALUE_REQUEST.toByte()) + le(FIRST_HANDLE) + le(LAST_HANDLE) +
            le(PRIMARY_SERVICE_TYPE) + uuid(AIRPODS_SETTINGS_SERVICE)
        val range = exchange(service)
        if (absent(range, service)) return null
        require(range.size == FIND_BY_TYPE_VALUE_RESPONSE_SIZE && range[0] == FIND_BY_TYPE_VALUE_RESPONSE.toByte()) {
            "AirPods service discovery failed"
        }
        var start = DongleProtocol.u16(range, 1)
        val end = DongleProtocol.u16(range, 3)
        require(start > 0 && start < end)
        var previousValue = 0
        var found: Characteristic? = null
        repeat(MAXIMUM_DISCOVERY_PAGES) {
            val request = byteArrayOf(READ_BY_TYPE_REQUEST.toByte()) + le(start) + le(end) + le(CHARACTERISTIC_TYPE)
            val response = exchange(request)
            if (absent(response, request)) return found
            require(response.size >= 2 && response[0] == READ_BY_TYPE_RESPONSE.toByte()) { "AirPods characteristic discovery failed" }
            val each = response[1].toInt() and 255
            require(each in setOf(SHORT_UUID_DECLARATION_LENGTH, LONG_UUID_DECLARATION_LENGTH) &&
                response.size > 2 && (response.size - 2) % each == 0)
            var previous = start - 1
            for (at in 2 until response.size step each) {
                val declaration = DongleProtocol.u16(response, at)
                val handle = DongleProtocol.u16(response, at + DECLARATION_VALUE_HANDLE_OFFSET)
                require(declaration > previous && declaration > previousValue && declaration <= end && handle > declaration && handle <= end)
                val declaredUuid = response.copyOfRange(at + DECLARATION_UUID_OFFSET, at + each)
                if (each == LONG_UUID_DECLARATION_LENGTH && declaredUuid.contentEquals(uuid(id))) {
                    val properties = response[at + DECLARATION_PROPERTIES_OFFSET].toInt()
                    require(found == null && properties and PROPERTY_READ != 0) { "AirPods setting is not uniquely readable" }
                    val writeOpcode = when {
                        properties and PROPERTY_WRITE != 0 -> WRITE_REQUEST
                        properties and PROPERTY_WRITE_WITHOUT_RESPONSE != 0 -> WRITE_COMMAND
                        else -> null
                    }
                    found = Characteristic(handle, writeOpcode)
                }
                previous = declaration
                previousValue = handle
            }
            if (previous == end) return found
            start = previous + 1
        }
        error("AirPods characteristic discovery exceeded its limit")
    }

    suspend fun read(characteristic: Characteristic): ByteArray {
        val response = exchange(byteArrayOf(READ_REQUEST.toByte()) + le(characteristic.handle))
        require(response.isNotEmpty() && response[0] == READ_RESPONSE.toByte()) { "AirPods setting read failed" }
        return response.copyOfRange(1, response.size)
    }
    suspend fun read(id: UUID): Pair<Characteristic, ByteArray>? = discover(id)?.let { it to read(it) }
    suspend fun write(characteristic: Characteristic, value: ByteArray) {
        val opcode = checkNotNull(characteristic.writeOpcode) { "This AirPods setting is read-only" }
        val response = exchange(byteArrayOf(opcode.toByte()) + le(characteristic.handle) + value)
        val confirmed = if (opcode == WRITE_REQUEST) response.contentEquals(byteArrayOf(WRITE_RESPONSE.toByte())) else response.isEmpty()
        require(confirmed) { "AirPods setting write failed" }
    }

    /** An error response for this request's opcode, inside its handle range, with "attribute not found". */
    private fun absent(response: ByteArray, request: ByteArray): Boolean = response.size == ERROR_RESPONSE_SIZE &&
        response[0] == ERROR_RESPONSE.toByte() && response[1] == request[0] &&
        response[ERROR_CODE_OFFSET] == ATTRIBUTE_NOT_FOUND.toByte() &&
        DongleProtocol.u16(response, ERROR_HANDLE_OFFSET) in DongleProtocol.u16(request, 1)..DongleProtocol.u16(request, 3)

    private companion object {
        /** The AirPods settings GATT service. */
        val AIRPODS_SETTINGS_SERVICE: UUID = UUID.fromString("7798082b-b7b7-45a6-9933-563492efe04e")

        // Bluetooth Core ATT opcodes.
        const val ERROR_RESPONSE = 0x01
        const val FIND_BY_TYPE_VALUE_REQUEST = 0x06
        const val FIND_BY_TYPE_VALUE_RESPONSE = 0x07
        const val READ_BY_TYPE_REQUEST = 0x08
        const val READ_BY_TYPE_RESPONSE = 0x09
        const val READ_REQUEST = 0x0a
        const val READ_RESPONSE = 0x0b
        const val WRITE_REQUEST = 0x12
        const val WRITE_RESPONSE = 0x13
        const val WRITE_COMMAND = 0x52

        const val ATTRIBUTE_NOT_FOUND = 0x0a
        const val ERROR_RESPONSE_SIZE = 5
        const val ERROR_HANDLE_OFFSET = 2
        const val ERROR_CODE_OFFSET = 4

        const val FIRST_HANDLE = 0x0001
        const val LAST_HANDLE = 0xffff
        const val PRIMARY_SERVICE_TYPE = 0x2800
        const val CHARACTERISTIC_TYPE = 0x2803
        /** Opcode, then one found handle range of two u16 handles. */
        const val FIND_BY_TYPE_VALUE_RESPONSE_SIZE = 5

        // A characteristic declaration: handle u16, properties u8, value handle u16, then a 16- or 128-bit UUID.
        const val SHORT_UUID_DECLARATION_LENGTH = 7
        const val LONG_UUID_DECLARATION_LENGTH = 21
        const val DECLARATION_PROPERTIES_OFFSET = 2
        const val DECLARATION_VALUE_HANDLE_OFFSET = 3
        const val DECLARATION_UUID_OFFSET = 5
        const val PROPERTY_READ = 0x02
        const val PROPERTY_WRITE_WITHOUT_RESPONSE = 0x04
        const val PROPERTY_WRITE = 0x08

        const val MAXIMUM_DISCOVERY_PAGES = 16
    }
}

/** A new session/report gap or manual media change always retires its previous pause. */
internal class DongleEarClaim {
    private var session: Long? = null
    private var inEars: Boolean? = null
    var paused = false
        private set
    enum class Action { NONE, PAUSE, RESUME, CANCEL }
    fun observe(generation: Long, fresh: Boolean?, enabled: Boolean): Action {
        if (session != generation || fresh == null || !enabled) {
            val old = paused
            session = generation; inEars = fresh; paused = false
            return if (old) Action.CANCEL else Action.NONE
        }
        val previous = inEars
        inEars = fresh
        return when {
            previous == true && !fresh -> Action.PAUSE
            previous == false && fresh && paused -> { paused = false; Action.RESUME }
            else -> Action.NONE
        }
    }
    fun acquired(value: Boolean) { paused = value }
    fun cancel() { session = null; inEars = null; paused = false }
}
