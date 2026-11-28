package com.portalmuse.core

import com.portalmuse.core.rtc.Rtp
import com.portalmuse.core.rtc.Srtp
import com.portalmuse.core.rtc.Stun
import com.portalmuse.core.rtc.ThriftReader
import com.portalmuse.core.rtc.ThriftWriter
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class VideoCallTest {
    private val executor = Executors.newCachedThreadPool()
    private var session: LinkSession? = null
    private val edge = Edge()

    @After fun stop() {
        edge.socket.close()
        session?.stop()
        executor.shutdownNow()
    }

    /** A media endpoint on the loopback: answers ICE, then sends voice and picture. */
    private class Edge {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        @Volatile var key: ByteArray? = null
        @Volatile var password = ""
        @Volatile var speak = true
        val heard = LinkedBlockingQueue<ByteArray>()

        fun start() = thread(isDaemon = true) {
            val buffer = ByteArray(2048)
            var sent = false
            var receiver: Srtp? = null
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: java.io.IOException) {
                    return@thread
                }
                val data = buffer.copyOf(packet.length)
                fun reply(bytes: ByteArray) = socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                if (Stun.isStun(data)) {
                    val header = ByteBuffer.allocate(20).putShort(0x0101).putShort(24).putInt(0x2112A442)
                        .put(data, 8, 12).array()
                    val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(password.toByteArray(), "HmacSHA1")) }
                    reply(header + byteArrayOf(0, 8, 0, 20) + mac.doFinal(header))
                    // Nominating the path carries the "USE-CANDIDATE" attribute.
                    val nominated = (20 until data.size - 3 step 4).any { data[it] == 0.toByte() && data[it + 1] == 0x25.toByte() }
                    if (nominated && !sent) {
                        sent = true
                        val sender = Srtp(key!!)
                        val picture = Rtp.build(127, 1, 0, 201, byteArrayOf(0x65, 4, 2)).also { it[1] = (127 or 0x80).toByte() }
                        reply(sender.protectRtp(picture))
                        if (speak) for (i in 1..3) reply(sender.protectRtp(Rtp.build(111, i, i * 960L, 200, byteArrayOf(i.toByte()))))
                        // A packet sent back with our own SSRC is discarded.
                        reply(sender.protectRtp(Rtp.build(111, 9, 0, 100, byteArrayOf(99))))
                    }
                } else if (!Rtp.isRtcp(data)) {
                    val reader = receiver ?: Srtp(key!!).also { receiver = it }
                    heard.add(Rtp.parse(reader.unprotectRtp(data)).payload)
                }
            }
        }
    }

    private class FakeAudio(private val level: Byte = 1) : VideoAudio {
        val played: MutableList<Byte> = Collections.synchronizedList(mutableListOf())
        @Volatile var capturing = true
        override fun read(): ByteArray? {
            Thread.sleep(20)
            return if (capturing) ByteArray(1920) { level } else null
        }
        override fun encode(pcm: ByteArray) = listOf(byteArrayOf(pcm[0], 42))
        /** A loud sound, to mute the microphone. */
        override fun decode(opus: ByteArray): ByteArray {
            played.add(opus[0])
            return ByteArray(1920) { if (it % 2 == 0) 0 else 40 }
        }
        override fun play(pcm: ByteArray) = Unit
        override fun stopCapture() { capturing = false }
    }

    private fun vm(joinStatus: Int = 200, stopFor: (String) -> String? = { null }, callIds: MutableList<String> = mutableListOf()) = FakeVm(
        onRequest = { call ->
            fun result(value: JSONObject, status: Int = 200) = sendFrame(
                ServiceFrame.Response(
                    call.streamId, status, JSONObject().put("ok", true).put("result", value).toString().toByteArray(),
                    endBody = true,
                ),
            )
            when (call.path) {
                "/voice/calls/media-endpoint" -> result(
                    JSONObject().put("host", "127.0.0.1").put("port", edge.socket.localPort).put("vip_type", "HATCH"),
                )
                "/chat/subscribe" -> {
                    sendFrame(ServiceFrame.Response(call.streamId, 200))
                    stopFor(callIds.last())?.let { id ->
                        val item = JSONObject().put("type", "session_control").put("name", "stop").put("call_id", "tool-1")
                        val inner = JSONObject().put("type", "conversation.item.added").put("item", item)
                        val event = JSONObject().put("type", "event").put("event", "hatch.voice.realtime_event")
                            .put("payload", JSONObject().put("call_id", id).put("event", inner))
                        sendFrame(ServiceFrame.BodyChunk(call.streamId, "$event\n".toByteArray()))
                    }
                }
                "/voice/calls/join" -> {
                    callIds.add(JSONObject(String(call.body)).getString("call_id"))
                    val offer = ThriftReader(Base64.getDecoder().decode(JSONObject(String(call.body)).getString("payload_b64")))
                    offer.struct()
                    val request = offer.struct()[1] as Map<*, *>
                    val crypto = request[3] as Map<*, *>
                    edge.key = Base64.getDecoder().decode(crypto[1] as ByteArray)
                    edge.password = String(crypto[3] as ByteArray)
                    val answer = ThriftWriter().apply {
                        i32(4, 200)
                        stop()
                        begin(2)
                        binary(1, "edge")
                        end()
                        stop()
                    }.bytes()
                    result(JSONObject().put("payload_b64", Base64.getEncoder().encodeToString(answer)), joinStatus)
                }
                "/voice/end-call" -> result(JSONObject().put("ok", true))
            }
        },
        onMessage = { send(JSONObject().put("id", it.getString("id")).put("ok", true)) },
    )

    private class Started(
        val call: VideoCall, val done: CompletableFuture<CallResult>, val audio: FakeAudio,
        val pictures: LinkedBlockingQueue<ByteArray>,
    ) {
        fun result(): CallResult = done.get(15, TimeUnit.SECONDS)
    }

    private fun start(vm: FakeVm, idle: Long = 20_000, firstTurn: Long? = null, level: Byte = 1): Started {
        edge.start()
        val registered = CountDownLatch(1)
        val link = LinkSession(
            vm, DeviceDescription("homelink-abcdef", "v", "1", "m", "M"), DeviceCommands({ JSONObject() }, "x"),
            executor, onRegistered = { registered.countDown() }, registerDevice = false,
        )
        session = link
        executor.execute { link.run() }
        assertTrue(registered.await(5, TimeUnit.SECONDS))
        val audio = FakeAudio(level)
        val pictures = LinkedBlockingQueue<ByteArray>()
        val call = VideoCall(
            link, "homelink-abcdef", audio, onVideo = pictures::add, idleTimeoutMillis = idle,
            readyTimeoutMillis = 5_000, cleanupTimeoutMillis = 2_000, permitted = { true },
            firstTurnTimeoutMillis = firstTurn, museName = "Nova",
        )
        return Started(call, CompletableFuture.supplyAsync({ call.run() }, executor), audio, pictures)
    }

    /** The next requests, apart from the conversation subscription. */
    private fun FakeVm.paths(count: Int) = generateSequence { calls.poll(5, TimeUnit.SECONDS)!! }
        .filter { it.path != "/chat/subscribe" }.take(count).toList()

    @Test fun aVideoCallJoinsShowsMuseAndSendsTheMutedThenLiveMicrophone() {
        val vm = vm()
        val run = start(vm)
        val (endpoint, join) = vm.paths(2)
        assertEquals("GET", endpoint.verb)
        val body = JSONObject(String(join.body))
        assertEquals("video", body.getString("call_media_type"))
        assertEquals("homelink-abcdef", body.getString("device_id"))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 4, 2), run.pictures.poll(5, TimeUnit.SECONDS))
        // While Muse speaks, silence; then the microphone.
        val sent = generateSequence { edge.heard.poll(3, TimeUnit.SECONDS)?.get(0) }.take(60).toList()
        assertTrue("silence pendant la voix de Muse : $sent", 0.toByte() in sent)
        assertEquals("micro rétabli", 1.toByte(), sent.last())
        assertEquals(listOf<Byte>(1, 2, 3), run.audio.played.toList())
        run.call.hangUp()
        assertEquals(CallResult.Ended(CallEnd.HUNG_UP), run.result())
        val end = vm.paths(1).single()
        assertEquals("/voice/end-call", end.path)
        assertEquals(body.getString("call_id"), JSONObject(String(end.body)).getString("call_id"))
    }

    @Test fun aSilentVideoCallHangsUpOnItsOwn() {
        edge.speak = false
        val vm = vm()
        val run = start(vm, idle = 800)
        assertEquals(CallResult.Ended(CallEnd.IDLE), run.result())
        assertEquals("/voice/end-call", vm.paths(3).last().path)
    }

    @Test fun museCanHangUpAVideoCallThroughTheConversationEvents() {
        val run = start(vm(stopFor = { it }))
        assertEquals(CallResult.Ended(CallEnd.REMOTE_STOP), run.result())
    }

    @Test fun anotherCallsStopIsIgnored() {
        edge.speak = false
        val run = start(vm(stopFor = { "un-autre-appel" }), idle = 4_000)
        assertEquals(CallResult.Ended(CallEnd.IDLE), run.result())
    }

    /** A transcription relayed by the conversation, the JSON stored in a string. */
    private fun heard(phrase: String, asMessage: Boolean = false) = FakeVm(
        onRequest = { call ->
            fun result(value: JSONObject) = sendFrame(
                ServiceFrame.Response(
                    call.streamId, 200, JSONObject().put("ok", true).put("result", value).toString().toByteArray(),
                    endBody = true,
                ),
            )
            when (call.path) {
                "/chat/subscribe" -> {
                    sendFrame(ServiceFrame.Response(call.streamId, 200))
                    val inner = JSONObject().put("type", "conversation.item.input_audio_transcription.completed")
                        .put("transcript", phrase)
                    val event = if (asMessage) {
                        JSONObject().put("type", "event").put("event", "message.user")
                            .put("payload", JSONObject().put("message_id", "u").put("display_text", phrase))
                    } else {
                        JSONObject().put("type", "event").put("event", "hatch.voice.realtime_event")
                            .put("payload", JSONObject().put("data", inner.toString()))
                    }
                    // After someone spoke in front of the device.
                    thread {
                        Thread.sleep(400)
                        sendFrame(ServiceFrame.BodyChunk(call.streamId, "$event\n".toByteArray()))
                    }
                }
                "/voice/calls/media-endpoint" -> result(
                    JSONObject().put("host", "127.0.0.1").put("port", edge.socket.localPort).put("vip_type", "HATCH"),
                )
                "/voice/calls/join" -> {
                    val offer = ThriftReader(Base64.getDecoder().decode(JSONObject(String(call.body)).getString("payload_b64")))
                    offer.struct()
                    val crypto = (offer.struct()[1] as Map<*, *>)[3] as Map<*, *>
                    edge.key = Base64.getDecoder().decode(crypto[1] as ByteArray)
                    edge.password = String(crypto[3] as ByteArray)
                    val answer = ThriftWriter().apply {
                        i32(4, 200)
                        stop()
                        begin(2)
                        binary(1, "edge")
                        end()
                        stop()
                    }.bytes()
                    result(JSONObject().put("payload_b64", Base64.getEncoder().encodeToString(answer)))
                }
                "/voice/end-call" -> result(JSONObject().put("ok", true))
            }
        },
        onMessage = { send(JSONObject().put("id", it.getString("id")).put("ok", true)) },
    )

    @Test fun sayingGoodbyeEndsTheVideoCall() {
        edge.speak = false
        assertEquals(CallResult.Ended(CallEnd.GOODBYE), start(heard("Merci, au revoir !"), level = 20).result())
    }

    @Test fun aGoodbyePostedAsAMessageCountsToo() {
        edge.speak = false
        assertEquals(CallResult.Ended(CallEnd.GOODBYE), start(heard("Au revoir Nova.", asMessage = true), level = 20).result())
    }

    @Test fun anotherSentenceOrASilentRoomDoesNot() {
        edge.speak = false
        assertEquals(CallResult.Ended(CallEnd.IDLE), start(heard("Quel temps fait-il ?"), idle = 1_500, level = 20).result())
    }

    @Test fun aVolumeRequestEndsTheVideoCallWithoutPlayingTheReply() {
        edge.speak = false
        assertEquals(CallResult.Ended(CallEnd.VOLUME), start(heard("Baisse le son."), level = 20).result())
    }

    @Test fun aWakeCallWithoutAnyReplyCloses() {
        edge.speak = false
        assertEquals(CallResult.Ended(CallEnd.NOTHING_ASKED), start(vm(), firstTurn = 700).result())
    }

    @Test fun aRefusedJoinFailsWithItsStatusAndStillEndsTheCall() {
        val vm = vm(joinStatus = 403)
        assertEquals(CallResult.Failed(CallFailure.REFUSED, 403), start(vm).result())
        assertEquals("/voice/end-call", vm.paths(3).last().path)
    }

    @Test fun privateMediaAddressesAreNotContactedByDefault() {
        assertTrue(!WebImages.isPublic(InetAddress.getByName("127.0.0.1")))
        assertTrue(!WebImages.isPublic(InetAddress.getByName("192.168.1.1")))
    }
}
