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

    // ---- newest media id already seen; only newer items are ever offered ----
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

    @Synchronized
    fun markDone(c: Context, id: Long) = putIds(c, "done", ids(c, "done") + id)

    fun discardedCount(c: Context): Int = ids(c, "discarded").size

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

    fun albumName(c: Context): String = p(c).getString("albumName", "photogate") ?: "photogate"
    fun setAlbumName(c: Context, name: String) {
        // changing the name means a new album is created on next upload
        p(c).edit().putString("albumName", name).remove("albumId").apply()
        bump()
    }

    fun albumId(c: Context): String? = p(c).getString("albumId", null)
    fun setAlbumId(c: Context, id: String) {
        p(c).edit().putString("albumId", id).apply()
    }

    // ---- misc ----
    fun watch(c: Context): Boolean = p(c).getBoolean("watch", false)
    fun setWatch(c: Context, on: Boolean) {
        p(c).edit().putBoolean("watch", on).apply()
        bump()
    }

    fun connected(c: Context): Boolean = p(c).getBoolean("connected", false)
    fun setConnected(c: Context, on: Boolean) {
        p(c).edit().putBoolean("connected", on).apply()
        bump()
    }
}
