package me.kavishdevar.librepods.dongle

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Control
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Frame
import me.kavishdevar.librepods.dongle.DongleControlProtocol.PhoneTransport.Operation as ReportOperation
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Rpc
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Usb
import me.kavishdevar.librepods.shizuku.IUsbHidConnection

internal class DongleUsbControlsUnavailable(cause: Throwable? = null) : IOException(
    if (cause == null) "Root Bluetooth access is required to share USB controls held by Android's media-key driver."
    else "The shared USB helper could not open controls while Android's media-key driver is attached.", cause)

/** Control-only and audio personalities share a board serial, but not interface numbers. */
internal object DongleUsb {
    fun candidate(vendor: Int, product: Int) =
        vendor == Usb.VENDOR_ID && product in listOf(Usb.CONTROL_ONLY_PRODUCT_ID, Usb.AUDIO_PRODUCT_ID)
    fun candidate(device: UsbDevice) = candidate(device.vendorId, device.productId)
    fun serial(device: UsbDevice): String? = runCatching { device.serialNumber?.takeIf(String::isNotBlank) }.getOrNull()
    fun key(device: UsbDevice) = serial(device)?.let { "usb:serial:$it" } ?: "usb:path:${device.deviceName}"
    fun devices(manager: UsbManager) = manager.deviceList.values.filter(::candidate)
    fun resolve(devices: List<UsbDevice>, selected: String): UsbDevice? = devices.firstOrNull { key(it) == selected }
        ?: devices.singleOrNull()?.takeIf { serial(it) == null && selected.startsWith("usb:serial:") }
    fun audioAvailable(device: UsbDevice) = (0 until device.interfaceCount).any {
        device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
    }

    // Parse HID items rather than accepting the consumer collection on the same interface.
    fun vendorReports(descriptor: ByteArray): Boolean {
        var page = 0; var usage = 0; var size = 0; var count = 0; var report = 0
        var vendorDepth = -1; var depth = 0
        val reports = mutableSetOf<Int>()
        var at = 0
        while (at < descriptor.size) {
            val prefix = descriptor[at++].toInt() and 255
            if (prefix == HID_LONG_ITEM) return false
            val sizeCode = prefix and HID_ITEM_SIZE_MASK
            val length = when (sizeCode) { HID_ITEM_SIZE_CODE_FOUR_BYTES -> 4; else -> sizeCode }
            if (at + length > descriptor.size) return false
            var value = 0L
            repeat(length) { value = value or ((descriptor[at++].toLong() and 255) shl (8 * it)) }
            when (prefix and HID_ITEM_TAG_AND_TYPE_MASK) {
                HID_USAGE_PAGE -> page = value.toInt()
                HID_USAGE -> usage = value.toInt()
                HID_REPORT_SIZE -> size = value.toInt()
                HID_REPORT_COUNT -> count = value.toInt()
                HID_REPORT_ID -> report = value.toInt()
                HID_COLLECTION -> {
                    depth++
                    val vendorCollection = page == Usb.VENDOR_USAGE_PAGE && usage == Usb.VENDOR_USAGE &&
                        value == HID_APPLICATION_COLLECTION
                    if (vendorCollection) vendorDepth = depth
                    usage = 0
                }
                HID_END_COLLECTION -> {
                    if (vendorDepth == depth) vendorDepth = -1
                    depth--
                    if (depth < 0) return false
                }
                HID_FEATURE -> {
                    val dongleReport = report in Control.REPORT_ID..Rpc.REPORT_ID &&
                        size == Usb.REPORT_SIZE_BITS && count == Usb.REPORT_COUNT
                    if (vendorDepth > 0 && dongleReport) reports += report
                }
                // Push/pop globals are not used by this version; reject rather than misidentify them.
                HID_PUSH, HID_POP -> return false
            }
        }
        return depth == 0 && reports.containsAll(listOf(Control.REPORT_ID, Rpc.REPORT_ID))
    }

    suspend fun open(manager: UsbManager, device: UsbDevice,
        shared: suspend (UsbInterface) -> IUsbHidConnection? = { null }): Link {
        check(candidate(device) && manager.hasPermission(device)) { "Allow USB access to connect the dongle" }
        val interfaces = (0 until device.interfaceCount).map(device::getInterface).filter {
            it.interfaceClass == UsbConstants.USB_CLASS_HID && it.interfaceSubclass == 0 && it.interfaceProtocol == 0
        }
        if (interfaces.isEmpty()) throw IOException("USB device does not expose the supported dongle controls")
        val connection = manager.openDevice(device) ?: throw IOException("USB dongle is unavailable")
        var retained = false
        try {
            var descriptorFailed = false
            val vendor = interfaces.filter { intf ->
                val descriptor = ByteArray(MAXIMUM_REPORT_DESCRIPTOR_SIZE)
                val count = connection.controlTransfer(STANDARD_INTERFACE_IN, GET_DESCRIPTOR, HID_REPORT_DESCRIPTOR_VALUE,
                    intf.id, descriptor, descriptor.size, TRANSFER_TIMEOUT_MILLISECONDS)
                if (count !in 1..descriptor.size) { descriptorFailed = true; false }
                else vendorReports(descriptor.copyOf(count))
            }
            if (vendor.size > 1 || vendor.isEmpty() && !descriptorFailed)
                throw IOException("USB device does not expose unique supported dongle controls")
            if (vendor.size == 1) {
                val link = Link(connection, vendor.single())
                try {
                    DongleProtocol.status(link.exchange(ReportOperation.READ_REPORT, Control.REPORT_ID, byteArrayOf()))
                    retained = true
                    return link
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                }
            }
        } finally { if (!retained) connection.close() }
        // Only startup reads can select a different transport. Never replay a write.
        var problem: Exception? = null
        var selected: Link? = null
        try {
            for (intf in interfaces) {
                val handle = try { shared(intf) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { problem = error; null } ?: continue
                val link = try {
                    if (!vendorReports(handle.descriptor())) throw IOException("Unsupported shared USB descriptor")
                    Link(handle).also {
                        DongleProtocol.status(it.exchange(ReportOperation.READ_REPORT, Control.REPORT_ID, byteArrayOf()))
                    }
                } catch (error: Exception) {
                    runCatching { handle.close() }
                    if (error is CancellationException) throw error
                    problem = error
                    continue
                }
                if (selected != null) {
                    link.close()
                    throw IOException("USB device exposes ambiguous shared dongle controls")
                }
                selected = link
            }
            return selected ?: throw DongleUsbControlsUnavailable(problem)
        } catch (error: Exception) { runCatching { selected?.close() }; throw error }
    }

    // USB HID 1.11 short item prefixes (tag and type, size bits cleared).
    private const val HID_LONG_ITEM = 0xfe
    private const val HID_ITEM_SIZE_MASK = 3
    private const val HID_ITEM_SIZE_CODE_FOUR_BYTES = 3
    private const val HID_ITEM_TAG_AND_TYPE_MASK = 0xfc
    private const val HID_USAGE_PAGE = 0x04
    private const val HID_USAGE = 0x08
    private const val HID_REPORT_SIZE = 0x74
    private const val HID_REPORT_COUNT = 0x94
    private const val HID_REPORT_ID = 0x84
    private const val HID_COLLECTION = 0xa0
    private const val HID_END_COLLECTION = 0xc0
    private const val HID_FEATURE = 0xb0
    private const val HID_PUSH = 0xa4
    private const val HID_POP = 0xb4
    private const val HID_APPLICATION_COLLECTION = 1L

    // USB control transfers.
    private const val STANDARD_INTERFACE_IN = 0x81
    private const val CLASS_INTERFACE_IN = 0xa1
    private const val CLASS_INTERFACE_OUT = 0x21
    private const val GET_DESCRIPTOR = 6
    private const val HID_GET_REPORT = 1
    private const val HID_SET_REPORT = 9
    /** Descriptor type 0x22 (HID report) in the high byte, index 0. */
    private const val HID_REPORT_DESCRIPTOR_VALUE = 0x2200
    /** Report type 3 (feature) in the high byte; the report id goes in the low byte. */
    private const val HID_FEATURE_REPORT_TYPE = 0x300
    private const val MAXIMUM_REPORT_DESCRIPTOR_SIZE = 1024
    private const val TRANSFER_TIMEOUT_MILLISECONDS = 1000

    class Link private constructor(private val connection: UsbDeviceConnection?, private val intf: UsbInterface?,
        private val shared: IUsbHidConnection?) : Closeable {
        constructor(connection: UsbDeviceConnection, intf: UsbInterface) : this(connection, intf, null)
        constructor(shared: IUsbHidConnection) : this(null, null, shared)
        private val closed = AtomicBoolean(false)
        fun exchange(operation: Int, report: Int, payload: ByteArray): ByteArray {
            val reading = operation == ReportOperation.READ_REPORT
            require(operation in ReportOperation.READ_REPORT..ReportOperation.WRITE_REPORT &&
                report in Control.REPORT_ID..Rpc.REPORT_ID && payload.size == if (reading) 0 else Frame.SIZE)
            if (operation == ReportOperation.WRITE_REPORT) DongleProtocol.checkReport(payload, report)
            if (closed.get()) throw IOException("USB dongle controls are closed")
            if (shared != null) {
                val bytes = try { shared.exchange(operation, report, payload.copyOf()) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { throw IOException("Shared USB control transfer failed", error) }
                if (closed.get()) throw IOException("USB dongle controls were retired")
                DongleProtocol.checkReport(bytes, report)
                return bytes
            }
            val bytes = if (reading) ByteArray(Frame.SIZE) else payload.copyOf()
            // Only unbound interfaces use USBFS; bound consumer HID uses the root shared path.
            val requestType = if (reading) CLASS_INTERFACE_IN else CLASS_INTERFACE_OUT
            val request = if (reading) HID_GET_REPORT else HID_SET_REPORT
            val count = checkNotNull(connection).controlTransfer(requestType, request, HID_FEATURE_REPORT_TYPE or report,
                checkNotNull(intf).id, bytes, bytes.size, TRANSFER_TIMEOUT_MILLISECONDS)
            if (count != Frame.SIZE || closed.get()) throw IOException("USB dongle control transfer ended")
            if (reading) return bytes.also { DongleProtocol.checkReport(it, report) }
            return DongleProtocol.header(report)
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            if (shared != null) shared.close() else connection?.close()
        }
    }
}
