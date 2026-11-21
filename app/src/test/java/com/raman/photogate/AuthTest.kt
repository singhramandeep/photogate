package com.raman.photogate

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.Base64
import kotlin.concurrent.thread

class AuthTest {
    private val google = FakeServer()

    @After
    fun tearDown() = google.close()

    @Test
    fun pkceChallengeMatchesRfc7636Example() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Auth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun verifierIsValidPkceLength() {
        val v = Auth.newVerifier()
        assertTrue(v.length in 43..128)
        assertTrue(v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun authUrlCarriesEverythingGoogleNeeds() {
        val url = Auth.authUrl("cid.apps.googleusercontent.com", "http://127.0.0.1:5555", "chal", "st8")
        val q = Auth.parseQuery(url.substringAfter('?'))
        assertEquals("cid.apps.googleusercontent.com", q["client_id"])
        assertEquals("http://127.0.0.1:5555", q["redirect_uri"])
        assertEquals("code", q["response_type"])
        assertTrue(q["scope"]!!.split(' ').contains(Auth.PHOTOS_SCOPE))
        assertEquals("chal", q["code_challenge"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals("st8", q["state"])
        assertEquals("offline", q["access_type"])
    }

    @Test
    fun emailIsReadFromIdToken() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"email":"a@b.c"}""".toByteArray())
        assertEquals("a@b.c", Auth.emailOf("h.$payload.sig"))
        assertNull(Auth.emailOf(null))
        assertNull(Auth.emailOf("garbage"))
    }

    @Test
    fun loopbackIgnoresNoiseAndReturnsRedirectParams() {
        Loopback(timeoutMs = 10_000).use { loop ->
            var got: Map<String, String>? = null
            val t = thread { got = loop.awaitRedirect { "<h1>ok ${it["code"]}</h1>" } }

            // browser preconnect: opens and closes without a request
            Socket("127.0.0.1", loop.port).close()
            // favicon: answered 404, keeps waiting
            val fav = URL("${loop.redirectUri}/favicon.ico").openConnection() as HttpURLConnection
            assertEquals(404, fav.responseCode)
            // the real redirect from Google
            val r = URL("${loop.redirectUri}/?state=xyz&code=4%2F0Abc&scope=a+b").openConnection() as HttpURLConnection
            assertEquals(200, r.responseCode)
            assertTrue(r.inputStream.bufferedReader().readText().contains("ok 4/0Abc"))

            t.join(5_000)
            assertEquals(mapOf("state" to "xyz", "code" to "4/0Abc", "scope" to "a b"), got)
        }
    }

    @Test
    fun exchangeSendsPkceAndParsesTokens() {
        google.on("/token") { 200 to """{"access_token":"at","expires_in":3599,"refresh_token":"rt","id_token":"x.y.z"}""" }
        Auth.tokenEndpoint = "${google.url}/token"

        val t = Auth.exchange("cid", "sec", "the code", "ver", "http://127.0.0.1:1")
        assertEquals(Auth.Tokens("at", 3599, "rt", "x.y.z"), t)

        val form = Auth.parseQuery(String(google.requests.single().body))
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("the code", form["code"])
        assertEquals("ver", form["code_verifier"])
        assertEquals("http://127.0.0.1:1", form["redirect_uri"])
        assertEquals("cid", form["client_id"])
        assertEquals("sec", form["client_secret"])
        assertEquals("application/x-www-form-urlencoded", google.requests.single().headers["content-type"])
    }

    @Test
    fun refreshWithoutSecretOmitsIt() {
        google.on("/token") { 200 to """{"access_token":"at2","expires_in":3600}""" }
        Auth.tokenEndpoint = "${google.url}/token"
        val t = Auth.refresh("cid", "", "rt")
        assertEquals("at2", t.access)
        assertNull(t.refresh)
        val form = Auth.parseQuery(String(google.requests.single().body))
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("rt", form["refresh_token"])
        assertTrue("client_secret" !in form)
    }

    @Test
    fun revokedGrantIsReportedAsSuch() {
        google.on("/token") { 400 to """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""" }
        Auth.tokenEndpoint = "${google.url}/token"
        try {
            Auth.refresh("cid", "sec", "rt")
            fail("expected GrantRevoked")
        } catch (e: Auth.GrantRevoked) {
            assertTrue(e.message!!.contains("invalid_grant"))
        }
    }

    @Test
    fun serverErrorIsPlainIoExceptionSoWorkRetries() {
        google.on("/token") { 503 to "busy" }
        Auth.tokenEndpoint = "${google.url}/token"
        try {
            Auth.refresh("cid", "sec", "rt")
            fail("expected IOException")
        } catch (e: Auth.GrantRevoked) {
            fail("503 must not sign the user out")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("503"))
        }
    }
}
