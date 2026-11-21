package com.raman.photogate

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Google sign-in through the browser (OAuth 2.0 + PKCE, "Desktop app" client, loopback redirect).
 *
 * Why not Play Services or a WebView: Play Services sign-in needs a matching Android OAuth client and Google Play
 * on the phone, and Google refuses sign-in inside an embedded WebView ("disallowed_useragent"). A Custom Tab is
 * the real browser shown inside the app, so it works everywhere and the user sees the normal Google login page.
 *
 * Flow: the app listens on http://127.0.0.1:<random port>, opens Google's login page, Google redirects the browser
 * back to that port with a one-time code, the app swaps the code for a refresh token and keeps it.
 */
object Auth {
    // Add-only: the app can upload to the library but cannot read or delete anything in it.
    const val PHOTOS_SCOPE = "https://www.googleapis.com/auth/photoslibrary.appendonly"
    private const val SCOPES = "$PHOTOS_SCOPE openid email"

    // overridable for tests
    var authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth"
    var tokenEndpoint = "https://oauth2.googleapis.com/token"

    /** The token endpoint said the grant is gone (revoked, expired, wrong client). The user must sign in again. */
    class GrantRevoked(msg: String) : IOException(msg)

    data class Tokens(val access: String, val expiresInSec: Long, val refresh: String?, val idToken: String?)

    // ------------------------------------------------------------------ sign-in

    @Volatile
    private var running: Loopback? = null

    /** Opens Google's sign-in page. The outcome lands in Store (connected / authError). Call on the main thread. */
    fun signIn(c: Context) {
        val app = c.applicationContext
        val clientId = Store.clientId(app)
        if (clientId.isBlank()) {
            Store.setAuthError(app, "Enter your OAuth client ID first.")
            return
        }
        running?.close()
        val loop = try {
            Loopback()
        } catch (e: IOException) {
            Store.setAuthError(app, "Could not start sign-in: ${e.message}")
            return
        }
        running = loop
        val verifier = newVerifier()
        val state = randomString(16)
        val url = authUrl(clientId, loop.redirectUri, challenge(verifier), state)
        Store.setAuthError(app, null)

        Thread {
            try {
                val q = loop.awaitRedirect { params -> resultPage(params["error"] == null && params["state"] == state) }
                when {
                    q["state"] != state -> throw IOException("Sign-in answer did not match. Try again.")
                    q["error"] != null -> throw IOException("Google said: ${q["error"]}")
                }
                val code = q["code"] ?: throw IOException("Google sent no code.")
                val t = exchange(clientId, Store.clientSecret(app), code, verifier, loop.redirectUri)
                val refresh = t.refresh ?: throw IOException("Google sent no refresh token. Remove PhotoGate at " +
                    "myaccount.google.com/permissions and connect again.")
                Store.saveTokens(app, refresh, t.access, System.currentTimeMillis() + t.expiresInSec * 1000, emailOf(t.idToken))
                Uploader.retryAll(app) // anything approved while signed out goes now
            } catch (e: SocketTimeoutException) {
                Store.setAuthError(app, "Sign-in timed out. Try again.")
            } catch (e: Exception) {
                if (running === loop) Store.setAuthError(app, e.message ?: e.toString())
            } finally {
                loop.close()
                if (running === loop) running = null
            }
        }.start()

        val uri = Uri.parse(url)
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(c, uri)
        } catch (e: ActivityNotFoundException) {
            try {
                c.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: ActivityNotFoundException) {
                loop.close()
                Store.setAuthError(app, "No web browser installed.")
            }
        }
    }

    fun signOut(c: Context) {
        Store.clearTokens(c)
    }

    // ------------------------------------------------------------------ tokens

    /**
     * A valid access token, refreshing it when needed. Null when the user is not signed in (or the grant was
     * revoked). Throws IOException on network trouble. Call off the main thread.
     */
    @Synchronized
    fun token(c: Context): String? {
        val refresh = Store.refreshToken(c) ?: return null
        val access = Store.accessToken(c)
        if (access != null && Store.accessExpiry(c) > System.currentTimeMillis() + 60_000) return access
        return try {
            val t = refresh(Store.clientId(c), Store.clientSecret(c), refresh)
            Store.saveAccess(c, t.access, System.currentTimeMillis() + t.expiresInSec * 1000)
            t.access
        } catch (e: GrantRevoked) {
            Store.clearTokens(c)
            Store.setAuthError(c, "Google access expired or was removed. Connect again.")
            null
        }
    }

    /** Forget the cached access token (after a 401) so the next token() call refreshes. */
    fun invalidate(c: Context) = Store.saveAccess(c, null, 0L)

    fun exchange(clientId: String, secret: String, code: String, verifier: String, redirect: String): Tokens =
        postToken(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "code_verifier" to verifier,
                "redirect_uri" to redirect,
                "client_id" to clientId,
                "client_secret" to secret,
            )
        )

    fun refresh(clientId: String, secret: String, refreshToken: String): Tokens =
        postToken(
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to clientId,
                "client_secret" to secret,
            )
        )

    private fun postToken(form: Map<String, String>): Tokens {
        val body = form.filterValues { it.isNotEmpty() }.entries
            .joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val conn = URL(tokenEndpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val err = runCatching { JSONObject(text) }.getOrNull()
                val kind = err?.optString("error").orEmpty()
                val desc = err?.optString("error_description").orEmpty()
                if (kind == "invalid_grant" || kind == "invalid_client" || kind == "unauthorized_client") {
                    throw GrantRevoked("$kind $desc".trim())
                }
                throw IOException("Token request failed: HTTP $code $kind $desc".trim())
            }
            val j = JSONObject(text)
            return Tokens(
                access = j.getString("access_token"),
                expiresInSec = j.optLong("expires_in", 3600),
                refresh = j.optString("refresh_token").ifEmpty { null },
                idToken = j.optString("id_token").ifEmpty { null },
            )
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ helpers (pure, unit tested)

    fun authUrl(clientId: String, redirect: String, challenge: String, state: String): String =
        authEndpoint + "?" + listOf(
            "client_id" to clientId,
            "redirect_uri" to redirect,
            "response_type" to "code",
            "scope" to SCOPES,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "access_type" to "offline", // we need a refresh token
            "prompt" to "consent", // always return a refresh token, even on reconnect
        ).joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }

    fun newVerifier(): String = randomString(48)

    fun challenge(verifier: String): String =
        b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** Email claim of a Google id_token, or null. The token came straight from Google over TLS, so no signature check. */
    fun emailOf(idToken: String?): String? {
        val payload = idToken?.split('.')?.getOrNull(1) ?: return null
        return runCatching {
            JSONObject(String(Base64.getUrlDecoder().decode(payload))).optString("email").ifEmpty { null }
        }.getOrNull()
    }

    fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    private fun randomString(bytes: Int): String =
        b64url(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

    private fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun resultPage(ok: Boolean): String {
        val msg = if (ok) "Connected. You can go back to PhotoGate." else "Sign-in did not finish. Go back to PhotoGate and try again."
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>PhotoGate</title><style>body{font-family:sans-serif;text-align:center;padding:48px 16px;background:#FAFAF8;color:#111}
a{display:inline-block;margin-top:24px;padding:14px 28px;background:#16A34A;color:#fff;border-radius:24px;text-decoration:none}</style></head>
<body><h2>$msg</h2><a href="intent://auth#Intent;scheme=photogate;package=com.raman.photogate;end">Back to PhotoGate</a></body></html>"""
    }
}

/** A one-shot HTTP listener on 127.0.0.1 that catches Google's redirect. */
class Loopback(private val timeoutMs: Int = 5 * 60_000) : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = timeoutMs }

    val port: Int get() = server.localPort
    val redirectUri: String get() = "http://127.0.0.1:$port"

    /**
     * Blocks until a request with a query string arrives (ignores favicon and browser preconnects), answers it with
     * [page] and returns its query parameters.
     */
    fun awaitRedirect(page: (Map<String, String>) -> String): Map<String, String> {
        while (true) {
            server.accept().use { s ->
                s.soTimeout = 10_000
                val line = try {
                    s.getInputStream().bufferedReader().readLine()
                } catch (e: IOException) {
                    null
                } ?: return@use
                // "GET /?code=...&state=... HTTP/1.1"
                val target = line.split(' ').getOrNull(1).orEmpty()
                val query = target.substringAfter('?', "")
                val out = s.getOutputStream()
                if (query.isEmpty()) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return@use
                }
                val params = Auth.parseQuery(query)
                val html = page(params).toByteArray()
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                out.write(html)
                out.flush()
                return params
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
    }
}
