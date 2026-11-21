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
 * [root] is DCIM/PhotoGate: copies of approved photos, a normal device folder that Google Photos can back up.
 */
class Gate(val root: File) {
    /** Where an earlier version kept photos while waiting. Only used to put them back. */
    val oldStaging = File(root, ".staging")

    fun ensureDirs() {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create $root")
    }

    /** Copies an approved photo into PhotoGate. The original stays where it is. */
    fun approve(src: File): File {
        ensureDirs()
        return copy(src, unique(root, src.name))
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

        /** Rename when possible, else copy and delete. Used only to put old staged files back. */
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
