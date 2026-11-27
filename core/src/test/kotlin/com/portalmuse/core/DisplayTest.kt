package com.portalmuse.core

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class DisplayTest {
    private class FakeHost(var available: Boolean = true, val broken: Set<String> = emptySet()) : ScreenHost {
        val shown = mutableListOf<Any>()
        var closes = 0
        override fun isAvailable() = available
        override fun load(source: PictureSource): Any? {
            val name = when (source) {
                is PictureSource.Web -> source.url
                is PictureSource.File -> source.path
            }
            if ("boom" in name) throw IllegalStateException("panne")
            return name.takeIf { it !in broken }
        }
        override fun showPictures(pictures: List<LoadedPicture>, title: String?, durationMillis: Long): Boolean {
            shown.add(Triple(pictures.map { it.image to it.caption }, title, durationMillis))
            return true
        }
        override fun showPage(html: String, title: String?, durationMillis: Long): Boolean {
            shown.add(Triple(html, title, durationMillis))
            return true
        }
        override fun close() { closes++ }
    }

    private fun item(url: String? = null, path: String? = null, caption: String? = null) = JSONObject()
        .apply { url?.let { put("url", it) }; path?.let { put("path", it) }; caption?.let { put("caption", it) } }

    private fun images(vararg items: Any) = JSONObject().put("images", JSONArray(items.toList()))

    @Test fun threeCommandsAreDeclaredWithTheirParameters() {
        val specs = DisplayCommands(FakeHost()).specs()
        assertEquals(setOf("display.show_images", "display.show_page", "display.close"), specs.keys)
        for (spec in specs.values) {
            assertTrue(spec.getString("description").length > 20 && spec.has("required") && spec.has("optional"))
        }
        assertTrue(specs.getValue("display.show_images").getJSONObject("required").has("images"))
    }

    @Test fun picturesAreShownInOrderWithCaptions() {
        val host = FakeHost()
        val params = images(item(url = "https://a.example/mer.jpg", caption = "La mer"), item(path = "workspace/b.png"))
            .put("title", "Océan").put("duration_ms", 60_000)
        val result = DisplayCommands(host).run("display.show_images", params)!!
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(2, result.getJSONObject("payload").getInt("shown"))
        assertEquals(
            Triple(listOf("https://a.example/mer.jpg" to "La mer", "workspace/b.png" to null), "Océan", 60_000L),
            host.shown.single(),
        )
    }

    @Test fun failedPicturesAreReportedAndTheOthersShown() {
        val host = FakeHost(broken = setOf("https://a.example/404.jpg"))
        val result = DisplayCommands(host).run(
            "display.show_images",
            images(item(url = "https://a.example/404.jpg"), item(url = "https://a.example/boom.jpg"), item(path = "ok.jpg")),
        )!!
        val payload = result.getJSONObject("payload")
        assertEquals(1, payload.getInt("shown"))
        assertEquals("[1,2]", payload.getJSONArray("failed_items").toString())
        val nothing = DisplayCommands(host).run("display.show_images", images(item(url = "https://a.example/404.jpg")))!!
        assertFalse(nothing.getBoolean("ok"))
        assertEquals(1, host.shown.size)
    }

    @Test fun badRequestsAreRefused() {
        val host = FakeHost()
        val commands = DisplayCommands(host)
        for (bad in listOf(
            JSONObject(), images(), images(*Array(9) { item(path = "a$it") }), images("texte"), images(item()),
            images(item(url = "https://a.example/a.jpg", path = "b")),
        )) {
            assertFalse(bad.toString(), commands.run("display.show_images", bad)!!.getBoolean("ok"))
        }
        for (bad in listOf(JSONObject(), JSONObject().put("html", ""), JSONObject().put("html", 4), JSONObject().put("html", "x".repeat(200_001)))) {
            assertFalse(commands.run("display.show_page", bad)!!.getBoolean("ok"))
        }
        assertTrue(host.shown.isEmpty())
        assertNull(commands.run("system.run", JSONObject()))
    }

    @Test fun durationsAreBounded() {
        val host = FakeHost()
        for ((asked, expected) in listOf(null to 120_000L, 0 to 1_800_000L, 10 to 5_000L, 99_999_999 to 1_800_000L)) {
            val params = JSONObject().put("html", "<p>x</p>").apply { asked?.let { put("duration_ms", it) } }
            DisplayCommands(host).run("display.show_page", params)
            @Suppress("UNCHECKED_CAST")
            assertEquals(expected, (host.shown.last() as Triple<String, String?, Long>).third)
        }
    }

    @Test fun aHiddenScreenIsToldToMuse() {
        val result = DisplayCommands(FakeHost(available = false)).run("display.show_page", JSONObject().put("html", "x"))!!
        assertFalse(result.getBoolean("ok"))
        assertTrue("screen" in result.getString("error"))
    }

    @Test fun pagesAreSealedByAContentPolicy() {
        val page = SafePage.wrap("<h1>Titre</h1><script>alert(1)</script>")
        assertTrue(page.indexOf("Content-Security-Policy") in 0 until page.indexOf("<h1>"))
        assertTrue("default-src 'none'" in page && "script-src" !in page)
    }

    @Test fun deviceCommandsCarryTheScreenOnesWhenAllowed() {
        val without = DeviceCommands({ JSONObject() }, "x")
        assertEquals(2, without.specs().length())
        assertFalse(without.run("display.close", JSONObject()).getBoolean("ok"))
        val host = FakeHost()
        val with = DeviceCommands({ JSONObject() }, "x", DisplayCommands(host))
        assertEquals(5, with.specs().length())
        assertTrue(with.run("display.close", JSONObject()).getBoolean("ok"))
        assertEquals(1, host.closes)
    }

    private fun address(literal: String) = InetAddress.getByName(literal)

    @Test fun onlyPublicAddressesAreReachable() {
        for (private in listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.1.1", "100.64.0.1", "0.0.0.0", "198.18.0.1",
            "224.0.0.1", "255.255.255.255", "::1", "::", "fe80::1", "fd00::1", "fc00::1", "::ffff:192.168.1.1",
            "64:ff9b::c0a8:101", "2002:c0a8:101::1", "2001:db8::1",
        )) assertFalse(private, WebImages.isPublic(address(private)))
        for (public in listOf("93.184.216.34", "1.1.1.1", "172.32.0.1", "2606:4700:4700::1111", "2a00:1450:4007::200e")) {
            assertTrue(public, WebImages.isPublic(address(public)))
        }
    }

    @Test fun onlyPlainHttpsNamesAreAccepted() {
        assertTrue(WebImages.allowed("https://upload.wikimedia.org/a.jpg") != null)
        for (bad in listOf(
            "http://a.example/a.jpg", "file:///etc/hosts", "https://192.168.1.1/a.jpg", "https://[::1]/a.jpg",
            "https://2130706433/a.jpg", "https://u:p@a.example/a.jpg", "https://a.example:8443/a.jpg", "texte", "",
        )) assertNull(bad, WebImages.allowed(bad))
    }

    private fun web(answers: Map<String, Pair<Int, String>>, seen: MutableList<String>) = WebImages(
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val url = chain.request().url.toString()
            seen.add(url)
            val (code, value) = answers.getValue(url)
            val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
            if (code in 300..399) builder.header("Location", value).body("".toResponseBody())
            else builder.body(value.toResponseBody())
            builder.build()
        }).build(),
        "portal-test", maxBytes = 100,
    )

    @Test fun redirectsAreCheckedOneByOne() {
        val seen = mutableListOf<String>()
        val images = web(
            mapOf(
                "https://a.example/1" to (302 to "https://b.example/2"),
                "https://b.example/2" to (200 to "image"),
                "https://a.example/local" to (302 to "https://192.168.1.1/admin"),
                "https://a.example/clear" to (301 to "http://b.example/2"),
                "https://a.example/loop" to (302 to "/loop"),
                "https://a.example/big" to (200 to "x".repeat(101)),
                "https://a.example/missing" to (404 to ""),
            ),
            seen,
        )
        assertArrayEquals("image".toByteArray(), images.fetch("https://a.example/1"))
        for (path in listOf("local", "clear", "big", "missing")) assertNull(path, images.fetch("https://a.example/$path"))
        assertTrue(seen.none { "192.168" in it || it.startsWith("http://") })
        seen.clear()
        assertNull(images.fetch("https://a.example/loop"))
        assertEquals(6, seen.size)
    }
}
