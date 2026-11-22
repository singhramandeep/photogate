package com.raman.photogate

import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * A new photo PhotoGate is tracking. The file stays in its original folder the whole time.
 * [deleteAt] is 0 while it waits for a decision, else when the original gets deleted (it was discarded).
 */
data class Photo(
    val id: Long, // MediaStore id
    val path: String, // absolute path of the original file
    val name: String,
    val from: String, // RELATIVE_PATH of its folder, e.g. "DCIM/Camera/"
    val isVideo: Boolean,
    val seenAt: Long,
    val deleteAt: Long = 0L,
) {
    val discarded: Boolean get() = deleteAt > 0

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("path", path).put("name", name).put("from", from)
        .put("video", isVideo).put("at", seenAt).put("deleteAt", deleteAt)

    companion object {
        fun fromJson(j: JSONObject) = Photo(
            id = j.getLong("id"),
            path = j.getString("path"),
            name = j.getString("name"),
            from = j.optString("from"),
            isVideo = j.optBoolean("video"),
            seenAt = j.optLong("at"),
            deleteAt = j.optLong("deleteAt"),
        )
    }
}

/**
 * Plain file operations behind PhotoGate (no Android APIs, so they are unit tested with real files).
 * [root] is DCIM/PhotoGate: approved photos, a normal device folder that is synced to Google Photos on its own
 * (e.g. with Samsung Gallery's Google Photos sync, Camera folder off).
 */
class Gate(val root: File) {
    /** Where an earlier version kept photos while waiting. Only used to put them back. */
    val oldStaging = File(root, ".staging")

    fun ensureDirs() {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create $root")
    }

    /** Moves an approved photo out of its folder into PhotoGate/<category>. */
    fun approve(src: File, category: String? = null): File {
        val dir = category?.let { Categories.clean(it) }?.let { File(root, it) } ?: root
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        return move(src, unique(dir, src.name))
    }

    /** Deletes a file. True if it is gone (also when it was already gone). */
    fun delete(f: File): Boolean = !f.exists() || f.delete()

    companion object {
        /** [name] in [dir], or "name (1).ext", "name (2).ext"... if that is taken. Never overwrites. */
        fun unique(dir: File, name: String): File {
            var f = File(dir, name)
            if (!f.exists()) return f
            val base = name.substringBeforeLast('.', name)
            val ext = if ('.' in name) "." + name.substringAfterLast('.') else ""
            var n = 1
            while (true) {
                f = File(dir, "$base ($n)$ext")
                if (!f.exists()) return f
                n++
            }
        }

        /** Copy that keeps the file's date and never leaves a half-written file behind. */
        fun copy(src: File, dst: File): File {
            if (!src.isFile) throw IOException("Missing $src")
            try {
                src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            } catch (e: IOException) {
                dst.delete()
                throw e
            }
            if (dst.length() != src.length()) {
                dst.delete()
                throw IOException("Copy of $src is incomplete")
            }
            dst.setLastModified(src.lastModified())
            return dst
        }

        /** Rename when possible (instant), else copy and delete. Exactly one copy exists afterwards. */
        fun move(src: File, dst: File): File {
            if (!src.isFile) throw IOException("Missing $src")
            dst.parentFile?.mkdirs()
            if (src.renameTo(dst)) return dst
            copy(src, dst)
            if (!src.delete()) {
                dst.delete()
                throw IOException("Cannot remove $src")
            }
            return dst
        }

        /** Discarded photos whose time is up. */
        fun due(list: List<Photo>, nowMs: Long): List<Photo> = list.filter { it.discarded && it.deleteAt <= nowMs }

        /** Earliest future deletion, or null. */
        fun nextDue(list: List<Photo>): Long? = list.filter { it.discarded }.minOfOrNull { it.deleteAt }
    }
}
