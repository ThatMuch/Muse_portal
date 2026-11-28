package com.portalmuse.gadget

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.portalmuse.core.BleCharacteristic
import com.portalmuse.core.BleConnection
import com.portalmuse.core.BleDevice
import com.portalmuse.core.BleError
import com.portalmuse.core.BleNotification
import com.portalmuse.core.BleRadio
import com.portalmuse.core.BleTarget
import com.portalmuse.core.BleValue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "PortalMuse"
private const val MAX_CONNECTIONS = 3
private const val OPERATION_MS = 15_000L
private const val MAX_NOTIFICATIONS = 200
private const val IDLE_DISCONNECT_MS = 5 * 60_000L
private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"
private val CLIENT_CONFIGURATION = UUID.fromString("00002902$BASE_SUFFIX")

/**
 * The Portal's Bluetooth LE "central" role, for Muse's commands. It touches neither the radio state nor the
 * pairing advertisement.
 */
@SuppressLint("MissingPermission")
object BleCentral {
    private val connections = ConcurrentHashMap<Int, Link>()
    private val seen = ConcurrentHashMap<String, Pair<BluetoothDevice, String?>>()
    private var nextId = 0

    fun radio(context: Context): BleRadio = Radio(context.applicationContext)

    /** The feature is turned off, or the app is stopping. */
    fun closeAll() {
        for (id in connections.keys.toList()) connections.remove(id)?.close()
    }

    /** An open connection, and the queue of answers from its Bluetooth stack. */
    private class Link(val address: String) : BluetoothGattCallback() {
        class Reply(val kind: String, val status: Int, val data: ByteArray? = null)

        lateinit var gatt: BluetoothGatt
        val replies = LinkedBlockingQueue<Reply>()
        val notifications = LinkedBlockingQueue<Pair<Int, ByteArray>>()
        @Volatile var open = false
        @Volatile var mtu = 23
        @Volatile var usedAt = SystemClock.elapsedRealtime()

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            open = status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED
            replies.offer(Reply("state", if (open) 0 else maxOf(status, 1)))
        }

        override fun onMtuChanged(gatt: BluetoothGatt, size: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = size
            replies.offer(Reply("mtu", status))
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            replies.offer(Reply("services", status))
        }

        @Deprecated("Android 10")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            replies.offer(Reply("read", status, characteristic.value?.copyOf()))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            replies.offer(Reply("write", status))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            replies.offer(Reply("descriptor", status))
        }

        @Deprecated("Android 10")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value?.copyOf() ?: return
            if (notifications.size < MAX_NOTIFICATIONS) notifications.offer(characteristic.instanceId to value)
        }

        /** Starts an operation and waits for its answer; one at a time per connection. */
        fun operation(kind: String, timeout: Long = OPERATION_MS, start: () -> Boolean): Reply {
            replies.clear()
            if (!start()) throw BleError("busy", "the Bluetooth stack refused the operation")
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (true) {
                val left = deadline - SystemClock.elapsedRealtime()
                val reply = replies.poll(maxOf(left, 0), TimeUnit.MILLISECONDS)
                    ?: throw BleError("timeout", "the device did not answer")
                if (reply.kind == "state" && reply.status != 0) {
                    throw BleError("disconnected", "the connection was lost or refused (status ${reply.status})")
                }
                if (reply.kind != kind) continue
                if (reply.status != 0) throw BleError("gatt_error", "the device refused (status ${reply.status})")
                return reply
            }
        }

        fun close() {
            open = false
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    private class Radio(private val context: Context) : BleRadio {
        private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java).adapter

        override fun problem(): String? = when {
            adapter == null || !adapter.isEnabled -> "Bluetooth is off on the device"
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ->
                "the owner has not granted the location permission that Android requires for Bluetooth scans"
            ScreenBridge.presenter == null -> "the Portal Muse screen is not in front"
            else -> null
        }

        override fun scan(durationMillis: Long, active: Boolean, name: String?, limit: Int): List<BleDevice> {
            val scanner = adapter?.bluetoothLeScanner ?: throw BleError("unavailable", "no BLE scanner")
            val found = ConcurrentHashMap<String, BleDevice>()
            var failure = 0
            val callback = object : ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult) {
                    val record = result.scanRecord
                    val address = result.device.address
                    val label = record?.deviceName ?: found[address]?.name
                    val maker = record?.manufacturerSpecificData?.takeIf { it.size() > 0 }?.let { data ->
                        val company = data.keyAt(0)
                        byteArrayOf(company.toByte(), (company shr 8).toByte()) + data.valueAt(0)
                    }
                    seen[address] = result.device to label
                    found[address] = BleDevice(
                        address, result.rssi, label, result.isConnectable,
                        record?.serviceUuids.orEmpty().map { short(it.uuid) }, maker,
                    )
                }

                override fun onScanFailed(code: Int) {
                    failure = code
                }
            }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(null, settings, callback)
            try {
                Thread.sleep(durationMillis)
            } finally {
                runCatching { scanner.stopScan(callback) }
            }
            if (failure != 0) throw BleError("scan_failed", "Android refused the scan (code $failure)")
            Log.i(TAG, "bluetooth: scan, ${found.size} device(s)")
            return found.values.filter { name == null || it.name?.contains(name, ignoreCase = true) == true }
                .sortedByDescending { it.rssi }.take(limit)
        }

        override fun nameOf(address: String): String? = seen[address]?.second

        @Synchronized
        override fun connect(address: String, timeoutMillis: Long): BleConnection {
            dropIdle()
            connections.entries.firstOrNull { it.value.address == address && it.value.open }
                ?.let { return BleConnection(it.key, address, it.value.mtu) }
            if (connections.size >= MAX_CONNECTIONS) {
                throw BleError("too_many", "at most $MAX_CONNECTIONS connections; disconnect one first")
            }
            val device = seen[address]?.first ?: adapter?.getRemoteDevice(address)
                ?: throw BleError("unavailable", "no Bluetooth adapter")
            val link = Link(address)
            try {
                link.operation("state", timeoutMillis) {
                    link.gatt = device.connectGatt(context, false, link, BluetoothDevice.TRANSPORT_LE)
                        ?: return@operation false
                    true
                }
                // Longer exchanges, when the device accepts them.
                runCatching { link.operation("mtu", 3_000) { link.gatt.requestMtu(247) } }
            } catch (e: BleError) {
                link.close()
                throw e
            }
            val id = ++nextId
            connections[id] = link
            Log.i(TAG, "bluetooth: connection $id open")
            return BleConnection(id, address, link.mtu)
        }

        override fun connections(): List<BleConnection> {
            dropIdle()
            return connections.entries.map { BleConnection(it.key, it.value.address, it.value.mtu) }
        }

        private fun dropIdle() {
            val now = SystemClock.elapsedRealtime()
            for ((id, link) in connections.entries.toList()) {
                if (!link.open || now - link.usedAt > IDLE_DISCONNECT_MS) connections.remove(id)?.close()
            }
        }

        private fun link(conn: Int): Link {
            dropIdle()
            return connections[conn]?.also { it.usedAt = SystemClock.elapsedRealtime() }
                ?: throw BleError("not_connected", "no such connection; use ble.connect")
        }

        override fun disconnect(conn: Int) {
            link(conn)
            connections.remove(conn)?.close()
        }

        private fun characteristics(link: Link): List<BluetoothGattCharacteristic> {
            if (link.gatt.services.isEmpty()) link.operation("services") { link.gatt.discoverServices() }
            return link.gatt.services.flatMap { it.characteristics }
        }

        override fun discover(conn: Int): List<BleCharacteristic> {
            val link = link(conn)
            return synchronized(link) {
                characteristics(link).take(120).map { item ->
                    BleCharacteristic(short(item.service.uuid), short(item.uuid), item.instanceId, properties(item))
                }
            }
        }

        private fun find(link: Link, target: BleTarget): BluetoothGattCharacteristic {
            val matches = characteristics(link).filter { item ->
                if (target.handle != null) {
                    item.instanceId == target.handle
                } else {
                    short(item.uuid) == target.uuid &&
                        (target.service == null || short(item.service.uuid) == target.service)
                }
            }
            if (matches.size > 1) throw BleError("ambiguous", "several characteristics match; use the handle")
            return matches.singleOrNull() ?: throw BleError("not_found", "no such characteristic; use ble.discover")
        }

        override fun read(conn: Int, target: BleTarget): BleValue {
            val link = link(conn)
            return synchronized(link) {
                val item = find(link, target)
                val reply = link.operation("read") { link.gatt.readCharacteristic(item) }
                BleValue(item.instanceId, reply.data ?: ByteArray(0))
            }
        }

        @Suppress("DEPRECATION")
        override fun write(conn: Int, target: BleTarget, data: ByteArray, withResponse: Boolean?): Int {
            val link = link(conn)
            return synchronized(link) {
                val item = find(link, target)
                val acknowledged = item.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                item.writeType = if (withResponse ?: acknowledged) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                if (data.size > link.mtu - 3 && item.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                    throw BleError("too_long", "without response, at most ${link.mtu - 3} bytes")
                }
                item.value = data
                link.operation("write") { link.gatt.writeCharacteristic(item) }
                Log.i(TAG, "bluetooth: write of ${data.size} bytes")
                item.instanceId
            }
        }

        @Suppress("DEPRECATION")
        override fun subscribe(conn: Int, target: BleTarget, durationMillis: Long): List<BleNotification> {
            val link = link(conn)
            return synchronized(link) {
                val item = find(link, target)
                val notify = item.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val indicate = item.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                val configuration = item.getDescriptor(CLIENT_CONFIGURATION)
                if ((!notify && !indicate) || configuration == null) {
                    throw BleError("not_supported", "that characteristic does not notify")
                }
                link.notifications.clear()
                link.gatt.setCharacteristicNotification(item, true)
                configuration.value = if (notify) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
                link.operation("descriptor") { link.gatt.writeDescriptor(configuration) }
                val start = SystemClock.elapsedRealtime()
                val values = ArrayList<BleNotification>()
                while (values.size < MAX_NOTIFICATIONS) {
                    val left = start + durationMillis - SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    val next = link.notifications.poll(left, TimeUnit.MILLISECONDS) ?: break
                    if (next.first == item.instanceId) {
                        values.add(BleNotification(SystemClock.elapsedRealtime() - start, next.second))
                    }
                }
                link.gatt.setCharacteristicNotification(item, false)
                configuration.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                runCatching { link.operation("descriptor", 3_000) { link.gatt.writeDescriptor(configuration) } }
                link.usedAt = SystemClock.elapsedRealtime()
                values
            }
        }

        private fun short(uuid: UUID): String {
            val text = uuid.toString()
            return if (text.startsWith("0000") && text.endsWith(BASE_SUFFIX)) text.substring(4, 8) else text
        }

        private fun properties(item: BluetoothGattCharacteristic) = listOf(
            BluetoothGattCharacteristic.PROPERTY_READ to "read",
            BluetoothGattCharacteristic.PROPERTY_WRITE to "write",
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE to "write_no_response",
            BluetoothGattCharacteristic.PROPERTY_NOTIFY to "notify",
            BluetoothGattCharacteristic.PROPERTY_INDICATE to "indicate",
        ).filter { item.properties and it.first != 0 }.map { it.second }
    }
}
