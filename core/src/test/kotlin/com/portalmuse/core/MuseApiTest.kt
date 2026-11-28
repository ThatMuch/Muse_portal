package com.portalmuse.core

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val VMS = """{"vm_list":[
  {"vm_ws_url":"wss://x","vm_auth_token":"jeton-vm","vm_name":"Muse","vm_id":"v1","default":true},
  {"vm_url":"https://y","vm_auth_token":""},
  "bruit"
]}"""

class MuseApiTest {
    private val seen = mutableListOf<Request>()

    /** No request goes out: the interceptor answers in place of the server. */
    private fun api(code: Int = 200, body: String = VMS, failure: IOException? = null): MuseApi {
        val fake = Interceptor { chain ->
            seen.add(chain.request())
            if (failure != null) throw failure
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        return MuseApi(OkHttpClient.Builder().addInterceptor(fake).build(), "portal-muse-gadget/test")
    }

    private fun credentials(apiUrlV2: String = "https://api.muse.ai") = Credentials(
        "acces", "rafraichir", "u", "https://hatch-api.meta.ai", apiUrlV2, "hatch.metaaivm.com",
    )

    @Test fun fetchVmsSendsTheDeviceTokenToTheMuseApi() {
        val lookup = api().fetchVms("acces", "")
        assertEquals(200, lookup.status)
        assertEquals(listOf("v1"), lookup.vms.map { it.id })
        assertTrue(lookup.vms.single().isDefault)
        val request = seen.single()
        assertEquals("https://api.muse.ai/fetch_vms", request.url.toString())
        assertEquals("GET", request.method)
        assertEquals("Bearer acces", request.header("Authorization"))
        assertEquals("1.0.0", request.header("X-API-Version"))
        assertEquals("portal-muse-gadget/test", request.header("User-Agent"))
    }

    @Test fun foreignApiRootFallsBackToTheDefault() {
        api().fetchVms("acces", "https://evil.example")
        assertEquals("api.muse.ai", seen.single().url.host)
    }

    @Test fun failuresGiveNoVmsAndKeepTheStatus() {
        assertEquals(401, api(code = 401).fetchVms("a", "").status)
        assertTrue(api(code = 401).fetchVms("a", "").vms.isEmpty())
        assertTrue(api(body = """{"error_title":"non"}""").fetchVms("a", "").vms.isEmpty())
        assertTrue(api(body = "pas du json").fetchVms("a", "").vms.isEmpty())
        assertTrue(api(body = "{}").fetchVms("a", "").vms.isEmpty())
        assertNull(api(failure = IOException("hors ligne")).fetchVms("a", "").status)
        assertNull(api().fetchVms("jeton\ninvalide", "").status)
    }

    @Test fun probeSendsNoCredentials() {
        assertEquals("joignable (HTTP 404)", api(code = 404).probe())
        assertNull(seen.single().header("Authorization"))
        assertEquals("injoignable (IOException)", api(failure = IOException("x")).probe())
    }

    @Test fun vmNeverPrintsItsToken() {
        assertTrue("jeton-vm" !in api().fetchVms("a", "").vms.single().toString())
    }

    @Test fun verifiedCredentialsAreSavedWithTheNodeId() {
        var saved: PairingRecord? = null
        MuseProvisioning.verifyAndSave(
            api(), credentials(), { it() }, "jeton-sdk", "homelink-abcdef", nowSeconds = 42,
        ) { saved = it; true }
        val record = assertNotNull(saved).let { saved!! }
        assertEquals("homelink-abcdef", record.nodeId)
        assertEquals(42, record.accessTokenSavedAt)
        assertEquals("hatch.metaaivm.com", record.noiseHost)
        val reread = PairingRecord.fromJson(record.toJson())!!
        assertEquals(record.refreshToken, reread.refreshToken)
        assertEquals(record.nodeId, reread.nodeId)
        assertTrue("acces" !in record.toString() && "rafraichir" !in record.toString())
        assertTrue("jeton-sdk" !in record.toJson())
    }

    @Test fun rejectedTokenSavesNothing() {
        var saves = 0
        val error = assertThrows(ProvisionFailed::class.java) {
            MuseProvisioning.verifyAndSave(api(code = 401), credentials(), { it() }, null, "homelink-abcdef", 1) { saves++; true }
        }
        assertEquals("auth_failed", error.status)
        assertEquals(0, saves)
    }

    @Test fun staleSessionOrStorageFailureReportsErrorStorage() {
        for ((commit, save) in listOf<Pair<(() -> Boolean) -> Boolean, Boolean>>({ _: () -> Boolean -> false } to true, { it: () -> Boolean -> it() } to false)) {
            val error = assertThrows(ProvisionFailed::class.java) {
                MuseProvisioning.verifyAndSave(api(), credentials(), commit, null, "homelink-abcdef", 1) { save }
            }
            assertEquals("error_storage", error.status)
        }
    }

    @Test fun damagedRecordsAreIgnored() {
        assertNull(PairingRecord.fromJson("{}"))
        assertNull(PairingRecord.fromJson("pas du json"))
        assertNull(PairingRecord.fromJson("""{"access_token":"a","refresh_token":"r","node_id":"autre"}"""))
    }
}
