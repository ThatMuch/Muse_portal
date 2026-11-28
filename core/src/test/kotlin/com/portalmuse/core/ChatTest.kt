package com.portalmuse.core

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ChatTest {
    private val executor = Executors.newCachedThreadPool()
    private val device = DeviceDescription("homelink-abcdef", "Portal Muse", "0.5.0", "portalmuse-portal-10", "Portal 10 (DIY)")
    private val commands = DeviceCommands({ JSONObject() }, "# x")
    private var session: LinkSession? = null

    @After fun stop() {
        session?.stop()
        executor.shutdownNow()
    }

    private fun event(name: String, vararg fields: Pair<String, Any>) = JSONObject()
        .put("type", "event").put("event", name)
        .put("payload", JSONObject().apply { fields.forEach { put(it.first, it.second) } })

    private fun FakeVm.push(stream: Long, vararg events: JSONObject) {
        sendFrame(ServiceFrame.BodyChunk(stream, events.joinToString("") { "$it\n" }.toByteArray()))
    }

    private fun answer() = arrayOf(
        event("message.user", "message_id" to "u1"),
        event("agent.status", "activity_code" to "thinking", "activity_text" to "Nova réfléchit"),
        event("delta.message_start", "message_id" to "m1"),
        event("delta.text_append", "message_id" to "m1", "text" to "Bon"),
        event("delta.text_append", "message_id" to "m1", "text" to "jour"),
        event("delta.message_done", "message_id" to "m1", "display_text" to "Bonjour !"),
        event("task.status", "status" to "completed", "reply_to_message_id" to "u1"),
    )

    /** VM that accepts the subscription, acknowledges, then plays [events] on the subscription. */
    private fun vm(
        subscribeStatus: Int = 200, chatStatus: Int = 200,
        events: Array<JSONObject> = answer(), eventsBeforeAck: Boolean = false,
        after: FakeVm.(Long) -> Unit = {},
    ): FakeVm {
        var subscription = 0L
        return FakeVm(
            onRequest = { call ->
                when (call.path) {
                    "/chat/subscribe" -> {
                        subscription = call.streamId
                        sendFrame(ServiceFrame.Response(call.streamId, subscribeStatus))
                    }
                    "/chat/stream" -> {
                        if (eventsBeforeAck) push(subscription, *events)
                        val ack = JSONObject().put("result", JSONObject().put("message_id", "u1").put("session_id", "s1"))
                        sendFrame(ServiceFrame.Response(call.streamId, chatStatus, ack.toString().toByteArray(), endBody = true))
                        if (!eventsBeforeAck && chatStatus == 200) push(subscription, *events)
                        after(subscription)
                    }
                    "/api/gadget/me" -> sendFrame(
                        ServiceFrame.Response(call.streamId, 200, """{"result":{"muse_name":" Nova "}}""".toByteArray(), endBody = true),
                    )
                }
            },
            onMessage = { send(JSONObject().put("id", it.getString("id")).put("ok", true)) },
        )
    }

    private fun connect(
        vm: FakeVm, stalled: Long = 20_000, requestTimeout: Long = 60_000, sendTimeout: Long = 200_000,
    ): MuseChat {
        val registered = CountDownLatch(1)
        val created = LinkSession(vm, device, commands, executor, onRegistered = { registered.countDown() })
        session = created
        executor.execute { created.run() }
        assertTrue(registered.await(5, TimeUnit.SECONDS))
        return MuseChat(
            created, "homelink-abcdef", replyTimeoutMillis = 3_000, stalledReplyMillis = stalled,
            requestTimeoutMillis = requestTimeout, sendTimeoutMillis = sendTimeout,
        )
    }

    @Test fun textGoesToTheMainChatAndTheReplyStreamsBack() {
        val vm = vm()
        val progress = mutableListOf<ChatProgress>()
        val result = connect(vm).sendText("Salut", progress::add)
        assertEquals("Bonjour !", (result as ChatResult.Answer).text)
        assertEquals(false, result.partial)
        val subscribe = vm.calls.take()
        assertEquals("/chat/subscribe", subscribe.path)
        assertEquals("l'abonnement précède l'envoi", "{}", String(subscribe.body))
        val chat = vm.calls.take()
        assertEquals(ServiceType.DAEMON, chat.service)
        assertEquals("POST", chat.verb)
        val body = JSONObject(String(chat.body))
        assertEquals("Salut", body.getString("message"))
        assertEquals("homelink-abcdef", body.getString("device_id"))
        assertEquals("text", body.getString("output_modality"))
        assertTrue("sans session_id : la conversation principale", !body.has("session_id"))
        assertTrue(progress.any { it.text == "Bonjour !" })
        assertEquals(subscribe.streamId, vm.resets.poll(5, TimeUnit.SECONDS))
    }

    @Test fun aBusyMuseMayAcknowledgeAMessageLate() {
        var subscription = 0L
        val vm = FakeVm(
            onRequest = { call ->
                when (call.path) {
                    "/chat/subscribe" -> {
                        subscription = call.streamId
                        sendFrame(ServiceFrame.Response(call.streamId, 200))
                    }
                    "/chat/stream" -> executor.execute {
                        Thread.sleep(900)
                        val ack = JSONObject().put("result", JSONObject().put("message_id", "u1"))
                        sendFrame(ServiceFrame.Response(call.streamId, 200, ack.toString().toByteArray(), endBody = true))
                        push(subscription, *answer())
                    }
                }
            },
            onMessage = { send(JSONObject().put("id", it.getString("id")).put("ok", true)) },
        )
        val result = connect(vm, requestTimeout = 300, sendTimeout = 5_000).sendText("Salut")
        assertEquals("Bonjour !", (result as ChatResult.Answer).text)
    }

    @Test fun aSpokenRequestAsksForAVoiceReplyAndNamesItsMessages() {
        val vm = vm()
        val result = connect(vm).sendText("Salut", spoken = true) as ChatResult.Answer
        assertEquals(listOf("m1"), result.messageIds)
        vm.calls.take()
        assertEquals("voice", JSONObject(String(vm.calls.take().body)).getString("output_modality"))
    }

    @Test fun aWrittenReplyIsNotHeldByATaskThatStaysBusy() {
        // Seen on a real account for a spoken reply: the message is closed, Muse stays "busy".
        val events = arrayOf(
            event("message.user", "message_id" to "u1"),
            event("agent.status", "activity_code" to "thinking"),
            event("delta.text_append", "message_id" to "m1", "text" to "Voici"),
            event("delta.message_done", "message_id" to "m1"),
        )
        val progress = mutableListOf<ChatProgress>()
        val result = connect(vm(events = events), stalled = 600).sendText("?", progress::add, spoken = true)
        assertEquals(false, (result as ChatResult.Answer).partial)
        assertEquals("Voici", result.text)
        assertTrue("le message clos est annoncé aussitôt", progress.any { it.finished == listOf("m1") })
    }

    @Test fun aNewSideChatIsFollowedOnceTheMessageHasCreatedIt() {
        var subscriptions = 0
        val vm = FakeVm(
            onRequest = { call ->
                when (call.path) {
                    "/chat/subscribe" -> {
                        subscriptions++
                        sendFrame(ServiceFrame.Response(call.streamId, if (subscriptions == 1) 404 else 200))
                        if (subscriptions == 2) {
                            push(call.streamId, event("message.assistant", "message_id" to "m1", "display_text" to "Salut"))
                        }
                    }
                    "/chat/stream" -> sendFrame(
                        ServiceFrame.Response(
                            call.streamId, 200, """{"result":{"message_id":"u1","session_id":"side-1"}}""".toByteArray(), endBody = true,
                        ),
                    )
                }
            },
            onMessage = { send(JSONObject().put("id", it.getString("id")).put("ok", true)) },
        )
        val registered = CountDownLatch(1)
        val link = LinkSession(vm, device, commands, executor, onRegistered = { registered.countDown() })
        session = link
        executor.execute { link.run() }
        assertTrue(registered.await(5, TimeUnit.SECONDS))
        val chat = MuseChat(link, "homelink-abcdef", replyTimeoutMillis = 3_000, stalledReplyMillis = 600, sideChat = "side-1")
        assertEquals("Salut", (chat.sendText("?") as ChatResult.Answer).text)
        val first = vm.calls.take()
        assertEquals("side-1", JSONObject(String(first.body)).getString("session_id"))
        val message = vm.calls.take()
        assertEquals("side-1", JSONObject(String(message.body)).getString("session_id"))
        assertEquals("/chat/subscribe", vm.calls.take().path)
    }

    @Test fun aFileIsUploadedAsTheSdkDescribes() {
        val vm = historyVm(200, """{"ok":true}""")
        assertEquals(200, connect(vm).upload("portal-photos/a.jpg", byteArrayOf(1, 2, 3), "image/jpeg"))
        val call = vm.calls.take()
        assertEquals("/fs/upload", call.path)
        val type = call.headers.entries.first { it.key.equals("content-type", true) }.value
        val boundary = type.substringAfter("boundary=")
        val body = String(call.body, Charsets.ISO_8859_1)
        assertTrue(body.startsWith("--$boundary\r\n"))
        assertTrue("name=\"path\"\r\n\r\nportal-photos/a.jpg\r\n" in body)
        assertTrue("name=\"create_parent\"" in body)
        assertTrue("filename=\"a.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n\u0001\u0002\u0003\r\n--$boundary--" in body)
        for (bad in listOf("../a", "/etc/a", "a//b", "é.jpg")) {
            assertTrue(bad, runCatching { connect(historyVm(200, "")).upload(bad, byteArrayOf(1), "x") }.isFailure)
        }
    }

    private fun historyVm(status: Int, body: String) = FakeVm(
        onRequest = { call -> sendFrame(ServiceFrame.Response(call.streamId, status, body.toByteArray(), endBody = true)) },
        onMessage = { send(JSONObject().put("id", it.getString("id")).put("ok", true)) },
    )

    @Test fun theHistoryKeepsOnlyReadableMessagesInOrder() {
        val body = """{"ok":true,"result":{"chat_events":[
            {"role":"assistant","event_name":"message.assistant","display_text":"Deux","seq":2},
            {"role":"user","event_name":"message.user","display_text":"Un\u0007","seq":1},
            {"role":"tool","display_text":"interne","seq":3},
            {"role":"assistant","display_text":"  ","seq":4}],"has_more":true}}"""
        val vm = historyVm(200, body)
        val result = connect(vm).history(5) as History.Messages
        assertEquals(listOf(PastMessage(true, "Un"), PastMessage(false, "Deux")), result.messages)
        val call = vm.calls.take()
        assertEquals("GET", call.verb)
        assertEquals("/chat/history?limit=5", call.path)
        assertEquals(ServiceType.DAEMON, call.service)
    }

    @Test fun aRefusedOrUnreadableHistorySaysSo() {
        assertEquals(403, (connect(historyVm(403, "non")).history() as History.Unavailable).status)
        session?.stop()
        assertEquals(null, (connect(historyVm(200, "pas du json")).history() as History.Unavailable).status)
    }

    @Test fun aPhotoIsSentWithItsTextFirst() {
        val vm = vm()
        assertEquals("Bonjour !", (connect(vm).sendImage(byteArrayOf(1, 2, 3), "Regarde ça.") as ChatResult.Answer).text)
        vm.calls.take()
        val items = JSONObject(String(vm.calls.take().body)).getJSONArray("items")
        assertEquals("Regarde ça.", items.getJSONObject(0).getString("text"))
        assertEquals("image/jpeg", items.getJSONObject(1).getString("mime_type"))
        assertEquals("AQID", items.getJSONObject(1).getString("data_base64"))
    }

    private fun speechVm(vararg statuses: Int): FakeVm {
        val left = statuses.toMutableList()
        return FakeVm(
            onRequest = { call ->
                val status = left.removeAt(0)
                sendFrame(ServiceFrame.Response(call.streamId, status, "MP3-".toByteArray()))
                sendFrame(ServiceFrame.BodyChunk(call.streamId, "suite".toByteArray(), endBody = true))
            },
            onMessage = { send(JSONObject().put("id", it.getString("id")).put("ok", true)) },
        )
    }

    @Test fun theReplyVoiceArrivesInPiecesWithTheOlderPathAsFallback() {
        val vm = speechVm(404, 200)
        val chat = connect(vm)
        val audio = java.io.ByteArrayOutputStream()
        assertEquals(200, chat.speech("reply 1&x", audio::write))
        assertEquals("MP3-suite", audio.toString())
        val first = vm.calls.take()
        assertEquals("GET", first.verb)
        assertEquals(ServiceType.DAEMON, first.service)
        assertEquals("/api/voice/tts-stream?message_id=reply%201%26x", first.path)
        assertEquals("audio/mpeg", first.headers.entries.first { it.key.equals("accept", true) }.value)
        assertEquals("/voice/tts-stream?message_id=reply%201%26x", vm.calls.take().path)
    }

    @Test fun aRefusedVoiceGivesItsStatusAndNoSound() {
        val audio = java.io.ByteArrayOutputStream()
        assertEquals(403, connect(speechVm(403)).speech("m", audio::write))
        assertEquals(0, audio.size())
    }

    @Test fun eventsArrivingBeforeTheAcknowledgementAreKept() {
        val result = connect(vm(eventsBeforeAck = true)).sendText("Salut")
        assertEquals("Bonjour !", (result as ChatResult.Answer).text)
    }

    @Test fun otherPeoplesTurnsAreNotShown() {
        val events = arrayOf(
            event("message.user", "message_id" to "autre"),
            event("message.assistant", "message_id" to "x", "display_text" to "pas pour nous"),
            *answer(),
            event("message.user", "message_id" to "encore"),
            event("message.assistant", "message_id" to "y", "display_text" to "ni ceci"),
            event("message.assistant", "message_id" to "z", "display_text" to "ailleurs", "reply_to_message_id" to "autre"),
        )
        assertEquals("Bonjour !", (connect(vm(events = events)).sendText("Salut") as ChatResult.Answer).text)
    }

    @Test fun repeatedAndForeignSessionEventsAreDropped() {
        val events = arrayOf(
            event("message.user", "message_id" to "u1"),
            event("delta.text_append", "message_id" to "m1", "text" to "A").put("seq", 5),
            event("delta.text_append", "message_id" to "m1", "text" to "A").put("seq", 5),
            event("delta.text_append", "message_id" to "m9", "text" to "Z", "session_id" to "autre-chat"),
            event("delta.text_append", "message_id" to "m1", "text" to "B").put("seq", 6),
            event("delta.message_done", "message_id" to "m1"),
        )
        assertEquals("AB", (connect(vm(events = events)).sendText("Salut") as ChatResult.Answer).text)
    }

    @Test fun finalTranscriptTextIsRead() {
        val transcript = JSONObject("""{"messages":[{"id":"m1","role":"assistant","content":[{"type":"text","text":"Depuis\u0007 le transcript"}]}]}""")
        val events = arrayOf(
            event("message.user", "message_id" to "u1"),
            event("message.assistant", "message_id" to "m1", "transcript" to transcript),
        )
        assertEquals("Depuis le transcript", (connect(vm(events = events)).sendText("?") as ChatResult.Answer).text)
    }

    @Test fun nothingIsSentWhenRepliesCannotBeFollowed() {
        val vm = vm(subscribeStatus = 403)
        val result = connect(vm).sendText("Salut") as ChatResult.Failed
        assertEquals(ChatFailure.NOT_SENT, result.failure)
        assertEquals(listOf("/chat/subscribe"), vm.calls.map { it.path })
    }

    @Test fun aRefusedMessageIsReported() {
        val result = connect(vm(chatStatus = 429)).sendText("Salut") as ChatResult.Failed
        assertEquals(ChatFailure.REFUSED, result.failure)
        assertEquals(429, result.status)
    }

    @Test fun silenceGivesNoReplyButTheMessageWasSent() {
        val result = connect(vm(events = arrayOf())).sendText("Salut") as ChatResult.Failed
        assertEquals(ChatFailure.NO_REPLY, result.failure)
    }

    @Test fun anUnfinishedReplyIsReturnedAsPartial() {
        val events = arrayOf(
            event("message.user", "message_id" to "u1"),
            event("delta.text_append", "message_id" to "m1", "text" to "Début"),
        )
        val result = connect(vm(events = events)).sendText("Salut") as ChatResult.Answer
        assertEquals("Début", result.text)
        assertTrue(result.partial)
    }

    @Test fun aClosedSessionEndsTheWait() {
        val vm = vm(events = arrayOf(), after = { close() })
        val future = CompletableFuture.supplyAsync({ connect(vm).sendText("Salut") }, executor)
        assertEquals(ChatFailure.NO_REPLY, (future.get(2, TimeUnit.SECONDS) as ChatResult.Failed).failure)
    }

    @Test fun voiceNotesAreUploadedInPartsAsWav() {
        val vm = vm()
        val wav = Wav.encode(ByteArray(160_000) { (it % 100).toByte() }, 16_000)
        val result = connect(vm).sendVoice(wav)
        assertTrue(result is ChatResult.Answer)
        vm.calls.take()
        val chat = vm.calls.take()
        assertTrue("envoi en plusieurs morceaux", chat.parts > 10)
        val item = JSONObject(String(chat.body)).getJSONArray("items").getJSONObject(0)
        assertEquals("file", item.getString("type"))
        assertEquals("audio/wav", item.getString("mime_type"))
        assertEquals("voice_note.wav", item.getString("filename"))
        assertArrayEquals(wav, java.util.Base64.getDecoder().decode(item.getString("data_base64")))
    }

    @Test fun wavHeaderDescribesMono16Bit() {
        val wav = Wav.encode(ByteArray(32_000), 16_000)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(wav.size - 8, header.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(32_000, header.getInt(40))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { connect(vm()).sendVoice(ByteArray(Wav.MAX_BYTES + 1)) }
    }

    @Test fun theMuseNameComesFromTheGadgetApi() {
        val vm = vm()
        assertEquals("Nova", connect(vm).museName())
        val call = vm.calls.take()
        assertEquals(ServiceType.GADGET, call.service)
        assertEquals("GET", call.verb)
    }
}
