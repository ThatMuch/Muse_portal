package com.portalmuse.core

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AvatarTest {
    private val executor = Executors.newCachedThreadPool()
    private var session: LinkSession? = null

    @After fun stop() {
        session?.stop()
        executor.shutdownNow()
    }

    private val identity = """{"ok":true,"result":{"muse_name":"x","avatar":{"image_exists":true,
        "image_path":"workspace/a b.webp","assets_revision":"r7","other":1,
        "video_variants":{"idle":"workspace/idle.mp4","working":"workspace/work.mp4","waking":null,"odd":42,"Bad Name":"x"}}}}"""

    @Test fun identityGivesTheImageAndTheVideos() {
        val avatar = (AvatarParser.parse(identity) as AvatarIdentity.Present).avatar
        assertEquals("workspace/a b.webp", avatar.imagePath)
        assertEquals("r7", avatar.revision)
        assertEquals(mapOf("idle" to "workspace/idle.mp4", "working" to "workspace/work.mp4"), avatar.videos)
        assertTrue("workspace" !in avatar.toString())
    }

    @Test fun aDarkScreenGetsTheDarkSetOnlyWhenItIsComplete() {
        fun parse(extra: String) = (AvatarParser.parse(
            """{"ok":true,"result":{"avatar":{"image_exists":true,"image_path":"l.webp",
               "video_variants":{"idle":"l.mp4","working":"lw.mp4"}$extra}}}""",
        ) as AvatarIdentity.Present).avatar.forDarkScreen()
        val dark = parse(""","darkmode_image_path":"d.webp","darkmode_video_variants":{"idle":"d.mp4"}""")
        assertEquals("d.webp", dark.imagePath)
        assertEquals(mapOf("idle" to "d.mp4"), dark.videos)
        assertEquals("l.webp", parse("").imagePath)
        assertEquals("l.webp", parse(""","darkmode_image_path":"d.webp"""").imagePath)
        assertEquals("l.webp", parse(""","darkmode_image_path":null,"darkmode_video_variants":{"idle":"d.mp4"}""").imagePath)
    }

    @Test fun missingOrInvalidAvatarsAreTold() {
        assertEquals(AvatarIdentity.None, AvatarParser.parse("""{"ok":true,"result":{"avatar":{"image_exists":false}}}"""))
        for (bad in listOf("{}", "x", """{"ok":false}""", """{"ok":true,"result":{"avatar":{"image_exists":true}}}""",
            """{"ok":true,"result":{"avatar":{"image_exists":true,"image_path":"${"a".repeat(600)}"}}}""")) {
            assertEquals(bad.take(60), AvatarIdentity.Unknown, AvatarParser.parse(bad))
        }
    }

    /** La description sert au diagnostic : des noms de champs, aucune valeur. */
    @Test fun theShapeNamesFieldsOnly() {
        val shape = AvatarParser.shape(identity)
        assertTrue(shape, "image_path" in shape && "idle" in shape && "working" in shape)
        assertTrue(shape, "workspace" !in shape && "r7" !in shape && "Bad Name" !in shape)
    }

    @Test fun filePathsAreEncodedAsPaths() {
        assertEquals("/fs/raw/avatars/a%20b%3Fx%23y.webp", AvatarParser.filePath("/avatars/a b?x#y.webp"))
        assertEquals("/fs/raw/%C3%A9/f.mp4", AvatarParser.filePath("é/f.mp4"))
        for (bad in listOf("a/../secret", "..", "", "/", "a\nb")) assertNull(bad, AvatarParser.filePath(bad))
    }

    private fun mp4(size: Int) = ByteArray(size) { (it % 249).toByte() }

    /** VM qui sert [file] par plages ; [cap] limite la taille d'une réponse, [ranges] les ignore. */
    private fun fileVm(file: ByteArray, cap: Int = Int.MAX_VALUE, ranges: Boolean = true, contentRange: Boolean = true) = FakeVm(
        onRequest = { call ->
            if (call.path == "/identity") {
                sendFrame(ServiceFrame.Response(call.streamId, 200, identity.toByteArray(), endBody = true))
            } else if (!ranges) {
                sendFrame(ServiceFrame.Response(call.streamId, 200, file, endBody = true))
            } else {
                val (from, to) = Regex("bytes=(\\d+)-(\\d+)").find(call.headers.getValue("Range"))!!.destructured
                val start = from.toInt()
                if (start >= file.size) {
                    sendFrame(ServiceFrame.Response(call.streamId, 416, endBody = true))
                } else {
                    val end = minOf(to.toInt() + 1, file.size, start + minOf(cap, file.size))
                    val headers = if (contentRange) listOf("content-range" to "bytes $start-${end - 1}/${file.size}") else emptyList()
                    sendFrame(ServiceFrame.Response(call.streamId, 206, file.copyOfRange(start, end), true, headers))
                }
            }
        },
        onMessage = { send(JSONObject().put("id", it.getString("id")).put("ok", true)) },
    )

    private fun connect(vm: FakeVm): AvatarClient {
        val registered = CountDownLatch(1)
        val link = LinkSession(
            vm, DeviceDescription("homelink-abcdef", "p", "1", "m", "M"), DeviceCommands({ JSONObject() }, "x"),
            executor, onRegistered = { registered.countDown() },
        )
        session = link
        executor.execute { link.run() }
        assertTrue(registered.await(5, TimeUnit.SECONDS))
        return AvatarClient(link, chunkBytes = 10_000, timeoutMillis = 2_000)
    }

    private fun download(client: AvatarClient, max: Int = 1_000_000): ByteArray? {
        val out = ByteArrayOutputStream()
        return if (client.download("workspace/idle.mp4", max, out)) out.toByteArray() else null
    }

    @Test fun identityComesFromTheDaemon() {
        val vm = fileVm(mp4(10))
        val result = connect(vm).identity()
        assertTrue(result?.first is AvatarIdentity.Present)
        val call = vm.calls.take()
        assertEquals(ServiceType.DAEMON, call.service)
        assertEquals("GET", call.verb)
    }

    @Test fun filesAreReadInRanges() {
        val file = mp4(35_000)
        val vm = fileVm(file)
        assertArrayEquals(file, download(connect(vm)))
        val ranges = vm.calls.map { it.headers.getValue("Range") }
        assertEquals(listOf("bytes=0-9999", "bytes=10000-19999", "bytes=20000-29999", "bytes=30000-34999"), ranges)
        assertEquals("/fs/raw/workspace/idle.mp4", vm.calls.first().path)
    }

    @Test fun aServerCappingResponsesStillGivesTheWholeFile() {
        val file = mp4(25_000)
        assertArrayEquals(file, download(connect(fileVm(file, cap = 4_096))))
    }

    @Test fun withoutContentRangeTheEndIsFoundByAsking() {
        val file = mp4(20_000)
        assertArrayEquals(file, download(connect(fileVm(file, contentRange = false))))
    }

    @Test fun aServerIgnoringRangesGivesTheWholeFile() {
        val file = mp4(50_000)
        assertArrayEquals(file, download(connect(fileVm(file, ranges = false))))
    }

    @Test fun oversizedFilesAreRefused() {
        assertNull(download(connect(fileVm(mp4(30_000))), max = 20_000))
    }

    @Test fun aLostSessionFailsTheDownload() {
        val vm = FakeVm(onRequest = { close() }, onMessage = { send(JSONObject().put("id", it.getString("id")).put("ok", true)) })
        assertNull(download(connect(vm)))
    }
}
