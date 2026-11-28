package com.portalmuse.gadget

import com.portalmuse.core.tr
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.portalmuse.core.BleFraming
import com.portalmuse.core.SetupController
import com.portalmuse.core.SetupTransport
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private const val TAG = "PortalMuseBle"
private val SERVICE_UUID = UUID.fromString("7fdd3d1c-38ea-46cf-8b46-314ecf5f240c")
private val RX_UUID = UUID.fromString("4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01")
private val TX_UUID = UUID.fromString("d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c")
private val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** Unassigned company identifier; the byte that follows is the "configured" flag. */
private const val PAIRED_FLAG_COMPANY_ID = 0xFFFF

/**
 * GATT server and BLE advertisement for setup, the Android equivalent of `ble_server.py`. One phone at a time.
 * Messages are handled in order on [worker].
 */
@SuppressLint("MissingPermission")
class BlePeripheral(
    private val context: Context,
    private val onState: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) : SetupTransport {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val notificationSent = Semaphore(0)
    private val mtus = HashMap<String, Int>()
    private val preparedWrite = ByteArrayOutputStream()
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private lateinit var tx: BluetoothGattCharacteristic
    private lateinit var controller: SetupController

    @Volatile private var client: BluetoothDevice? = null
    @Volatile private var subscribed = false
    @Volatile private var lastValue = ByteArray(0)

    fun start(setup: SetupController) {
        controller = setup
        val opened = manager.openGattServer(context, gattCallback)
            ?: return onFailure(tr("GATT server unavailable", "serveur GATT indisponible"))
        server = opened
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                RX_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        tx = BluetoothGattCharacteristic(
            TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                CLIENT_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(tx)
        if (!opened.addService(service)) onFailure(tr("GATT service refused", "service GATT refusé"))
    }

    fun stop() {
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null
        client?.let { server?.cancelConnection(it) }
        client = null
        server?.close()
        server = null
        worker.shutdownNow()
    }

    private fun advertise() {
        val leAdvertiser = manager.adapter.bluetoothLeAdvertiser
            ?: return onFailure(tr("BLE advertising unavailable", "annonce BLE non disponible"))
        advertiser = leAdvertiser
        val parameters = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // 3 (flags) + 18 (128 bit UUID) + 5 (manufacturer) = 26 bytes out of 31.
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addManufacturerData(PAIRED_FLAG_COMPANY_ID, byteArrayOf(0))
            .build()
        // The name (16 characters) goes in the scan response.
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        leAdvertiser.startAdvertising(parameters, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "advertising started under the name ${manager.adapter.name}")
            onState("visible")
        }

        override fun onStartFailure(errorCode: Int) = onFailure(tr(
            "BLE advertising refused (code $errorCode)",
            "annonce BLE refusée (code $errorCode)",
        ))
    }

    // -- Sending (called off the GATT callback thread) ---------------------------

    override fun mtu(): Int = synchronized(mtus) { client?.let { mtus[it.address] } ?: BleFraming.DEFAULT_ATT_MTU }

    override fun sendPackets(packets: List<ByteArray>) {
        for ((index, packet) in packets.withIndex()) {
            val device = client ?: return
            val gatt = server ?: return
            if (index > 0) Thread.sleep(BleFraming.CHUNK_STAGGER_MS)
            lastValue = packet
            if (!subscribed) {
                Log.w(TAG, "the phone is not subscribed to notifications")
                continue
            }
            notificationSent.drainPermits()
            tx.value = packet
            if (!gatt.notifyCharacteristicChanged(device, tx, false)) {
                Log.w(TAG, "notification refused (packet ${index + 1}/${packets.size})")
                return
            }
            if (!notificationSent.tryAcquire(2, TimeUnit.SECONDS)) Log.w(TAG, "notification without acknowledgement")
        }
    }

    override fun disconnect(delayMillis: Long) {
        main.postDelayed({ client?.let { server?.cancelConnection(it) } }, delayMillis)
    }

    // -- Receiving ---------------------------------------------------------------

    private fun deliver(packet: ByteArray) {
        val message = controller.assemble(packet) ?: return
        submit { controller.handleMessage(message) }
    }

    private fun submit(task: () -> Unit) {
        if (worker.isShutdown) return
        worker.execute {
            try {
                task()
            } catch (e: Exception) {
                // The class only: an error message could contain received data.
                Log.e(TAG, "setup command failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun claim(device: BluetoothDevice): Boolean {
        val current = client
        if (current == null) client = device
        return current == null || current.address == device.address
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) main.post { if (server != null) advertise() }
            else onFailure(tr(
                "GATT service not added (status $status)",
                "service GATT non ajouté (statut $status)",
            ))
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val connected = newState == BluetoothProfile.STATE_CONNECTED
            Log.i(TAG, "device ${if (connected) "connected" else "disconnected"} (status $status)")
            if (connected) return
            synchronized(mtus) { mtus.remove(device.address) }
            if (client?.address != device.address) return
            client = null
            subscribed = false
            preparedWrite.reset()
            notificationSent.release()
            submit { controller.onDisconnect() }
            onState("visible")
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(TAG, "ATT MTU $mtu")
            synchronized(mtus) { mtus[device.address] = mtu }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            prepared: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val bytes = value ?: ByteArray(0)
            var status = BluetoothGatt.GATT_SUCCESS
            if (characteristic.uuid != RX_UUID || !claim(device)) {
                status = BluetoothGatt.GATT_FAILURE
            } else if (prepared) {
                // Long write: the phone exceeds the MTU and sends in several parts.
                if (offset != preparedWrite.size()) status = BluetoothGatt.GATT_INVALID_OFFSET
                else if (offset + bytes.size > BleFraming.MAX_MESSAGE_BYTES) {
                    status = BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                } else preparedWrite.write(bytes)
            }
            if (responseNeeded) server?.sendResponse(device, requestId, status, offset, bytes)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "write refused (status $status)")
            } else if (!prepared) {
                onState(tr("phone connected", "téléphone connecté"))
                deliver(bytes)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val ours = client?.address == device.address
            val complete = preparedWrite.toByteArray()
            if (ours) preparedWrite.reset()
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (ours && execute && complete.isNotEmpty()) deliver(complete)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            val value = lastValue
            if (characteristic.uuid != TX_UUID || client?.address != device.address || offset > value.size) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            } else {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            prepared: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val ok = descriptor.uuid == CLIENT_CONFIG_UUID && !prepared && claim(device)
            if (ok) {
                subscribed = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                Log.i(TAG, "notifications ${if (subscribed) "enabled" else "disabled"}")
            }
            if (responseNeeded) {
                val status = if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
                server?.sendResponse(device, requestId, status, offset, value)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            val value = if (subscribed && client?.address == device.address) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notificationSent.release()
        }
    }
}
