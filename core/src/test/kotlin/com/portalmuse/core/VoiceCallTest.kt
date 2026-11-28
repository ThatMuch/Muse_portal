package com.portalmuse.core

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.OffsetDateTime
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VoiceCallTest {
    private val executor = Executors.newCachedThreadPool()
    private val fixture = resource("realtime_voice.json")
    private var session: LinkSession? = null

    @After fun stop() {
        session?.stop()
        executor.shutdownNow()
    }

    /** 40 ms at 24 kHz: an audible tone. */
    private val tone: ByteArray = ByteBuffer.allocate(1920).order(ByteOrder.LITTLE_ENDIAN).apply {
        repeat(960) { putShort((2000 * Math.sin(2 * Math.PI * 440 * it / 24000)).toInt().toShort()) }
    }.array()

    private class FakeAudio(private val loud: Boolean = false, private val roll: PreRoll? = null) : CallAudio {
        val played: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        @Volatile var flushes = 0
        @Volatile var captures = true
        override fun read(): ByteArray? {
            if (roll != null) return roll.read()
            Thread.sleep(10)
            val high: Byte = if (loud) 20 else 0
            return if (captures) ByteArray(1920) { if (it % 2 == 0) 50 else high } else null
        }
        override fun play(pcm: ByteArray) { played.add(pcm) }
        override fun flush() { flushes++ }
        override fun stopCapture() {
            captures = false
            roll?.stop()
        }
    }

    private inner class Run(
        idle: Long = 20_000, overall: Long = 60_000, ready: Long = 3_000, firstTurn: Long? = null,
        stalled: Long = 60_000, loudRoom: Boolean = false, preRoll: PreRoll? = null,
    ) {
        val vm = FakeVm(onRequest = { call ->
            if (call.path == "/voice/end-call") {
                sendFrame(ServiceFrame.Response(call.streamId, 200, """{"ok":true,"result":{"ok":true}}""".toByteArray(), endBody = true))
            }
        })
        val audio = FakeAudio(loudRoom, preRoll)
        val events: MutableList<CallEvent> = java.util.concurrent.CopyOnWriteArrayList()
        val logs: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        val call: VoiceCall
        val done: CompletableFuture<CallResult>
        var media = 0L
        lateinit var start: JSONObject
        lateinit var path: String

        init {
            val opened = CountDownLatch(1)
            val link = LinkSession(
                vm, DeviceDescription("homelink-abcdef", "v", "1", "m", "M"), DeviceCommands({ JSONObject() }, "x"),
                executor, onRegistered = { opened.countDown() }, registerDevice = false,
            )
            session = link
            executor.execute { link.run() }
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            call = VoiceCall(
                link, "homelink-abcdef", audio, idleTimeoutMillis = idle, overallTimeoutMillis = overall,
                readyTimeoutMillis = ready, cleanupTimeoutMillis = 2_000, firstTurnTimeoutMillis = firstTurn, stalledTurnTimeoutMillis = stalled,
                onEvent = events::add, log = logs::add,
            )
            done = CompletableFuture.supplyAsync({ call.run() }, executor)
        }

        fun nextData(): ServiceFrame.BodyChunk = checkNotNull(vm.duplexData.poll(3, TimeUnit.SECONDS)) { "rien envoyé" }
        fun nextEvent() = JSONObject(String(nextData().data))

        /** Reads the opening of the call. */
        fun admit(status: Int = 200) {
            val request = checkNotNull(vm.duplexOpened.poll(3, TimeUnit.SECONDS))
            media = request.streamId
            path = request.path
            assertEquals(ServiceType.DAEMON, request.service)
            start = nextEvent()
            assertEquals("hatch.voice.call.start", start.getString("type"))
            val offer = nextEvent()
            assertEquals("hatch.voice.capability.offer", offer.getString("type"))
            assertEquals(1, offer.getInt("end_call_stop"))
            vm.sendFrame(ServiceFrame.Response(media, status))
        }

        fun server(vararg records: JSONObject) {
            val raw = records.joinToString("") { "$it\n" }.toByteArray()
            // Arbitrary splits, as on the network.
            for (offset in raw.indices step 997) {
                vm.sendFrame(ServiceFrame.BodyChunk(media, raw.copyOfRange(offset, minOf(offset + 997, raw.size))))
            }
        }

        fun ready(id: String = start.getString("call_id")) = server(
            JSONObject().put("type", "hatch.voice.session").put("call_id", id)
                .put("session_id", fixture.getString("session_id")).put("thread_id", fixture.getString("thread_id")),
        )

        fun connect() {
            admit()
            ready()
        }

        fun result(): CallResult = done.get(8, TimeUnit.SECONDS)
        fun endCall(): JSONObject {
            val end = vm.calls.poll(3, TimeUnit.SECONDS)!!
            assertEquals("/voice/end-call", end.path)
            return JSONObject(String(end.body))
        }

        fun eventually(what: String, check: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 3_000
            while (!check()) {
                assertTrue(what, System.currentTimeMillis() < deadline)
                Thread.sleep(10)
            }
        }
    }

    private fun delta(pcm: ByteArray, id: String? = null) = JSONObject().put("type", "continuous_audio_video.delta")
        .put(
            "audio_delta",
            JSONObject().put("audio_frames", java.util.Base64.getEncoder().encodeToString(pcm))
                .put("format", resource("realtime_voice.json").getJSONObject("audio_format"))
                .put("num_channels", 1),
        ).apply { id?.let { put("response_id", it) } }

    private fun event(type: String) = JSONObject().put("type", type)

    @Test fun aWholeCallFromStartToRemoteEnd() = with(Run()) {
        admit()
        assertEquals("/voice/realtime?call_id=${start.getString("call_id")}", path)
        assertEquals("mcu-wearable", start.getString("device"))
        assertEquals("homelink-abcdef", start.getString("device_id"))
        assertEquals("audio", start.getString("call_media_type"))
        assertEquals(false, start.getBoolean("proactive_greeting_enabled"))
        assertNull("rien du micro avant que l'appel soit prêt", vm.duplexData.poll(200, TimeUnit.MILLISECONDS))
        ready()
        val append = nextEvent()
        assertEquals("input_audio_buffer.append", append.getString("type"))
        assertEquals(1920, java.util.Base64.getDecoder().decode(append.getString("audio")).size)
        assertTrue(append.getLong("send_timestamp_ms") > 0)
        server(
            event("response.created").put("response", JSONObject().put("id", "r1")),
            event("response.output_audio_transcript.delta").put("delta", "Bon"),
            delta(tone),
            event("response.output_audio_transcript.done").put("transcript", "Bonjour"),
        )
        eventually("audio joué") { audio.played.size == 1 }
        assertArrayEquals(tone, audio.played.single())
        eventually("texte affiché") { events.filterIsInstance<CallEvent.Words>().map { it.text } == listOf("Bon", "Bonjour") }
        assertTrue(CallEvent.Connected in events)
        vm.sendFrame(ServiceFrame.BodyChunk(media, ByteArray(0), endBody = true))
        val body = endCall()
        assertEquals(CallResult.Ended(CallEnd.REMOTE_END), result())
        assertEquals(start.getString("call_id"), body.getString("call_id"))
        assertEquals(fixture.getString("thread_id"), body.getString("thread_id"))
        val from = OffsetDateTime.parse(body.getString("call_started_at"))
        assertTrue(OffsetDateTime.parse(body.getString("call_ended_at")).isAfter(from))
        assertTrue("l'envoi se ferme après l'accusé de fin", generateSequence { vm.duplexData.poll(1, TimeUnit.SECONDS) }.any { it.endBody })
        assertEquals(false, audio.captures)
    }

    @Test fun theCallSummaryCountsWithoutContent() = with(Run()) {
        connect()
        nextEvent()
        server(event("response.output_audio_transcript.delta").put("delta", "secret"), delta(tone))
        eventually("audio joué") { audio.played.size == 1 }
        call.hangUp()
        endCall()
        result()
        val summary = logs.single { it.startsWith("summary") }
        assertTrue(summary, "hatch.voice.session x1" in summary && "continuous_audio_video.delta x1" in summary)
        assertTrue(summary, "secret" !in summary)
    }

    @Test fun whatMuseHeardAndIgnoredIsReportedWithoutLoggingIt() = with(Run()) {
        connect()
        server(
            event("conversation.item.input_audio_transcription.delta").put("item_id", "i1").put("delta", "quelle heure"),
            event("hatch.voice.input_suppressed").put("item_id", "i1").put("reason", "side_speech").put("note", "texte privé"),
            event("conversation.item.input_audio_transcription.completed").put("item_id", "i2").put("transcript", "bonjour"),
        )
        eventually("événements") { events.filterIsInstance<CallEvent.Heard>().size == 2 }
        assertEquals(CallEvent.Heard("i1", "quelle heure", false), events.filterIsInstance<CallEvent.Heard>().first())
        assertTrue(CallEvent.Ignored("i1") in events)
        call.hangUp()
        result()
        val line = logs.single { it.startsWith("speech dropped") }
        assertTrue(line, "reason=side_speech" in line && "note" in line)
        assertTrue(logs.none { "privé" in it || "quelle heure" in it || "bonjour" in it })
    }

    @Test fun hangingUpEndsTheCallOnTheServer() = with(Run()) {
        connect()
        nextEvent()
        call.hangUp()
        endCall()
        assertEquals(CallResult.Ended(CallEnd.HUNG_UP), result())
    }

    @Test fun refusalIsReportedWithItsStatus() = with(Run()) {
        admit(status = 403)
        endCall()
        assertEquals(CallResult.Failed(CallFailure.REFUSED, 403), result())
    }

    @Test fun anotherCallsReadinessIsRejected() = with(Run()) {
        admit()
        ready("autre")
        endCall()
        assertEquals(CallFailure.PROTOCOL, (result() as CallResult.Failed).failure)
    }

    @Test fun audioBeforeReadinessIsRejectedButSilenceIsNot() = with(Run()) {
        admit()
        server(delta(ByteArray(1920)))
        assertTrue(!done.isDone)
        server(delta(tone))
        assertEquals(CallFailure.PROTOCOL, (result() as CallResult.Failed).failure)
        assertTrue(audio.played.isEmpty())
    }

    @Test fun readinessHasADeadline() = with(Run(ready = 300)) {
        admit()
        assertEquals(CallFailure.NOT_READY, (result() as CallResult.Failed).failure)
        assertEquals(CallResult.Failed(CallFailure.NOT_READY, null), done.get())
    }

    @Test fun theMicrophoneIsMutedWhileMuseSpeaks() = with(Run()) {
        connect()
        nextEvent()
        // 2 s of speech.
        server(delta(ByteArray(96_000) { tone[it % tone.size] }))
        eventually("parole signalée") { CallEvent.Speaking(true) in events }
        Thread.sleep(150)
        vm.duplexData.clear()
        assertNull("micro coupé pendant la réponse", vm.duplexData.poll(600, TimeUnit.MILLISECONDS))
        val resumed = checkNotNull(vm.duplexData.poll(3, TimeUnit.SECONDS)) { "le micro doit reprendre" }
        assertEquals("input_audio_buffer.append", JSONObject(String(resumed.data)).getString("type"))
        eventually("fin de parole signalée") { events.filterIsInstance<CallEvent.Speaking>().last() == CallEvent.Speaking(false) }
        call.hangUp()
    }

    @Test fun anInputHoldStopsTheMicrophone() = with(Run()) {
        connect()
        nextEvent()
        server(event("hatch.voice.switch_input_hold").put("held", true))
        Thread.sleep(150)
        vm.duplexData.clear()
        assertNull(vm.duplexData.poll(300, TimeUnit.MILLISECONDS))
        server(event("hatch.voice.switch_input_hold").put("held", false))
        nextEvent()
        call.hangUp()
    }

    @Test fun interruptingFlushesCancelsAndIgnoresTheRestOfThatReply() = with(Run()) {
        connect()
        server(event("response.created").put("response", JSONObject().put("id", "r1")), delta(ByteArray(48_000) { tone[it % tone.size] }, "p1"))
        eventually("audio joué") { audio.played.isNotEmpty() }
        val before = audio.flushes
        call.interrupt()
        eventually("lecture vidée") { audio.flushes > before }
        eventually("annulation envoyée") {
            vm.duplexData.any { String(it.data).contains("\"response.cancel\"") && String(it.data).contains("r1") }
        }
        val played = audio.played.size
        server(delta(tone, "p1"))
        Thread.sleep(200)
        assertEquals(played, audio.played.size)
        call.hangUp()
    }

    @Test fun museCanHangUp() = with(Run()) {
        connect()
        server(event("conversation.item.added").put("item", JSONObject().put("type", "session_control").put("call_id", "c").put("name", "stop")))
        endCall()
        assertEquals(CallResult.Ended(CallEnd.REMOTE_STOP), result())
    }

    @Test fun silenceEndsTheCallButPendingWorkDoesNot() = with(Run(idle = 400)) {
        connect()
        server(event("hatch.voice.turn_started").put("item_id", "i").put("submission_message_id", "s"))
        Thread.sleep(900)
        assertTrue("Muse travaille encore", !done.isDone)
        server(event("hatch.voice.turn_settled").put("response_message_id", "s"))
        endCall()
        assertEquals(CallResult.Ended(CallEnd.IDLE), result())
    }

    /** False alarm of the wake word: noise, but no request recognized. */
    @Test fun aWakeCallWithoutAnyRequestEndsQuickly() = with(Run(firstTurn = 500)) {
        connect()
        server(event("input_audio_buffer.speech_started").put("item_id", "i"), event("hatch.voice.input_suppressed").put("item_id", "i"))
        endCall()
        assertEquals(CallResult.Ended(CallEnd.NOTHING_ASKED), result())
    }

    @Test fun aWakeCallWithARequestGoesOn() = with(Run(firstTurn = 500)) {
        connect()
        server(event("hatch.voice.turn_started").put("item_id", "i").put("submission_message_id", "s"))
        Thread.sleep(900)
        assertTrue(!done.isDone)
        call.hangUp()
    }

    @Test fun roomNoiseDoesNotKeepACallAlive() = with(Run(idle = 500, loudRoom = true)) {
        connect()
        // A television: sound, dropped starts of speech, but no exchange.
        server(event("input_audio_buffer.speech_started").put("item_id", "tv"), event("hatch.voice.input_suppressed").put("item_id", "tv"))
        endCall()
        assertEquals(CallResult.Ended(CallEnd.IDLE), result())
    }

    @Test fun aSentenceBeingSpokenIsNotCutOff() = with(Run(idle = 600)) {
        connect()
        Thread.sleep(400)
        server(event("input_audio_buffer.speech_started").put("item_id", "i"))
        Thread.sleep(400)
        assertTrue(!done.isDone)
        call.hangUp()
    }

    @Test fun aTurnNeverSettledStopsHoldingTheCall() = with(Run(idle = 300, stalled = 900)) {
        connect()
        server(event("hatch.voice.turn_started").put("item_id", "i").put("submission_message_id", "s"))
        Thread.sleep(500)
        assertTrue(!done.isDone)
        endCall()
        assertEquals(CallResult.Ended(CallEnd.IDLE), result())
    }

    @Test fun whatWasSaidWhileConnectingIsSentFirst() {
        val said = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        for (i in 1..5) said.put(ByteArray(1920) { i.toByte() })
        with(Run(preRoll = PreRoll({ said.take() }, capacity = 100))) {
            admit()
            Thread.sleep(200)
            assertTrue("rien ne part avant que l'appel soit prêt", vm.duplexData.isEmpty())
            ready()
            val sent = List(5) { java.util.Base64.getDecoder().decode(nextEvent().getString("audio"))[0].toInt() }
            assertEquals(listOf(1, 2, 3, 4, 5), sent)
            call.hangUp()
            result()
            said.put(ByteArray(1920))
        }
    }

    @Test fun callsHaveAMaximumDuration() = with(Run(overall = 500)) {
        connect()
        assertEquals(CallResult.Ended(CallEnd.TOO_LONG), result())
    }

    @Test fun turnErrorsAreIgnoredButADeadSessionFails() = with(Run()) {
        connect()
        server(event("error").put("error", JSONObject().put("code", "server_error")))
        Thread.sleep(200)
        assertTrue(!done.isDone)
        server(event("error").put("error", JSONObject().put("code", "session_dead")))
        assertEquals(CallFailure.CONNECTION, (result() as CallResult.Failed).failure)
    }

    @Test fun aLostConnectionEndsTheCall() = with(Run()) {
        connect()
        nextEvent()
        vm.close()
        assertEquals(CallFailure.CONNECTION, (result() as CallResult.Failed).failure)
    }

    @Test fun theVoiceConnectionNeverRegistersTheDevice() = with(Run()) {
        connect()
        assertTrue("aucun flux de contrôle", vm.requests.isEmpty() && vm.received.isEmpty())
        call.hangUp()
    }

    @Test fun voiceUrlCarriesTheAppIdentity() {
        val record = PairingRecord("a", "r", "u", "", "", "evil.example", 0, "homelink-abcdef")
        val vm = Vm("w", "t", "n", "v 1", true)
        assertEquals("wss://hatch.metaaivm.com/v1/noise?vm_id=v%201&app_id=hatch-web", NoiseUrl.of(record, vm, appId = "hatch-web"))
        assertEquals("wss://hatch.metaaivm.com/v1/noise?vm_id=v%201", NoiseUrl.of(record, vm))
    }
}
