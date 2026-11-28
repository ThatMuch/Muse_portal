// Kotlin port of muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates, Apache-2.0). Source:
// linux/src/musegadget/noise/envelope.py
package com.portalmuse.core

enum class ServiceType(val wire: Int) {
    DAEMON(0),

    /** The VM's Gadget API, meant for device tokens. */
    GADGET(6),
}

/** An HTTP stream frame carried in the Noise session. */
sealed class ServiceFrame(val streamId: Long) {
    class Request(
        streamId: Long,
        val verb: String,
        val path: String,
        val headers: List<Pair<String, String>> = emptyList(),
        val body: ByteArray = ByteArray(0),
        val endBody: Boolean = false,
    ) : ServiceFrame(streamId)

    class Response(
        streamId: Long,
        val status: Int,
        val body: ByteArray = ByteArray(0),
        val endBody: Boolean = false,
        val headers: List<Pair<String, String>> = emptyList(),
    ) : ServiceFrame(streamId)

    class BodyChunk(streamId: Long, val data: ByteArray, val endBody: Boolean = false) : ServiceFrame(streamId)

    class Reset(streamId: Long, val code: Int, val reason: String) : ServiceFrame(streamId)
}

object Envelope {
    /** Device to VM: `ServiceRequest`. */
    fun encodeRequest(service: ServiceType, frame: ServiceFrame): ByteArray {
        val writer = ProtoWriter()
        if (service.wire != 0) writer.varint(1, service.wire.toLong())
        return writer.bytes(2, encodeFrame(frame)).toByteArray()
    }

    /** VM to device: `ServiceResponse`. */
    fun decodeResponse(data: ByteArray): ServiceFrame {
        val payload = ProtoReader.read(data).lastOrNull { it.id == 1 }?.asBytes()
        if (payload == null || payload.isEmpty()) throw NoiseError("empty service response")
        return decodeFrame(payload)
    }

    /** The opposite directions, for the simulated VM of the tests. */
    internal fun encodeResponse(frame: ServiceFrame): ByteArray = ProtoWriter().bytes(1, encodeFrame(frame)).toByteArray()

    internal fun decodeRequest(data: ByteArray): Pair<ServiceType, ServiceFrame> {
        val fields = ProtoReader.read(data)
        val wire = fields.lastOrNull { it.id == 1 }?.asLong()?.toInt() ?: 0
        val service = ServiceType.values().single { it.wire == wire }
        return service to decodeFrame(fields.last { it.id == 2 }.asBytes())
    }

    private fun encodeFrame(frame: ServiceFrame): ByteArray {
        val inner = ProtoWriter()
        val kind = when (frame) {
            is ServiceFrame.Request -> {
                if (frame.verb.isNotEmpty()) inner.string(1, frame.verb)
                if (frame.path.isNotEmpty()) inner.string(2, frame.path)
                for ((key, value) in frame.headers) {
                    inner.bytes(3, ProtoWriter().string(1, key).string(2, value).toByteArray())
                }
                if (frame.body.isNotEmpty()) inner.bytes(4, frame.body)
                if (frame.endBody) inner.varint(5, 1)
                2
            }
            is ServiceFrame.Response -> {
                if (frame.status != 0) inner.varint(1, frame.status.toLong())
                for ((key, value) in frame.headers) {
                    inner.bytes(2, ProtoWriter().string(1, key).string(2, value).toByteArray())
                }
                if (frame.body.isNotEmpty()) inner.bytes(3, frame.body)
                if (frame.endBody) inner.varint(4, 1)
                3
            }
            is ServiceFrame.BodyChunk -> {
                if (frame.data.isNotEmpty()) inner.bytes(1, frame.data)
                if (frame.endBody) inner.varint(2, 1)
                4
            }
            is ServiceFrame.Reset -> {
                if (frame.code != 0) inner.varint(1, frame.code.toLong())
                if (frame.reason.isNotEmpty()) inner.string(2, frame.reason)
                5
            }
        }
        val writer = ProtoWriter()
        if (frame.streamId != 0L) writer.varint(1, frame.streamId)
        return writer.bytes(kind, inner.toByteArray()).toByteArray()
    }

    private fun decodeFrame(data: ByteArray): ServiceFrame {
        var streamId = 0L
        var body: ProtoField? = null
        for (field in ProtoReader.read(data)) {
            if (field.id == 1) streamId = field.asLong() else if (field.id in 2..5) body = field
        }
        val kind = body ?: throw NoiseError("service frame without content")
        val all = ProtoReader.read(kind.asBytes())
        val fields = all.associateBy { it.id }
        /** Headers are a repeated field: 3 in a request, 2 in a response. */
        fun headers(id: Int) = all.filter { it.id == id }.map { header ->
            val parts = ProtoReader.read(header.asBytes()).associateBy { it.id }
            (parts[1]?.asString() ?: "") to (parts[2]?.asString() ?: "")
        }
        fun number(id: Int) = fields[id]?.asLong() ?: 0L
        fun bytes(id: Int) = fields[id]?.asBytes() ?: ByteArray(0)
        return when (kind.id) {
            2 -> ServiceFrame.Request(
                streamId, String(bytes(1)), String(bytes(2)), headers(3), bytes(4), endBody = number(5) != 0L,
            )
            3 -> ServiceFrame.Response(streamId, number(1).toInt(), bytes(3), number(4) != 0L, headers(2))
            4 -> ServiceFrame.BodyChunk(streamId, bytes(1), number(2) != 0L)
            else -> ServiceFrame.Reset(streamId, number(1).toInt(), String(bytes(2)))
        }
    }
}
