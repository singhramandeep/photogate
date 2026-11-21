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
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object Uploader {
    fun approve(c: Context, id: Long) {
        Store.removePending(c, id)
        enqueue(c, id)
    }

    fun enqueue(c: Context, id: Long) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf("id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(c.applicationContext)
            .enqueueUniqueWork("upload-$id", ExistingWorkPolicy.KEEP, req)
    }
}

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        val id = inputData.getLong("id", -1L)
        val item = Media.byId(c, id) ?: return@withContext Result.failure()

        val token = try {
            Auth.silentToken(c)
        } catch (e: Exception) {
            null
        }
        if (token == null) {
            Notifier.needSignIn(c)
            return@withContext Result.failure()
        }

        try {
            val albumId = if (Store.albumOn(c) || Store.mode(c) == "album") {
                Store.albumId(c) ?: PhotosApi.createAlbum(token, Store.albumName(c)).also {
                    Store.setAlbumId(c, it)
                }
            } else null
            val uploadToken = PhotosApi.upload(c, token, item)
            PhotosApi.createItem(token, uploadToken, item.name, albumId)
            Store.markDone(c, id)
            Result.success()
        } catch (e: PhotosApi.AuthExpired) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        } catch (e: IOException) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}

object PhotosApi {
    private const val BASE = "https://photoslibrary.googleapis.com/v1"

    class AuthExpired : IOException("Google token expired")

    private fun read(conn: HttpURLConnection): String {
        val code = conn.responseCode
        if (code == 401) throw AuthExpired()
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IOException("HTTP $code $text")
        return text
    }

    /** Step 1: send the raw bytes, get back an upload token. */
    fun upload(c: Context, token: String, item: Item): String {
        val conn = URL("$BASE/uploads").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 180_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-type", "application/octet-stream")
        conn.setRequestProperty("X-Goog-Upload-Content-Type", item.mime)
        conn.setRequestProperty("X-Goog-Upload-Protocol", "raw")
        conn.setChunkedStreamingMode(256 * 1024)
        val input = c.contentResolver.openInputStream(item.uri)
            ?: throw IOException("Cannot open ${item.uri}")
        input.use { i -> conn.outputStream.use { o -> i.copyTo(o) } }
        return read(conn)
    }

    /** Step 2: turn the upload token into a library item (optionally inside an album). */
    fun createItem(token: String, uploadToken: String, name: String, albumId: String?) {
        val body = JSONObject().apply {
            if (albumId != null) put("albumId", albumId)
            put("newMediaItems", JSONArray().put(JSONObject().apply {
                put("description", "")
                put("simpleMediaItem", JSONObject().apply {
                    put("fileName", name)
                    put("uploadToken", uploadToken)
                })
            }))
        }
        val res = JSONObject(postJson("$BASE/mediaItems:batchCreate", token, body))
        val status = res.optJSONArray("newMediaItemResults")?.optJSONObject(0)?.optJSONObject("status")
        // success has no error code (or code 0)
        if (status != null && status.optInt("code", 0) != 0) {
            throw IOException("Create failed: ${status.optString("message")}")
        }
    }

    fun createAlbum(token: String, title: String): String {
        val body = JSONObject().put("album", JSONObject().put("title", title))
        return JSONObject(postJson("$BASE/albums", token, body)).getString("id")
    }

    private fun postJson(url: String, token: String, body: JSONObject): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-type", "application/json; charset=utf-8")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        return read(conn)
    }
}
