// Les commandes ble.* reprennent les noms et les formes de muse-gadget-sdk (Copyright (c) Meta
// Platforms, Inc. and affiliates, Apache-2.0). Source : esp32/main/ble_commands.c
package com.portalmuse.core

import org.json.JSONArray
import org.json.JSONObject

/** Un appareil vu pendant une recherche. */
data class BleDevice(
    val address: String, val rssi: Int, val name: String?, val connectable: Boolean,
    val services: List<String> = emptyList(), val manufacturerData: ByteArray? = null,
)

data class BleCharacteristic(val service: String, val uuid: String, val handle: Int, val properties: List<String>)

data class BleConnection(val conn: Int, val address: String, val mtu: Int)

/** La caractéristique visée : par [handle], ou par [uuid] (et [service] s'il y en a plusieurs). */
data class BleTarget(val handle: Int?, val uuid: String?, val service: String?)

class BleValue(val handle: Int, val data: ByteArray)

class BleNotification(val millis: Long, val data: ByteArray)

/** Un échec à dire à Muse : un code court et une phrase, en anglais. */
class BleError(val code: String, message: String) : Exception(message)

/** La radio de l'appareil. Tous les appels bloquent et lèvent [BleError]. */
interface BleRadio {
    /** `null` si tout est prêt, sinon pourquoi le Bluetooth est inutilisable. */
    fun problem(): String?
    fun scan(durationMillis: Long, active: Boolean, name: String?, limit: Int): List<BleDevice>
    /** Le nom connu d'une adresse, s'il y en a un. */
    fun nameOf(address: String): String?
    fun connect(address: String, timeoutMillis: Long): BleConnection
    fun connections(): List<BleConnection>
    fun disconnect(conn: Int)
    fun discover(conn: Int): List<BleCharacteristic>
    fun read(conn: Int, target: BleTarget): BleValue
    fun write(conn: Int, target: BleTarget, data: ByteArray, withResponse: Boolean?): Int
    fun subscribe(conn: Int, target: BleTarget, durationMillis: Long): List<BleNotification>
}

/** Les appareils que le propriétaire a acceptés, une fois pour toutes. */
interface ApprovedDevices {
    fun contains(address: String): Boolean
    fun add(address: String, name: String?)
}

/**
 * Le Bluetooth du Portal, vu par Muse. Chercher est permis dès que le propriétaire a activé la
 * fonction ; se connecter à un nouvel appareil demande son accord à l'écran.
 */
class BluetoothCommands(
    private val radio: BleRadio,
    private val approved: ApprovedDevices,
    private val askOwner: (Question) -> AskResult,
) : CommandSet {
    override fun specs(): Map<String, JSONObject> {
        fun conn() = JSONObject().put("conn", Specs.field("integer", "From ble.connect."))
        fun characteristic() = JSONObject()
            .put("handle", Specs.field("integer", "Value handle from ble.discover."))
            .put("uuid", Specs.field("string", "Or the characteristic UUID, e.g. 2a19."))
            .put("service", Specs.field("string", "Service UUID, if several share uuid."))
        fun spec(description: String, required: JSONObject, optional: JSONObject, timeout: Int) =
            Specs.of(description, required, optional).put("timeout_ms", timeout)
        return linkedMapOf(
            SCAN to spec(
                "Scan for nearby BLE devices, strongest first: address, rssi, name, connectable, services, " +
                    "manufacturer_data (hex).",
                JSONObject(),
                JSONObject()
                    .put("duration_ms", Specs.field("integer", "100-30000 (default 5000)."))
                    .put("name", Specs.field("string", "Only names containing this."))
                    .put("limit", Specs.field("integer", "1-40 (default 20).")),
                40_000,
            ),
            CONNECT to spec(
                "Connect to a BLE device (up to 3). The first time for a device, the owner is asked on the " +
                    "touchscreen. Returns conn and mtu.",
                JSONObject().put("address", Specs.field("string", "AA:BB:CC:DD:EE:FF, from ble.scan.")),
                JSONObject().put("timeout_ms", Specs.field("integer", "1000-30000 (default 10000).")),
                110_000,
            ),
            STATE to spec("Bluetooth state and open connections.", JSONObject(), JSONObject(), 10_000),
            DISCONNECT to spec("Close a connection.", conn(), JSONObject(), 10_000),
            DISCOVER to spec(
                "List a connected device's characteristics: service, UUID, handle, properties.",
                conn(), JSONObject(), 45_000,
            ),
            READ to spec("Read a characteristic. Returns hex, and text when printable.", conn(), characteristic(), 45_000),
            WRITE to spec(
                "Write a characteristic. Write only what the person asked for.",
                conn(),
                characteristic()
                    .put("hex", Specs.field("string", "Bytes as hex."))
                    .put("text", Specs.field("string", "Or UTF-8 text."))
                    .put("response", Specs.field("boolean", "false: without response.")),
                45_000,
            ),
            SUBSCRIBE to spec(
                "Collect a characteristic's notifications for duration_ms.",
                conn(), characteristic().put("duration_ms", Specs.field("integer", "100-60000 (default 5000).")), 75_000,
            ),
        )
    }

    override fun run(command: String, params: JSONObject): JSONObject? {
        if (command !in specs().keys) return null
        radio.problem()?.let { return failure("unavailable", it) }
        return try {
            Specs.ok(execute(command, params))
        } catch (e: BleError) {
            failure(e.code, e.message.orEmpty())
        }
    }

    private fun failure(code: String, message: String) = Specs.error("$code: $message")

    private fun invalid(message: String): Nothing = throw BleError("invalid_params", message)

    private fun number(params: JSONObject, key: String, range: IntRange, default: Int): Int {
        if (!params.has(key)) return default
        val value = (params.opt(key) as? Number)?.toInt()
        if (value == null || value !in range) invalid("$key must be ${range.first}-${range.last}")
        return value
    }

    private fun conn(params: JSONObject) =
        (params.opt("conn") as? Number)?.toInt() ?: invalid("conn (from ble.connect) is required")

    private fun target(params: JSONObject): BleTarget {
        val handle = (params.opt("handle") as? Number)?.toInt()
        val uuid = Specs.text(params, "uuid", 36)?.lowercase()
        if (handle == null && uuid == null) invalid("give handle or uuid")
        return BleTarget(handle, uuid, Specs.text(params, "service", 36)?.lowercase())
    }

    private fun execute(command: String, params: JSONObject): JSONObject = when (command) {
        SCAN -> {
            val devices = radio.scan(
                number(params, "duration_ms", 100..30_000, 5_000).toLong(), params.opt("active") != false,
                Specs.text(params, "name", 60), number(params, "limit", 1..40, 20),
            )
            val list = devices.sortedByDescending { it.rssi }.map { device ->
                JSONObject().put("address", device.address).put("rssi", device.rssi)
                    .putOpt("name", device.name).put("connectable", device.connectable)
                    .putOpt("services", device.services.takeIf { it.isNotEmpty() }?.let { JSONArray(it) })
                    .putOpt("manufacturer_data", device.manufacturerData?.let(::hex))
            }
            JSONObject().put("found", list.size).put("devices", JSONArray(list))
        }
        CONNECT -> connect(params)
        STATE -> JSONObject().put(
            "connections",
            JSONArray(radio.connections().map { JSONObject().put("conn", it.conn).put("address", it.address) }),
        )
        DISCONNECT -> {
            radio.disconnect(conn(params))
            JSONObject().put("disconnected", true)
        }
        DISCOVER -> JSONObject().put(
            "characteristics",
            JSONArray(
                radio.discover(conn(params)).map {
                    JSONObject().put("service", it.service).put("uuid", it.uuid).put("handle", it.handle)
                        .put("properties", JSONArray(it.properties))
                },
            ),
        )
        READ -> {
            val conn = conn(params)
            val value = radio.read(conn, target(params))
            JSONObject().put("conn", conn).put("handle", value.handle).put("length", value.data.size)
                .put("hex", hex(value.data)).putOpt("text", printable(value.data))
        }
        WRITE -> {
            val conn = conn(params)
            val data = data(params)
            val handle = radio.write(conn, target(params), data, params.opt("response") as? Boolean)
            JSONObject().put("conn", conn).put("handle", handle).put("written", data.size)
        }
        else -> {
            val conn = conn(params)
            val values = radio.subscribe(conn, target(params), number(params, "duration_ms", 100..60_000, 5_000).toLong())
            JSONObject().put("conn", conn).put(
                "notifications",
                JSONArray(values.map { JSONObject().put("ms", it.millis).put("hex", hex(it.data)) }),
            )
        }
    }

    private fun connect(params: JSONObject): JSONObject {
        val address = Specs.text(params, "address", 17)?.uppercase() ?: invalid("address is required")
        if (!ADDRESS.matches(address)) invalid("address must look like AA:BB:CC:DD:EE:FF")
        val timeout = number(params, "timeout_ms", 1_000..30_000, 10_000).toLong()
        if (!approved.contains(address)) {
            // Le nom vient de l'appareil lui-même : on montre toujours l'adresse avec.
            val name = radio.nameOf(address)?.filterNot { it.isISOControl() }?.take(40)
            val answer = askOwner(
                Question(
                    "Autoriser la connexion à cet objet Bluetooth ?",
                    "${name ?: "Sans nom"}\n$address\n\nUne fois autorisé, Muse pourra le lire et le commander " +
                        "sans redemander.",
                    "Autoriser", "Refuser", APPROVAL_MS,
                ),
            )
            if (answer != AskResult.APPROVED) {
                throw BleError("not_approved", "the owner did not allow this device (${answer.name.lowercase()})")
            }
            approved.add(address, name)
        }
        val connection = radio.connect(address, timeout)
        return JSONObject().put("conn", connection.conn).put("address", connection.address).put("mtu", connection.mtu)
    }

    private fun data(params: JSONObject): ByteArray {
        val hex = params.opt("hex") as? String
        val text = params.opt("text") as? String
        if ((hex == null) == (text == null)) invalid("give either hex or text")
        val data = if (hex != null) {
            val clean = hex.filterNot { it.isWhitespace() || it == ':' }
            if (clean.length % 2 != 0 || clean.length !in 2..1024 || !clean.all { Character.digit(it, 16) >= 0 }) {
                invalid("hex must be 1-512 bytes of hex digits")
            }
            ByteArray(clean.length / 2) { clean.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        } else {
            text!!.toByteArray()
        }
        if (data.isEmpty() || data.size > 512) invalid("write 1 to 512 bytes")
        return data
    }

    private fun hex(data: ByteArray) = data.joinToString("") { "%02x".format(it) }

    private fun printable(data: ByteArray): String? =
        String(data).takeIf { text -> text.isNotEmpty() && text.all { it in ' '..'~' || it.code in 160..0x24F } }

    private companion object {
        const val SCAN = "ble.scan"
        const val CONNECT = "ble.connect"
        const val STATE = "ble.state"
        const val DISCONNECT = "ble.disconnect"
        const val DISCOVER = "ble.discover"
        const val READ = "ble.read"
        const val WRITE = "ble.write"
        const val SUBSCRIBE = "ble.subscribe"
        const val APPROVAL_MS = 60_000L
        val ADDRESS = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
    }
}
