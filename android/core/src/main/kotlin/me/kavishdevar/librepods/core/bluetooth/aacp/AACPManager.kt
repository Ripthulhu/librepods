/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.core.bluetooth.aacp

import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.AACPPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.AACPPacketType
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.AudioSourcePacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.BatteryInfoPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.ConnectedDevicesPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.ControlCommandPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.CustomEqPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.EarDetectionResponsePacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.InformationPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.MagicKeyResponsePacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.RenamePacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.StemPressPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.types.AppleEvent
import me.kavishdevar.librepods.core.bluetooth.aacp.types.Capability
import me.kavishdevar.librepods.core.bluetooth.aacp.types.CapabilityEntry
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ConnectOpcode
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommand
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.aacp.types.CustomEq
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MagicKeyType
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MessageOpcode
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.data.audio.MicrophoneFrame
import me.kavishdevar.librepods.core.data.heartrate.HeartRateSample
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.core.devices.cachedControlStates
import me.kavishdevar.librepods.core.devices.withControlReport
import me.kavishdevar.librepods.core.devices.withControlRequest
import me.kavishdevar.librepods.core.devices.mergeBatteryReports
import me.kavishdevar.librepods.core.utils.HeadTracking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AACPManager(private val device: AppleDevice,
    private val socketFactory: () -> BluetoothSocket = {
        device.createSocket(ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a"), 4097)
    }
) {
    private val TAG = "AACPManager"

    @Volatile private var socket: BluetoothSocket? = null
    private var connectionScope: CoroutineScope? = null
    private val connectLock = java.util.concurrent.locks.ReentrantLock()
    private val connectionEpoch = AtomicLong()
    private val controlTimeouts = ConcurrentHashMap<ControlCommandIdentifier, Job>()
    private var eqTimeout: Job? = null
    @Volatile var microphonePacketCallback: ((BluetoothSocket, ByteArray) -> Unit)? = null

    fun captureAudioSocket(): BluetoothSocket? = socket?.takeIf { it.isConnected }
    fun ownsAudioSocket(expected: BluetoothSocket): Boolean = socket === expected && expected.isConnected

    /** Captured-transport writes never enter packet history or raw logs. */
    fun sendAudioPacket(expected: BluetoothSocket, packet: ByteArray): Boolean = synchronized(expected) {
        if (!ownsAudioSocket(expected)) return@synchronized false
        try {
            expected.outputStream.write(packet)
            expected.outputStream.flush()
            ownsAudioSocket(expected)
        } catch (error: Exception) {
            Log.w(TAG, "Audio transport write failed: ${error.javaClass.simpleName}")
            false
        }
    }

    @Volatile private var rtBuddyManager: RTBuddyManager? = null

    private fun createSensorSession(expected: BluetoothSocket) = RTBuddyManager(
        sendPacket = { sendPacket(it, expected) },
        sendRaw = { sendAudioPacket(expected, it) },
        isCurrent = { ownsAudioSocket(expected) },
        firmwareMajor = { device.metadata.value.firmwareMajor },
        onMotion = { report ->
            publishForTransport(this, expected, ::captureAudioSocket) { HeadTracking.addAccel(report.vertical.toFloat(), report.horizontal.toFloat()) }
        },
        onHeartRate = { report ->
            publishForTransport(this, expected, ::captureAudioSocket) {
                device.updateState { it.copy(currentHeartRate = HeartRateSample(report.bpm, Clock.System.now())) }
            }
        },
        onMotionState = { state ->
            publishForTransport(this, expected, ::captureAudioSocket) {
                if (state != BuddyState.ACTIVE) HeadTracking.reset()
                device.updateState { it.copy(headTrackingState = state) }
            }
        },
        onHeartRateState = { state ->
            publishForTransport(this, expected, ::captureAudioSocket) {
                device.updateState { it.copy(hrmState = state, currentHeartRate = if (state == BuddyState.ACTIVE) it.currentHeartRate else null) }
            }
        },
    )

    private suspend fun sendDelayedInitPackets(expected: BluetoothSocket) {
        if (ownsAudioSocket(expected)) {
            Log.i(TAG, "initializing connection")

            if (!connectService4(expected)) return
            delay(200.milliseconds)
            if (!sendSourceFeatureCapabilities(expected)) return
            delay(200.milliseconds)
            if (!sendNotificationRequest(expected)) return
            delay(200.milliseconds)
            if (!sendCountryCode(expected)) return
            delay(200.milliseconds)
            sendRequestMagicKeys((MagicKeyType.IRK.value + MagicKeyType.ENC_KEY.value).toByte(), expected)
            if (ownsAudioSocket(expected)) rtBuddyManager?.requestDescriptors()
        }
    }

    fun connect(): Boolean {
        val requestEpoch = connectionEpoch.get()
        connectLock.lock()
        return try { if (requestEpoch != connectionEpoch.get()) false else connect(requestEpoch) }
        finally { connectLock.unlock() }
    }

    class ControlSessionRefresh internal constructor(internal val original: BluetoothSocket) {
        @Volatile internal var cancelled = false
        internal var epoch: Long? = null
    }

    fun prepareControlSessionRefresh(): ControlSessionRefresh? = captureAudioSocket()?.let(::ControlSessionRefresh)

    /** Reopen only AACP; native media/call profiles and the last confirmed settings are retained. */
    fun refreshControlSession(request: ControlSessionRefresh, stillCurrent: () -> Boolean): Boolean {
        if (!connectLock.tryLock(10, java.util.concurrent.TimeUnit.SECONDS)) return false
        try {
            val sensorState = device.state.value
            val motionWanted = sensorState.headTrackingState != BuddyState.INACTIVE
            val heartWanted = sensorState.hrmState != BuddyState.INACTIVE
            synchronized(this) {
                if (request.cancelled || !stillCurrent() || !ownsAudioSocket(request.original)) return false
                request.epoch = connectionEpoch.get() + 1
            }
            if (!disconnect(request.original, preserveControlState = true)) return false
            if (request.cancelled || !stillCurrent()) {
                cancelControlSessionRefresh(request)
                return false
            }
            val connected = connect(checkNotNull(request.epoch), preserveControlState = true)
            if (!connected || request.cancelled || !stillCurrent()) {
                cancelControlSessionRefresh(request)
                return false
            }
            val sensors = synchronized(this) {
                rtBuddyManager.takeIf {
                    !request.cancelled && request.epoch == connectionEpoch.get() && stillCurrent() && captureAudioSocket() != null
                }
            }
            if (sensors == null) {
                cancelControlSessionRefresh(request)
                return false
            }
            // The captured manager only writes to its captured replacement socket.
            if (motionWanted) sensors.setHeadTrackingEnabled(true)
            if (heartWanted) sensors.setHeartRateEnabled(true)
            val current = !request.cancelled && request.epoch == connectionEpoch.get() && stillCurrent() && captureAudioSocket() != null
            if (!current) cancelControlSessionRefresh(request)
            return current
        } finally { connectLock.unlock() }
    }

    /** Also invalidates a socket creation that has not reached connect() yet. */
    fun cancelControlSessionRefresh(request: ControlSessionRefresh) {
        val owned = synchronized(this) {
            request.cancelled = true
            if (request.epoch != connectionEpoch.get()) return
            connectionEpoch.incrementAndGet()
            socket
        }
        disconnect(owned, notifyDevice = true, preserveControlState = true)
    }

    private fun connect(requestEpoch: Long, preserveControlState: Boolean = false): Boolean {
        if (requestEpoch != connectionEpoch.get()) return false
        if (socket != null && socket!!.isConnected) {
            Log.i(TAG, "Already connected")
            return true
        }
        val connectingSocket = try {
            socketFactory()
        } catch (e: Exception) {
            Log.e(TAG, "failed to create socket: ${e.javaClass.simpleName}")
            return false
        }

        val previous = synchronized(this) {
            if (requestEpoch != connectionEpoch.get()) { connectingSocket.close(); return false }
            val retired = socket to rtBuddyManager
            socket = connectingSocket
            rtBuddyManager = null
            retired
        }
        // Closing the transport first releases any sensor writer waiting on the old socket.
        runCatching { previous.first?.close() }
        previous.second?.close()
        connectingSocket.let { socket ->
            try {
                Log.i(TAG, "connecting...")
                socket.connect()
            } catch (e: Exception) {
                Log.e(TAG, "failed to connect: ${e.javaClass.simpleName}")
                disconnect(socket, notifyDevice = preserveControlState, preserveControlState = preserveControlState)
                return false
            }

            if (!socket.isConnected) {
                disconnect(socket, notifyDevice = preserveControlState, preserveControlState = preserveControlState)
                return false
            }

            if (socket.isConnected) {
                Log.i(TAG, "connected!")
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            synchronized(this) {
                if (this.socket !== socket || requestEpoch != connectionEpoch.get()) { scope.cancel(); socket.close(); return false }
                connectionScope?.cancel()
                connectionScope = scope
                rtBuddyManager = createSensorSession(socket)
            }
            scope.launch {
                sendDelayedInitPackets(socket)
                delay(3.seconds) // just android stuff ig
                sendDelayedInitPackets(socket)
            }

            scope.launch {
                Log.i(TAG, "starting to read data...")
                val buffer = ByteArray(65_535)
                try {
                while (this@AACPManager.socket === socket && socket.isConnected) {
                    try {
                        val bytesRead = socket.inputStream.read(buffer)
                        var data: ByteArray
                        if (bytesRead > 0) {
                            data = buffer.copyOfRange(0, bytesRead)
                            try {
                                processPacket(data, socket)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing received packet: ${e.javaClass.simpleName}")
                            }

                        } else if (bytesRead == -1) {
                            Log.i("AirPodsService", "socket closed (bytesRead = -1)")
                            break
                        }
                    } catch (e: Exception) {
                        Log.i(TAG, "Error reading data, we have probably disconnected.")
                        break
                    }
                }
                } finally {
                    disconnect(socket, notifyDevice = true)
                }
            }
        }
        return true
    }

    fun sendRawPacket(data: ByteArray): Boolean {
        if (!validRawAacpPacket(data)) return false
        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.fromByte(data[4]),
            payload = data.copyOfRange(6, data.size),
            type = AACPPacketType.fromByte(data[0]),
            service = data[2]
        )
        return sendPacket(packet)
    }

    fun sendPacket(packet: AACPPacket, expected: BluetoothSocket? = captureAudioSocket()): Boolean {
        try {
            val privatePacket = packet.rawPacket.getOrNull(4) in listOf(0x31.toByte(), 0x58.toByte())
            if (!privatePacket) Log.d(TAG, "Sending packet opcode=${packet.rawPacket.getOrNull(4)}")

            val socket = expected ?: run {
                Log.e(TAG, "Can't send packet: Socket is null")
                return false
            }

            if (socket.isConnected) {
                if (!sendAudioPacket(socket, packet.rawPacket)) return false

                return publishForTransport(this, socket, ::captureAudioSocket) {
                    if (!privatePacket) device.updateState { it.copy(aacpPackets = it.aacpPackets + packet) }
                }
            } else {
                Log.d(TAG, "Can't send packet: Socket not initialized or connected")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending packet: ${e.javaClass.simpleName}")
            return false
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun processPacket(packet: ByteArray, sourceSocket: BluetoothSocket? = captureAudioSocket()) {
        if (sourceSocket != null && !ownsAudioSocket(sourceSocket)) return
        // A protobuf record can span reads; feed continuation bytes before the AACP header guard.
        val sensors = synchronized(this) {
            if (sourceSocket != null && ownsAudioSocket(sourceSocket)) rtBuddyManager else null
        }
        sensors?.feed(packet)
        if (!validAacpMessage(packet)) {
            Log.w(TAG, "Ignoring malformed AACP message (${packet.size} bytes)")
            return
        }
        val opcode = packet[4]
        when (MessageOpcode.fromByte(opcode)) {
            MessageOpcode.BUD_ROLE -> {
                if (packet.size < 7) return
                val payload = packet.copyOfRange(6, packet.size)
                val packet = AACPPacket.createUnknownPacket(
                    opcode = MessageOpcode.BUD_ROLE,
                    payload = payload
                )

                device.updateState {
                    it.copy(
                        leftIsPrimary = payload[0] == 0x01.toByte(),
                        aacpPackets = it.aacpPackets + packet
                    )
                }
            }
            MessageOpcode.CAPABILITIES -> {
                val payload = packet.copyOfRange(6, packet.size)
                val packet = AACPPacket.createUnknownPacket(
                    opcode = MessageOpcode.CAPABILITIES,
                    payload = payload
                )

                device.updateState {
                    it.copy(
                        capabilities = parseCapabilitiesResponse(packet.rawPacket),
                        aacpPackets = it.aacpPackets + packet
                    )
                }
            }
            MessageOpcode.BATTERY_INFO -> {
                val batteryPacket = BatteryInfoPacket.parse(packet)
                device.updateState { state -> state.copy(
                    battery = mergeBatteryReports(state.battery, batteryPacket.batteries,
                        device.settings.value.cacheDisconnectedComponentBattery),
                    aacpPackets = state.aacpPackets + batteryPacket
                ) }
            }

            MessageOpcode.CONTROL_COMMAND -> {
                val controlCommandPacket = try { ControlCommandPacket.parse(packet) }
                    catch (_: IllegalArgumentException) { Log.w(TAG, "Ignoring malformed control report"); return }
                val controlCommand = controlCommandPacket.controlCommand

                Log.i(TAG, "Received control command: ${controlCommand.identifier}")

                publishForTransport(this, sourceSocket, ::captureAudioSocket) {
                    device.updateState {
                        it.withControlReport(controlCommand.identifier, controlCommand.value).let { state ->
                            if (controlCommand.identifier == ControlCommandIdentifier.OWNS_CONNECTION)
                                state.copy(owns = controlCommand.value[0] == 0x01.toByte()) else state
                        }.let { it.copy(aacpPackets = it.aacpPackets + controlCommandPacket) }
                    }
                    if (sourceSocket != null && controlCommand.identifier == ControlCommandIdentifier.ALLOW_OFF_OPTION)
                        device.rememberAllowOffReport(controlCommand.value)
                    if (controlCommand.identifier !in device.state.value.requestedControls)
                        controlTimeouts.remove(controlCommand.identifier)?.cancel()
                }
            }

            MessageOpcode.EAR_DETECTION -> {
                if (packet.size < 8) return
                val earDetectionResponsePacket = EarDetectionResponsePacket.parse(packet, device.state.value.leftIsPrimary)

                device.updateState {
                    it.copy(
                        componentState = earDetectionResponsePacket.componentStates,
                        aacpPackets = it.aacpPackets + earDetectionResponsePacket
                    )
                }
            }

            MessageOpcode.CONVERSATION_AWARENESS -> {
                if (packet.size < 10) return
                val payload = packet.copyOfRange(6, packet.size)
                val packet = AACPPacket.createUnknownPacket(
                    opcode = MessageOpcode.CONVERSATION_AWARENESS,
                    payload = packet.copyOfRange(6, packet.size)
                )
                device.updateState {
                    it.copy(
                        conversationAwarenessState = payload.getOrElse(3, {0}).toInt(),
                        aacpPackets = it.aacpPackets + packet
                    )
                }
            }

            MessageOpcode.BUDDY_COMMAND -> Unit // Already framed and validated by the captured sensor session.

            MessageOpcode.MAGIC_KEYS_RESPONSE -> {
                val packet = MagicKeyResponsePacket.parse(packet)
                publishForTransport(this, sourceSocket, ::captureAudioSocket) {
                    device.updateState { it.copy(magicKeys = packet.magicKeys) }
                }
            }

            MessageOpcode.STEM_PRESS -> {
                val packet = StemPressPacket.parse(packet)

                CoroutineScope(Dispatchers.IO).launch {
                    device.emitEvent(
                        AppleEvent.StemPress(
                            pressType = packet.stemPressType,
                            bud = packet.stemPressBud
                        )
                    )
                }

                device.updateState {
                    it.copy(
                        aacpPackets = it.aacpPackets + packet
                    )
                }
            }

            MessageOpcode.AUDIO_SOURCE -> {
                try {
                    val packet = AudioSourcePacket.parse(packet)

                    device.updateState {
                        it.copy(
                            audioSource = packet.audioSource,
                            aacpPackets = it.aacpPackets + packet
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing audio source response: ${e.javaClass.simpleName}")
                }
            }

            MessageOpcode.CONNECTED_DEVICES -> {
                try {
                    val packet = ConnectedDevicesPacket.parse(packet)

                    device.updateState {
                        it.copy (
                            connectedDevices = packet.connectedDevices,
                            aacpPackets = it.aacpPackets + packet
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing connected devices response: ${e.javaClass.simpleName}")
                }
            }

            MessageOpcode.SMART_ROUTING_RESPONSE -> {
                if (packet.size < 12) return
                val packetString = packet.decodeToString()
                val sender =
                    packet.sliceArray(6..11).reversedArray().joinToString(":") { "%02X".format(it) }

                if ("iPad" in packetString) {
                    device.state.value.connectedDevices.find { it.macAddress.value == sender }?.type = "iPad"
                } else if ("Mac" in packetString) {
                    device.state.value.connectedDevices.find { it.macAddress.value == sender }?.type = "Mac"
                } else if ("iPhone" in packetString) { // not sure if this is it - don't have an iphone
                    device.state.value.connectedDevices.find { it.macAddress.value == sender }?.type = "iPhone"
                } else if ("Linux" in packetString) {
                    device.state.value.connectedDevices.find { it.macAddress.value == sender }?.type = "Linux"
                } else if ("Android" in packetString) {
                    device.state.value.connectedDevices.find { it.macAddress.value == sender }?.type = "Android"
                }
                Log.i(TAG, "Received smart routing response")
                if (packetString.contains("SetOwnershipToFalse")) {
                    CoroutineScope(Dispatchers.IO).launch {
                        device.emitEvent(
                            AppleEvent.OwnershipToFalseRequest(
                                sender = sender,
                                reverseTapped = packetString.contains("ReverseBannerTapped")
                            )
                        )
                    }
                }
                if (packetString.contains("ShowNearbyUI")) {
                    CoroutineScope(Dispatchers.IO).launch {
                        device.emitEvent(
                            AppleEvent.ShowNearbyUi(
                                sender = sender
                            )
                        )
                    }
                }

                val packet = AACPPacket.createUnknownPacket(
                    opcode = MessageOpcode.SMART_ROUTING_RESPONSE,
                    payload = packet.copyOfRange(6, packet.size)
                )

                device.updateState {
                    it.copy(
                        aacpPackets = it.aacpPackets + packet
                    )
                }
            }

            MessageOpcode.HEADPHONE_ACCOMMODATION -> {
                if (packet.size != 140) {
                    Log.w(
                        TAG,
                        "Received HEADPHONE_ACCOMMODATION packet of unexpected size: ${packet.size}, expected 140"
                    )
                    return
                }
                if (packet[6] != 0x84.toByte()) {
                    Log.w(
                        TAG,
                        "Received HEADPHONE_ACCOMMODATION packet with unexpected identifier: ${packet[6].toHexString()}, expected 0x84"
                    )
                    return
                }

                val eqOnMedia = (packet[10] == 0x01.toByte())
                val eqOnPhone = (packet[11] == 0x01.toByte())
                // there are 4 eqs. i am not sure what those are for, maybe all 4 listening modes, or maybe phone+media left+right, but then there shouldn't be another flag for phone/media visible. just directly the EQ... weird.
                // the EQs are little endian floats
                val eq1 = ByteBuffer.wrap(packet, 12, 32).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                ByteBuffer.wrap(packet, 44, 32).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                ByteBuffer.wrap(packet, 76, 32).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                ByteBuffer.wrap(packet, 108, 32).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

                // for now, taking just the first EQ
                val eqData = FloatArray(8) { i -> eq1.get(i) }

                Log.d(
                    TAG,
                    "Received headphone accommodation settings"
                )

                val packet = AACPPacket.createUnknownPacket(
                    opcode = MessageOpcode.HEADPHONE_ACCOMMODATION,
                    payload = packet.copyOfRange(6, packet.size)
                )

                device.updateState {
                    it.copy(
                        headphoneAccommodation = eqData,
                        headphoneAccommodationEnabledForMedia = eqOnMedia,
                        headphoneAccommodationEnabledForPhone = eqOnPhone,
                        aacpPackets = it.aacpPackets + packet
                    )
                }
            }

            MessageOpcode.INFORMATION -> {
                val informationPacket = InformationPacket.parse(packet)

                device.updateState {
                    it.copy(
                        aacpPackets = it.aacpPackets + informationPacket
                    )
                }
                device.updateMetadata { informationPacket.metadata }
            }

            MessageOpcode.CUSTOM_EQ -> {
                try {
                    val customEqPacket = CustomEqPacket.parse(packet)

                    val customEq = customEqPacket.customEq

                    publishForTransport(this, sourceSocket, ::captureAudioSocket) {
                        device.updateState { it.copy(customEq = customEq,
                            requestedCustomEq = it.requestedCustomEq?.takeUnless { request -> request == customEq },
                            aacpPackets = it.aacpPackets + customEqPacket) }
                        if (device.state.value.requestedCustomEq == null) { eqTimeout?.cancel(); eqTimeout = null }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse custom EQ packet: ${e.javaClass.simpleName}")
                }
            }

            MessageOpcode.MICROPHONE_STREAM -> {
                val audio = microphonePacketCallback
                if (audio != null && sourceSocket != null) {
                    audio(sourceSocket, packet)
                    return
                }
                try {
                    MicrophoneFrame.parsePacket(packet).forEach{ frame ->
                        CoroutineScope(Dispatchers.IO).launch {
                            device.emitMicrophoneFrame(frame)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse microphoneState packet: ${e.javaClass.simpleName}")
                }
            }

            MessageOpcode.MAC_ADDRESS -> Unit

            else -> {
                val packet = AACPPacket.createUnknownPacket(
                    opcode = MessageOpcode.fromByte(opcode),
                    payload = packet.copyOfRange(6, packet.size)
                )
                device.updateState {
                    it.copy(
                        aacpPackets = it.aacpPackets + packet
                    )
                }
                Log.w(TAG, "Unhandled messageOpcode received: ${opcode.toHexString()}")
            }
        }
    }

    fun sendControlCommand(identifier: Byte, value: ByteArray): Boolean {
        if (value.isEmpty() || value.size > 4) return false
        val controlCommand = ControlCommand(ControlCommandIdentifier.fromByte(identifier) ?: return false, value)

        val packet = ControlCommandPacket.create(controlCommand)

        val originalSocket = captureAudioSocket() ?: return false
        val previousReport = device.state.value.controlStates[controlCommand.identifier]
        if (!sendAudioPacket(originalSocket, packet.rawPacket)) return false
        return publishForTransport(this, originalSocket, ::captureAudioSocket) {
            device.updateState { it.withControlRequest(controlCommand.identifier, value, previousReport) }
            if (controlCommand.identifier == ControlCommandIdentifier.ALLOW_OFF_OPTION)
                device.rememberAllowOffRequest(value, previousReport)
            controlTimeouts.remove(controlCommand.identifier)?.cancel()
            val request = device.state.value.requestedControls[controlCommand.identifier]
            if (request != null && controlCommand.identifier !in setOf(ControlCommandIdentifier.ALLOW_OFF_OPTION,
                    ControlCommandIdentifier.AUTO_ANC_STRENGTH)) {
                controlTimeouts[controlCommand.identifier] = CoroutineScope(Dispatchers.Default).launch {
                    delay(10.seconds)
                    publishForTransport(this@AACPManager, originalSocket, ::captureAudioSocket) {
                        device.updateState { state ->
                            if (state.requestedControls[controlCommand.identifier] !== request) state
                            else state.copy(requestedControls = state.requestedControls - controlCommand.identifier)
                        }
                    }
                }
            }
        }
    }

    fun sendControlCommand(identifier: Byte, value: Byte): Boolean = sendControlCommand(identifier, byteArrayOf(value))
    fun sendControlCommand(identifier: Byte, value: Boolean): Boolean = sendControlCommand(identifier, if (value) byteArrayOf(0x01) else byteArrayOf(0x02))
    fun sendControlCommand(identifier: Byte, value: Int): Boolean =
        value in 0..255 && sendControlCommand(identifier, byteArrayOf(value.toByte()))

    fun sendRequestMagicKeys(type: Byte, expected: BluetoothSocket? = captureAudioSocket()): Boolean {
        Log.d(TAG, "Requesting proximity keys of type: ${type.toString(16)}")

        val payload = byteArrayOf(type, 0x00)

        val packet = AACPPacket.createUnknownPacket(
            MessageOpcode.MAGIC_KEYS_REQUEST,
            payload
        )

        return sendPacket(packet, expected)
    }

    fun sendNotificationRequest(expected: BluetoothSocket? = captureAudioSocket()): Boolean {
        // note to self #1: third byte is 0xfd when ear detection is disabled
        // note to self #2: this can be sent any time, not just at the start of the aacp connection
        val payload = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val packet = AACPPacket.createUnknownPacket(
            MessageOpcode.REQUEST_NOTIFICATIONS,
            payload
        )

        return sendPacket(packet, expected)
    }

    // todo: implement adaptive volume
    fun sendSourceFeatureCapabilities(expected: BluetoothSocket? = captureAudioSocket()): Boolean {
        val payload = byteArrayOf(0xd7.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.SOURCE_FEATURE_CAPABILITIES,
            payload
        )
        return sendPacket(packet, expected)
    }

    fun connectService4(expected: BluetoothSocket? = captureAudioSocket()): Boolean {
        val payload = byteArrayOf(0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val packet = AACPPacket.createUnknownPacket(
            type = AACPPacketType.CONNECT,
            opcode = ConnectOpcode.SOMETHING,
            payload = payload,
        )

        return sendPacket(packet, expected)
    }

    fun setHeadTrackingEnabled(enabled: Boolean): Boolean = rtBuddyManager?.setHeadTrackingEnabled(enabled) ?: false
    fun setHeartRateEnabled(enabled: Boolean): Boolean = rtBuddyManager?.setHeartRateEnabled(enabled) ?: false
    fun setSensorsSuspended(suspended: Boolean) { rtBuddyManager?.setSuspended(suspended) }

    fun sendRename(name: String): Boolean {
        val packet = try { RenamePacket.create(name) } catch (_: IllegalArgumentException) { return false }
        return sendPacket(packet)
    }

    fun sendMediaInformationNewDevice(selfMacAddress: String, targetMacAddress: String): Boolean {
        if (selfMacAddress.length != 17 || !selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) || targetMacAddress.length != 17 || !targetMacAddress.matches(
                Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
            )
        ) {
            Log.w(
                TAG,
                "Invalid MAC address format"
            )
            return false
        }

        Log.d(TAG, "Sending Media Information packet")

        val buffer = ByteBuffer.allocate(116)

        buffer.put(
            targetMacAddress.split(":").map { it.toInt(16).toByte() }.toByteArray().reversedArray()
        )
        buffer.put(byteArrayOf(0x6C, 0x00))
        buffer.put(byteArrayOf(0x01, 0xE5.toByte(), 0x4A))
        buffer.put("playingApp".toByteArray())
        buffer.put(0x42)
        buffer.put("NA".toByteArray())
        buffer.put(0x52)
        buffer.put("hostStreamingState".toByteArray())
        buffer.put(0x42)
        buffer.put("NO".toByteArray())
        buffer.put(0x49)
        buffer.put("btAddress".toByteArray())
        buffer.put(0x51)
        buffer.put(selfMacAddress.toByteArray())
        buffer.put(0x46)
        buffer.put("btName".toByteArray())
        buffer.put(0x47)
        buffer.put("Android".toByteArray())
        buffer.put(0x58)
        buffer.put("otherDevice".toByteArray())
        buffer.put("AudioCategory".toByteArray())
        buffer.put(byteArrayOf(0x30, 0x64))

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.SMART_ROUTING,
            payload = buffer.array()
        )

        return sendPacket(packet)
    }

    fun sendHijackRequest(selfMacAddress: String): Boolean {
        if (selfMacAddress.length != 17 || !selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) {
            Log.w(TAG, "Invalid MAC address format")
            return false
        }
        var success = false
        for (connectedDevice in device.state.value.connectedDevices) {
            if (connectedDevice.macAddress.value != selfMacAddress) {
                Log.d(TAG, "Sending Hijack Request packet")
                success = sendPacket(createHijackRequestPacket(connectedDevice.macAddress.value)) || success
            }
        }
        return success
    }

    fun createHijackRequestPacket(targetMacAddress: String): AACPPacket {
        val buffer = ByteBuffer.allocate(106)
        buffer.put(
            targetMacAddress.split(":").map { it.toInt(16).toByte() }.toByteArray().reversedArray()
        )
        buffer.put(byteArrayOf(0x62, 0x00))
        buffer.put(byteArrayOf(0x01, 0xE5.toByte()))
        buffer.put(0x4A)
        buffer.put("localscore".toByteArray())
        buffer.put(byteArrayOf(0x30, 0x64))
        buffer.put(0x46)
        buffer.put("reason".toByteArray())
        buffer.put(0x48)
        buffer.put("Hijackv2".toByteArray())
        buffer.put(0x51)
        buffer.put("audioRoutingScore".toByteArray())
        buffer.put(byteArrayOf(0x31, 0x2D, 0x01, 0x5F))
        buffer.put("audioRoutingSetOwnershipToFalse".toByteArray())
        buffer.put(0x01)
        buffer.put(0x4B)
        buffer.put("remotescore".toByteArray())
        buffer.put(0xA5.toByte())

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.SMART_ROUTING,
            payload = buffer.array()
        )

        return packet
    }

    fun sendMediaInformataion(selfMacAddress: String, streamingState: Boolean = false): Boolean {
        if (selfMacAddress.length != 17 || !selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) {
            // throw IllegalArgumentException("MAC address must be 6 bytes")
            Log.d(TAG, "Invalid MAC address format")
            return false
        }

        val targetMac = device.state.value.connectedDevices.find { it.macAddress.value != selfMacAddress }?.macAddress
        if (targetMac == null) {
            Log.w(TAG, "Cannot send Media Information packet: No connected device found")
            return false
        }
        Log.d(TAG, "Sending Media Information packet")

        val buffer = ByteBuffer.allocate(138)
        buffer.put(
            targetMac.value.split(":").map { it.toInt(16).toByte() }.toByteArray().reversedArray()
        )
        buffer.put(
            byteArrayOf(
                0x82.toByte(), // related to the length
                0x00
            )
        )
        buffer.put(byteArrayOf(0x01, 0xE5.toByte(), 0x4A)) // unknown, constant
        buffer.put("PlayingApp".toByteArray())
        buffer.put(byteArrayOf(0x56)) // 'V', seems like an identifier or a separator
        buffer.put("com.google.ios.youtube".toByteArray()) // package name, hardcoding for now, aforementioned reason
        buffer.put(byteArrayOf(0x52)) // 'R'
        buffer.put("HostStreamingState".toByteArray())
        buffer.put(byteArrayOf(0x42)) // 'B'
        buffer.put((if (streamingState) "YES" else "NO").toByteArray()) // streaming state
        buffer.put(0x49) // 'I'
        buffer.put("btAddress".toByteArray()) // self MAC
        buffer.put(0x51) // 'Q'
        buffer.put(selfMacAddress.toByteArray()) // self MAC
        buffer.put("btName".toByteArray()) // self name
        buffer.put(0x47) // 'D'
        buffer.put("Android".toByteArray()) // if set to iPad, shows "Moved to iPad", but most likely we're running on a phone. setting to anything else of the same length will show iPhone instead.
        buffer.put(0x58) // 'X'
        buffer.put("otherDevice".toByteArray())
        buffer.put("AudioCategory".toByteArray())
        buffer.put(byteArrayOf(0x31, 0x2D, 0x01))

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.SMART_ROUTING,
            payload = buffer.array()
        )

        return sendPacket(packet)
    }

    fun sendSmartRoutingShowUI(selfMacAddress: String): Boolean {
        if (selfMacAddress.length != 17 || !selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) {
            // throw IllegalArgumentException("MAC address must be 6 bytes")
            Log.w(TAG, "Invalid MAC address format")
            return false
        }

        val targetMac = device.state.value.connectedDevices.find { it.macAddress.value != selfMacAddress }?.macAddress
        if (targetMac == null) {
            Log.w(TAG, "Cannot send Smart Routing Show UI packet: No connected device found")
            return false
        }
        Log.d(TAG, "Sending Smart Routing Show UI packet")

        val buffer = ByteBuffer.allocate(134)
        buffer.put(
            targetMac.value.split(":").map { it.toInt(16).toByte() }.toByteArray().reversedArray()
        )
        buffer.put(byteArrayOf(0x7E, 0x00))
        buffer.put(byteArrayOf(0x01, 0xE6.toByte(), 0x5B))
        buffer.put("SmartRoutingKeyShowNearbyUI".toByteArray())
        buffer.put(0x01) // separator?
        buffer.put(0x4A)
        buffer.put("localscore".toByteArray())
        buffer.put(0x31, 0x2D)
        buffer.put(0x01)
        buffer.put(0x46)
        buffer.put("reasonHhijackv2".toByteArray())
        buffer.put(0x51.toByte())
        buffer.put("audioRoutingScore".toByteArray())
        buffer.put(0xA2.toByte())
        buffer.put(0x5F)
        buffer.put("audioRoutingSetOwnershipToFalse".toByteArray())
        buffer.put(0x01)
        buffer.put(0x4B)
        buffer.put("remotescore".toByteArray())
        buffer.put(0xA2.toByte())

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.SMART_ROUTING,
            payload = buffer.array()
        )

        return sendPacket(packet)
    }

    fun sendHijackReversed(selfMacAddress: String): Boolean {
        var success = false
        for (connectedDevice in device.state.value.connectedDevices) {
            if (connectedDevice.macAddress.value != selfMacAddress) {
                Log.d(TAG, "Sending Hijack Reversed packet")
                success = sendPacket(createHijackReversedPacket(connectedDevice.macAddress.value)) || success
            }
        }
        return success
    }

    fun createHijackReversedPacket(targetMacAddress: String): AACPPacket {
        val buffer = ByteBuffer.allocate(97)
        buffer.put(
            targetMacAddress.split(":").map { it.toInt(16).toByte() }.toByteArray().reversedArray()
        )
        buffer.put(byteArrayOf(0x59, 0x00))
        buffer.put(byteArrayOf(0x01, 0xE3.toByte()))
        buffer.put(0x5F)
        buffer.put("audioRoutingSetOwnershipToFalse".toByteArray())
        buffer.put(0x01)
        buffer.put(0x59)
        buffer.put("audioRoutingShowReverseUI".toByteArray())
        buffer.put(0x01)
        buffer.put(0x46)
        buffer.put("reason".toByteArray())
        buffer.put(0x53)
        buffer.put("ReverseBannerTapped".toByteArray())

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.SMART_ROUTING,
            payload = buffer.array()
        )

        return packet
    }

    fun sendAddTiPiDevice(selfMacAddress: String, targetMacAddress: String): Boolean {
        if (selfMacAddress.length != 17 || !selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) || targetMacAddress.length != 17 || !targetMacAddress.matches(
                Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
            )
        ) {
            // throw IllegalArgumentException("MAC address must be 6 bytes")
            Log.w(
                TAG,
                "Invalid MAC address format"
            )
            return false
        }
        Log.d(TAG, "Sending Add TiPi Device packet")

        val buffer = ByteBuffer.allocate(90)
        buffer.put(
            targetMacAddress.split(":").map { it.toInt(16).toByte() }.toByteArray().reversedArray()
        )
        buffer.put(byteArrayOf(0x52, 0x00))
        buffer.put(byteArrayOf(0x01, 0xE5.toByte()))
        buffer.put(0x48) // 'H'
        buffer.put("idleTime".toByteArray())
        buffer.put(byteArrayOf(0x08, 0x47))
        buffer.put("newTipi".toByteArray())
        buffer.put(byteArrayOf(0x01, 0x49))
        buffer.put("btAddress".toByteArray())
        buffer.put(0x51)
        buffer.put(selfMacAddress.toByteArray())
        buffer.put(0x46)
        buffer.put("btName".toByteArray())
        buffer.put(0x47)
        buffer.put("Android".toByteArray())
        buffer.put(0x50)
        buffer.put("nearbyAudioScore".toByteArray())
        buffer.put(byteArrayOf(0x0E))

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.SMART_ROUTING,
            payload = buffer.array()
        )

        return sendPacket(packet)
    }

    fun sendRawGesturesConfig(
        singlePressCustomized: Boolean = false,
        doublePressCustomized: Boolean = false,
        triplePressCustomized: Boolean = false,
        longPressCustomized: Boolean = false
    ): Boolean {
        val value = (
            (if (singlePressCustomized) 0x01 else 0) or
            (if (doublePressCustomized) 0x02 else 0) or
            (if (triplePressCustomized) 0x04 else 0) or
            (if (longPressCustomized) 0x08 else 0)
        ).toByte()

        return sendControlCommand(
            ControlCommandIdentifier.RAW_GESTURES_CONFIG.value, value
        )
    }

    fun sendPhoneMediaEQ(eq: FloatArray, phone: Byte = 0x02.toByte(), media: Byte = 0x02.toByte()) {
        if (eq.size != 8) throw IllegalArgumentException("EQ must be 8 floats")
        val header = byteArrayOf(
            0x84.toByte(),
            0x00.toByte(),
            0x02.toByte(),
            0x02.toByte(),
            phone,
            media
        )
        val buffer = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        for (block in 0..3) {
            for (i in 0..7) {
                buffer.putFloat(eq[i])
            }
        }

        val payload = header + buffer.array()

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.HEADPHONE_ACCOMMODATION,
            payload = payload
        )

        sendPacket(packet)

        device.updateState {
            it.copy(
                headphoneAccommodation = eq.copyOf(),
                headphoneAccommodationEnabledForMedia = media == 0x01.toByte(),
                headphoneAccommodationEnabledForPhone = phone == 0x01.toByte()
            )
        }
    }

    fun sendCountryCode(expected: BluetoothSocket? = captureAudioSocket()): Boolean {
        val payload = byteArrayOf(
            0x00, 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(),
        )

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.SET_COUNTRY_CODE,
            payload = payload
        )

        return sendPacket(packet, expected)
    }

    fun disconnect(expected: BluetoothSocket? = socket, notifyDevice: Boolean = false,
        preserveControlState: Boolean = false): Boolean {
        var retiredSensors: RTBuddyManager? = null
        val retired = retireForTransport(this, expected, { socket }) {
            retiredSensors = rtBuddyManager
            socket = null
            rtBuddyManager = null
            connectionEpoch.incrementAndGet()
            connectionScope?.cancel()
            connectionScope = null
            controlTimeouts.values.forEach { it.cancel() }
            controlTimeouts.clear()
            eqTimeout?.cancel()
            eqTimeout = null
            device.updateState {
                val knownBatteries = it.battery.filter { battery ->
                    battery.level in 0..100 && battery.observedAt != null && battery.observedAt >= 0 &&
                        battery.status != BatteryStatus.UNKNOWN
                }
                val retainedBatteries = when {
                    preserveControlState && !notifyDevice -> knownBatteries.toSet()
                    device.settings.value.cacheDisconnectedComponentBattery -> knownBatteries.map { battery ->
                        battery.copy(status = BatteryStatus.DISCONNECTED, present = false)
                    }.toSet()
                    else -> emptySet()
                }
                it.copy(battery = retainedBatteries, connectedDevices = emptyList(),
                    controlStates = if (preserveControlState)
                        it.cachedControlStates(device.settings.value, device.metadata.value.modelNumber) else emptyMap(),
                    requestedControls = emptyMap(), requestedCustomEq = null, aacpPackets = emptyList(),
                    headTrackingState = BuddyState.INACTIVE, hrmState = BuddyState.INACTIVE, currentHeartRate = null)
            }
            // The notification belongs to this retirement, before a replacement can be published.
            if (notifyDevice) runCatching { device.onAacpDisconnected() }.onFailure {
                Log.w(TAG, "Disconnect notification failed: ${it.javaClass.simpleName}")
            }
        }
        if (!retired) return false
        try { expected?.close() } catch (error: Exception) {
            Log.e(TAG, "Error closing socket: ${error.javaClass.simpleName}")
        }
        retiredSensors?.close()
        Log.i(TAG, "disconnected")
        return true
    }

    fun setCustomEq(customEq: CustomEq): Boolean {
        val originalSocket = captureAudioSocket() ?: return false
        val previousReport = device.state.value.customEq
        val payload = customEq.toAACPPayload()

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.CUSTOM_EQ,
            payload = payload
        )

        if (!sendAudioPacket(originalSocket, packet.rawPacket)) return false
        return publishForTransport(this, originalSocket, ::captureAudioSocket) {
            device.updateState { state ->
                if (state.customEq != previousReport) state
                else state.copy(requestedCustomEq = customEq.takeUnless { it == state.customEq })
            }
            eqTimeout?.cancel()
            eqTimeout = CoroutineScope(Dispatchers.Default).launch {
                delay(10.seconds)
                publishForTransport(this@AACPManager, originalSocket, ::captureAudioSocket) {
                    device.updateState { state ->
                        if (state.requestedCustomEq !== customEq) state else state.copy(requestedCustomEq = null)
                    }
                }
            }
        }
    }

    fun requestMicrophoneStream(expected: BluetoothSocket? = captureAudioSocket()): Boolean {
        val payload = byteArrayOf(
            0x00, 0x00,
            0x09, 0x00, // length
            0x00, 0x01,
            0x82.toByte(), 0x00,
            0x00, 0x00,
            0x04, 0x96.toByte(),
            0x00
        )

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.MICROPHONE_STREAM,
            payload = payload
        )

        return expected != null && sendAudioPacket(expected, packet.rawPacket)
    }

    fun endMicrophoneStream(expected: BluetoothSocket? = captureAudioSocket()): Boolean {
        val payload = byteArrayOf(
            0x00, 0x00,
            0x02, 0x00, // length
            0x03, 0x01
        )

        val packet = AACPPacket.createUnknownPacket(
            opcode = MessageOpcode.MICROPHONE_STREAM,
            payload = payload
        )

        return expected != null && sendAudioPacket(expected, packet.rawPacket)
    }

    fun parseCapabilitiesResponse(packet: ByteArray): Set<CapabilityEntry> {
        require(packet.size >= 7) { "Packet too short" }
        require(packet[4] == MessageOpcode.CAPABILITIES.value) {
            "Not a capabilities packet"
        }

        var offset = 6
        val capabilityCount = packet[offset++].toInt() and 0xFF

        val capabilities = mutableSetOf<CapabilityEntry>()

        repeat(capabilityCount) {
            if (offset >= packet.size) {
                throw IllegalArgumentException("Unexpected end of packet")
            }

            val capabilityId = packet[offset++]
            val capability = Capability.fromByte(capabilityId)
                ?: throw IllegalArgumentException(
                    "Unknown capability 0x%02X".format(capabilityId.toInt() and 0xFF)
                )

            if (offset + capability.valueSize > packet.size) {
                throw IllegalArgumentException("Truncated packet")
            }

            capabilities += CapabilityEntry(
                capability,
                packet.copyOfRange(offset, offset + capability.valueSize)
            )

            offset += capability.valueSize
        }

        for (capability in capabilities) {
            Log.d(
                TAG,
                "Capability: ${capability.capability.name}"
            )
        }

        return capabilities
    }

}
