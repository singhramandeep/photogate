package com.raman.photogate

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf

/** All app state, in SharedPreferences. `tick` changes on every write so Compose screens refresh. */
object Store {
    val tick = mutableIntStateOf(0)

    private fun p(c: Context) =
        c.applicationContext.getSharedPreferences("photogate", Context.MODE_PRIVATE)

    private fun bump() {
        tick.intValue = tick.intValue + 1
    }

    // ---- folders the app asks about (MediaStore RELATIVE_PATH, e.g. "DCIM/Camera/") ----
    fun folders(c: Context): Set<String> =
        (p(c).getStringSet("folders", setOf("DCIM/Camera/")) ?: emptySet()).toSet()

    fun setFolder(c: Context, path: String, on: Boolean) {
        val s = folders(c).toMutableSet()
        if (on) s.add(path) else s.remove(path)
        p(c).edit().putStringSet("folders", s).apply()
        bump()
    }

    // ---- media id that was newest when the app first ran; nothing at or below it is ever offered ----
    fun lastSeen(c: Context): Long = p(c).getLong("lastSeen", -1L)
    fun setLastSeen(c: Context, id: Long) {
        p(c).edit().putLong("lastSeen", id).apply()
    }

    // ---- per-photo decisions ----
    private fun ids(c: Context, key: String): Set<Long> =
        (p(c).getStringSet(key, emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()

    private fun putIds(c: Context, key: String, set: Set<Long>) {
        p(c).edit().putStringSet(key, set.map { it.toString() }.toSet()).apply()
        bump()
    }

    @Synchronized
    fun pending(c: Context): Set<Long> = ids(c, "pending")

    @Synchronized
    fun addPending(c: Context, id: Long) = putIds(c, "pending", ids(c, "pending") + id)

    @Synchronized
    fun removePending(c: Context, id: Long) = putIds(c, "pending", ids(c, "pending") - id)

    @Synchronized
    fun discard(c: Context, id: Long) {
        putIds(c, "pending", ids(c, "pending") - id)
        putIds(c, "discarded", ids(c, "discarded") + id)
    }

    fun discardedCount(c: Context): Int = ids(c, "discarded").size

    // ---- media already looked at by a scan: id -> DATE_ADDED seconds (used to prune old entries) ----
    private fun pairs(c: Context, key: String): Map<Long, Long> =
        (p(c).getStringSet(key, emptySet()) ?: emptySet()).mapNotNull {
            val id = it.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
            id to (it.substringAfter(':', "0").toLongOrNull() ?: 0L)
        }.toMap()

    private fun putPairs(c: Context, key: String, m: Map<Long, Long>) {
        p(c).edit().putStringSet(key, m.map { "${it.key}:${it.value}" }.toSet()).apply()
        bump()
    }

    @Synchronized
    fun handled(c: Context): Map<Long, Long> = pairs(c, "handled")

    @Synchronized
    fun setHandled(c: Context, m: Map<Long, Long>) = putPairs(c, "handled", m)

    // ---- approved, waiting to upload: id -> 1 if it goes into the album, else 0 ----
    @Synchronized
    fun queued(c: Context): Map<Long, Boolean> = pairs(c, "queued").mapValues { it.value == 1L }

    @Synchronized
    fun addQueued(c: Context, id: Long, album: Boolean) =
        putPairs(c, "queued", pairs(c, "queued") + (id to if (album) 1L else 0L))

    @Synchronized
    fun removeQueued(c: Context, id: Long) = putPairs(c, "queued", pairs(c, "queued") - id)

    @Synchronized
    fun markDone(c: Context, id: Long) {
        putPairs(c, "queued", pairs(c, "queued") - id)
        p(c).edit().putInt("uploaded", uploadedCount(c) + 1).apply()
        bump()
    }

    fun uploadedCount(c: Context): Int = p(c).getInt("uploaded", 0)

    // ---- temporary modes: "none" (upload nothing), "all" (upload everything), "album" (everything into album) ----
    fun startMode(c: Context, mode: String, durationMs: Long) {
        p(c).edit().putString("mode", mode)
            .putLong("modeUntil", System.currentTimeMillis() + durationMs).apply()
        bump()
    }

    fun stopMode(c: Context) {
        p(c).edit().putLong("modeUntil", 0L).apply()
        bump()
    }

    fun modeUntil(c: Context): Long = p(c).getLong("modeUntil", 0L)

    /** "ask" when no temporary mode is running. */
    fun mode(c: Context): String =
        if (modeUntil(c) > System.currentTimeMillis()) p(c).getString("mode", "ask") ?: "ask" else "ask"

    // ---- decision window (minutes, 0 = no limit) ----
    fun windowMin(c: Context): Int = p(c).getInt("windowMin", 0)
    fun setWindowMin(c: Context, m: Int) {
        p(c).edit().putInt("windowMin", m).apply()
        bump()
    }

    // ---- collection (Google Photos album created by this app) ----
    fun albumOn(c: Context): Boolean = p(c).getBoolean("albumOn", false)
    fun setAlbumOn(c: Context, on: Boolean) {
        p(c).edit().putBoolean("albumOn", on).apply()
        bump()
    }

    fun albumName(c: Context): String =
        p(c).getString("albumName", "PhotoGate")?.trim()?.ifEmpty { null } ?: "PhotoGate"

    fun albumNameRaw(c: Context): String = p(c).getString("albumName", "PhotoGate") ?: "PhotoGate"
    fun setAlbumName(c: Context, name: String) {
        // changing the name means a new album is created on next upload
        p(c).edit().putString("albumName", name).remove("albumId").apply()
        bump()
    }

    fun albumId(c: Context): String? = p(c).getString("albumId", null)
    fun setAlbumId(c: Context, id: String?) {
        p(c).edit().putString("albumId", id).apply()
    }

    // ---- misc ----
    fun watch(c: Context): Boolean = p(c).getBoolean("watch", false)
    fun setWatch(c: Context, on: Boolean) {
        p(c).edit().putBoolean("watch", on).apply()
        bump()
    }

    // ---- Google sign-in (browser OAuth, see Auth) ----
    fun clientId(c: Context): String = p(c).getString("clientId", "")?.trim() ?: ""
    fun clientSecret(c: Context): String = p(c).getString("clientSecret", "")?.trim() ?: ""
    fun setClient(c: Context, id: String, secret: String) {
        p(c).edit().putString("clientId", id.trim()).putString("clientSecret", secret.trim()).apply()
        bump()
    }

    fun connected(c: Context): Boolean = refreshToken(c) != null
    fun email(c: Context): String? = p(c).getString("email", null)
    fun refreshToken(c: Context): String? = p(c).getString("refreshToken", null)
    fun accessToken(c: Context): String? = p(c).getString("accessToken", null)
    fun accessExpiry(c: Context): Long = p(c).getLong("accessExpiry", 0L)

    fun saveTokens(c: Context, refresh: String, access: String, expiry: Long, email: String?) {
        p(c).edit().putString("refreshToken", refresh).putString("accessToken", access)
            .putLong("accessExpiry", expiry).putString("email", email).remove("authError").apply()
        bump()
    }

    fun saveAccess(c: Context, access: String?, expiry: Long) {
        p(c).edit().putString("accessToken", access).putLong("accessExpiry", expiry).apply()
    }

    fun clearTokens(c: Context) {
        p(c).edit().remove("refreshToken").remove("accessToken").remove("accessExpiry").remove("email").apply()
        bump()
    }

    fun authError(c: Context): String? = p(c).getString("authError", null)
    fun setAuthError(c: Context, msg: String?) {
        p(c).edit().putString("authError", msg).apply()
        bump()
    }
}
