package com.raman.photogate

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object Uploader {
    fun approve(c: Context, id: Long) {
        Store.removePending(c, id)
        enqueue(c, id, Store.albumOn(c))
    }

    /** The item stays in Store.queued until it is really uploaded, so nothing is lost while signed out or offline. */
    fun enqueue(c: Context, id: Long, album: Boolean) {
        Store.addQueued(c, id, album)
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf("id" to id, "album" to album))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(c.applicationContext)
            .enqueueUniqueWork("upload-$id", ExistingWorkPolicy.KEEP, req)
    }

    /** Re-enqueue everything approved but not uploaded yet (after signing in, or from the Retry button). */
    fun retryAll(c: Context) {
        Store.queued(c).forEach { (id, album) -> enqueue(c, id, album) }
    }
}

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        val id = inputData.getLong("id", -1L)
        val album = inputData.getBoolean("album", false)
        val item = Media.byId(c, id)
        if (item == null) {
            // deleted from the phone before it could upload
            Store.removeQueued(c, id)
            return@withContext Result.failure()
        }

        try {
            val token = Auth.token(c)
            if (token == null) {
                // stays in the queue; signing in re-enqueues it
                Notifier.needSignIn(c)
                return@withContext Result.failure()
            }
            val uploadToken = c.contentResolver.openInputStream(item.uri)?.use {
                PhotosApi.upload(token, it, item.mime)
            } ?: throw IOException("Cannot open ${item.uri}")

            val albumId = if (album) Albums.get(c, token) else null
            try {
                PhotosApi.createItem(token, uploadToken, item.name, albumId)
            } catch (e: PhotosApi.HttpError) {
                // the album was deleted in Google Photos (or is not ours any more): make a fresh one, try once more
                if (albumId == null || e.code !in setOf(400, 403, 404)) throw e
                Albums.forget(c, albumId)
                PhotosApi.createItem(token, uploadToken, item.name, Albums.get(c, token))
            }
            Store.markDone(c, id)
            Result.success()
        } catch (e: PhotosApi.AuthExpired) {
            Auth.invalidate(c)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        } catch (e: IOException) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        } catch (e: JSONException) {
            Result.failure()
        }
    }
}

/** The app's own album. Only one worker may create it, or parallel uploads would make duplicates. */
object Albums {
    @Synchronized
    fun get(c: Context, token: String): String =
        Store.albumId(c) ?: PhotosApi.createAlbum(token, Store.albumName(c)).also { Store.setAlbumId(c, it) }

    @Synchronized
    fun forget(c: Context, id: String) {
        if (Store.albumId(c) == id) Store.setAlbumId(c, null)
    }
}

object PhotosApi {
    var base = "https://photoslibrary.googleapis.com/v1" // overridable for tests

    open class HttpError(val code: Int, body: String) : IOException("HTTP $code $body")
    class AuthExpired(body: String) : HttpError(401, body)

    private fun read(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code == 401) throw AuthExpired(text)
        if (code !in 200..299) throw HttpError(code, text)
        return text
    }

    /** Step 1: send the raw bytes, get back an upload token. */
    fun upload(token: String, input: InputStream, mime: String): String {
        val conn = URL("$base/uploads").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 180_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("X-Goog-Upload-Content-Type", mime)
            conn.setRequestProperty("X-Goog-Upload-Protocol", "raw")
            conn.setChunkedStreamingMode(256 * 1024)
            conn.outputStream.use { o -> input.copyTo(o) }
            val t = read(conn).trim()
            if (t.isEmpty()) throw IOException("Empty upload token")
            return t
        } finally {
            conn.disconnect()
        }
    }

    /** Step 2: turn the upload token into a library item (optionally inside an album). */
    fun createItem(token: String, uploadToken: String, name: String, albumId: String?) {
        val body = JSONObject().apply {
            if (albumId != null) put("albumId", albumId)
            put("newMediaItems", JSONArray().put(JSONObject().apply {
                put("simpleMediaItem", JSONObject().apply {
                    put("fileName", name)
                    put("uploadToken", uploadToken)
                })
            }))
        }
        val res = JSONObject(postJson("$base/mediaItems:batchCreate", token, body))
        val result = res.optJSONArray("newMediaItemResults")?.optJSONObject(0)
            ?: throw IOException("Create failed: empty answer")
        val status = result.optJSONObject("status")
        // success has no error code (or code 0) and carries the new mediaItem
        if (status != null && status.optInt("code", 0) != 0) {
            throw IOException("Create failed: ${status.optString("message")}")
        }
    }

    fun createAlbum(token: String, title: String): String {
        val body = JSONObject().put("album", JSONObject().put("title", title))
        return JSONObject(postJson("$base/albums", token, body)).getString("id")
    }

    private fun postJson(url: String, token: String, body: JSONObject): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            return read(conn)
        } finally {
            conn.disconnect()
        }
    }
}
