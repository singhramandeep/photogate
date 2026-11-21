package com.raman.photogate

import org.json.JSONObject
import java.io.File
import java.io.IOException

/** One file in the staging folder. [deleteAt] is 0 while it waits for a decision, else when it gets deleted. */
data class Staged(
    val id: Long, // MediaStore id it had in its original folder
    val path: String, // absolute path inside the staging folder
    val name: String,
    val from: String, // original RELATIVE_PATH, e.g. "DCIM/Camera/"
    val isVideo: Boolean,
    val stagedAt: Long,
    val deleteAt: Long = 0L,
) {
    val discarded: Boolean get() = deleteAt > 0

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("path", path).put("name", name).put("from", from)
        .put("video", isVideo).put("at", stagedAt).put("deleteAt", deleteAt)

    companion object {
        fun fromJson(j: JSONObject) = Staged(
            id = j.getLong("id"),
            path = j.getString("path"),
            name = j.getString("name"),
            from = j.optString("from"),
            isVideo = j.optBoolean("video"),
            stagedAt = j.optLong("at"),
            deleteAt = j.optLong("deleteAt"),
        )
    }
}

/**
 * Plain file operations behind PhotoGate (no Android APIs, so they are unit tested with real files).
 *
 *   <root>/            = DCIM/PhotoGate: approved photos. A normal device folder that Google Photos can back up.
 *   <root>/.staging/   = waiting and discarded photos. Hidden and marked .nomedia, so galleries and Google Photos
 *                        never see what is in it.
 */
class Gate(val root: File) {
    val staging = File(root, ".staging")

    fun ensureDirs() {
        if (!staging.isDirectory && !staging.mkdirs()) throw IOException("Cannot create $staging")
        val nomedia = File(staging, ".nomedia")
        if (!nomedia.exists()) nomedia.createNewFile()
    }

    /** Moves a new photo out of its folder into staging. Returns where it now is. */
    fun stage(src: File): File {
        ensureDirs()
        return move(src, unique(staging, src.name))
    }

    /** Moves a photo (staged, or straight from its folder) into the approved folder. */
    fun approve(src: File): File {
        ensureDirs()
        return move(src, unique(root, src.name))
    }

    /** Deletes a staged photo. True if it is gone (also when it was already gone). */
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

        /** Rename when possible (instant), else copy and delete. The file's date is kept either way. */
        fun move(src: File, dst: File): File {
            if (!src.isFile) throw IOException("Missing $src")
            if (src.renameTo(dst)) return dst
            val time = src.lastModified()
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
            dst.setLastModified(time)
            if (!src.delete()) {
                // keep exactly one copy
                dst.delete()
                throw IOException("Cannot remove $src")
            }
            return dst
        }

        /** Discarded files whose time is up. */
        fun due(list: List<Staged>, nowMs: Long): List<Staged> = list.filter { it.discarded && it.deleteAt <= nowMs }

        /** Earliest future deletion, or null. */
        fun nextDue(list: List<Staged>): Long? = list.filter { it.discarded }.minOfOrNull { it.deleteAt }
    }
}
