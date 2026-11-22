package com.raman.photogate

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray

/** All app state, in SharedPreferences. `tick` changes on every write so Compose screens refresh. */
object Store {
    val tick = mutableIntStateOf(0)

    private fun p(c: Context) =
        c.applicationContext.getSharedPreferences("photogate", Context.MODE_PRIVATE)

    private fun bump() {
        tick.intValue = tick.intValue + 1
    }

    // ---- folders the app catches new photos from (MediaStore RELATIVE_PATH, e.g. "DCIM/Camera/") ----
    fun folders(c: Context): Set<String> =
        (p(c).getStringSet("folders", setOf("DCIM/Camera/")) ?: emptySet()).toSet()

    fun setFolder(c: Context, path: String, on: Boolean) {
        val s = folders(c).toMutableSet()
        if (on) s.add(path) else s.remove(path)
        p(c).edit().putStringSet("folders", s).apply()
        bump()
    }

    // ---- category of each tracked folder (falls back to a guess from the folder name) ----
    fun categoryOf(c: Context, folder: String): String =
        p(c).getString("cat:$folder", null) ?: Categories.guess(folder)

    fun setCategory(c: Context, folder: String, category: String) {
        val clean = Categories.clean(category) ?: return
        p(c).edit().putString("cat:$folder", clean).apply()
        if (clean !in Categories.PRESETS) addCategory(c, clean)
        bump()
    }

    /** Preset categories plus any the user made. */
    fun categories(c: Context): List<String> =
        Categories.PRESETS + (p(c).getStringSet("customCats", emptySet()) ?: emptySet())
            .filter { it !in Categories.PRESETS }.sorted()

    fun addCategory(c: Context, name: String) {
        val clean = Categories.clean(name) ?: return
        val s = (p(c).getStringSet("customCats", emptySet()) ?: emptySet()) + clean
        p(c).edit().putStringSet("customCats", s).apply()
        bump()
    }

    // ---- media id that was newest when the app first ran; nothing at or below it is ever touched ----
    fun lastSeen(c: Context): Long = p(c).getLong("lastSeen", -1L)
    fun setLastSeen(c: Context, id: Long) {
        p(c).edit().putLong("lastSeen", id).apply()
    }

    // ---- media already looked at by a scan: id -> DATE_ADDED seconds (used to prune old entries) ----
    @Synchronized
    fun handled(c: Context): Map<Long, Long> =
        (p(c).getStringSet("handled", emptySet()) ?: emptySet()).mapNotNull {
            val id = it.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
            id to (it.substringAfter(':', "0").toLongOrNull() ?: 0L)
        }.toMap()

    @Synchronized
    fun setHandled(c: Context, m: Map<Long, Long>) {
        p(c).edit().putStringSet("handled", m.map { "${it.key}:${it.value}" }.toSet()).apply()
    }

    // ---- photos being tracked (waiting for a decision, or discarded and due for deletion) ----
    // (key is still "staged" so photos from the earlier staging-folder version are found and put back)
    @Synchronized
    fun staged(c: Context): List<Photo> {
        val raw = p(c).getString("staged", null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { Photo.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun putStaged(c: Context, list: List<Photo>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        p(c).edit().putString("staged", a.toString()).apply()
        bump()
    }

    @Synchronized
    fun addStaged(c: Context, s: Photo) = putStaged(c, staged(c).filter { it.id != s.id } + s)

    @Synchronized
    fun updateStaged(c: Context, s: Photo) = putStaged(c, staged(c).map { if (it.id == s.id) s else it })

    @Synchronized
    fun removeStaged(c: Context, id: Long) = putStaged(c, staged(c).filter { it.id != id })

    fun stagedById(c: Context, id: Long): Photo? = staged(c).firstOrNull { it.id == id }

    // ---- counters ----
    fun approvedCount(c: Context): Int = p(c).getInt("approved", 0)
    fun deletedCount(c: Context): Int = p(c).getInt("deleted", 0)

    @Synchronized
    fun countApproved(c: Context) {
        p(c).edit().putInt("approved", approvedCount(c) + 1).apply()
        bump()
    }

    @Synchronized
    fun countDeleted(c: Context) {
        p(c).edit().putInt("deleted", deletedCount(c) + 1).apply()
        bump()
    }

    // ---- temporary modes: "none" (don't ask, do nothing), "all" (approve everything) ----
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
    fun mode(c: Context): String {
        if (modeUntil(c) <= System.currentTimeMillis()) return "ask"
        val m = p(c).getString("mode", "ask") ?: "ask"
        return if (m == "none" || m == "all") m else "ask" // older versions had an "album" mode
    }

    // ---- decision window (minutes, 0 = no limit) ----
    fun windowMin(c: Context): Int = p(c).getInt("windowMin", 0)
    fun setWindowMin(c: Context, m: Int) {
        p(c).edit().putInt("windowMin", m).apply()
        bump()
    }

    // ---- how long a discarded photo waits before it is deleted (minutes, 0 = immediately) ----
    fun deleteAfterMin(c: Context): Int = p(c).getInt("deleteAfterMin", 24 * 60)
    fun setDeleteAfterMin(c: Context, m: Int) {
        p(c).edit().putInt("deleteAfterMin", m).apply()
        bump()
    }

    // ---- misc ----
    fun watch(c: Context): Boolean = p(c).getBoolean("watch", false)
    fun setWatch(c: Context, on: Boolean) {
        p(c).edit().putBoolean("watch", on).apply()
        bump()
    }

    fun refresh() = bump()
}
