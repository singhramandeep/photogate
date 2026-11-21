package com.raman.photogate

import android.Manifest
import android.content.Context
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat

data class Item(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val name: String,
    val folder: String,
    val mime: String,
    val addedMs: Long,
    /** Last write to the file. For a long video this is when recording stopped, not when it started. */
    val modifiedMs: Long = addedMs,
)

object Media {
    private val FILES: Uri = MediaStore.Files.getContentUri("external")
    private const val TYPES = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1,3))"

    private val PROJECTION = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.RELATIVE_PATH,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.DATE_ADDED,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
    )

    /** Runtime permissions to ask for. READ_MEDIA_* only exist from Android 13; 11 and 12 use READ_EXTERNAL_STORAGE. */
    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.POST_NOTIFICATIONS,
        ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasPermission(c: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun toItem(cur: Cursor): Item {
        val id = cur.getLong(0)
        val video = cur.getInt(4) == 3
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return Item(
            id = id,
            uri = ContentUris.withAppendedId(base, id),
            isVideo = video,
            name = cur.getString(1) ?: "photo",
            folder = cur.getString(2) ?: "",
            mime = cur.getString(3) ?: if (video) "video/mp4" else "image/jpeg",
            addedMs = cur.getLong(5) * 1000L,
            modifiedMs = cur.getLong(6) * 1000L,
        )
    }

    private fun query(c: Context, selection: String, args: Array<String>): List<Item> {
        val out = ArrayList<Item>()
        c.contentResolver.query(
            FILES, PROJECTION, "$TYPES AND ($selection)", args,
            "${MediaStore.Files.FileColumns._ID} ASC"
        )?.use { cur ->
            while (cur.moveToNext()) out.add(toItem(cur))
        }
        return out
    }

    /**
     * Items added since [sinceSec] with an id above [afterId]. MediaStore hides files that are still being written
     * (IS_PENDING), and a long video gets its id when recording starts, so a plain "id > last seen" scan would
     * skip it. Looking back by time and remembering what was handled catches it once it is finished.
     */
    fun recent(c: Context, sinceSec: Long, afterId: Long): List<Item> =
        query(
            c,
            "${MediaStore.Files.FileColumns.DATE_ADDED} >= ? AND ${MediaStore.Files.FileColumns._ID} > ?",
            arrayOf(sinceSec.toString(), afterId.toString()),
        )

    fun byId(c: Context, id: Long): Item? =
        query(c, "${MediaStore.Files.FileColumns._ID} = ?", arrayOf(id.toString())).firstOrNull()

    fun byIds(c: Context, ids: Collection<Long>): List<Item> {
        if (ids.isEmpty()) return emptyList()
        // ids are plain longs, safe to inline
        val inList = ids.toSet().joinToString(",")
        return query(c, "${MediaStore.Files.FileColumns._ID} IN ($inList)", emptyArray())
            .sortedByDescending { it.id }
    }

    fun maxId(c: Context): Long {
        c.contentResolver.query(
            FILES, arrayOf(MediaStore.Files.FileColumns._ID), TYPES, null,
            "${MediaStore.Files.FileColumns._ID} DESC"
        )?.use { cur ->
            if (cur.moveToFirst()) return cur.getLong(0)
        }
        return 0L
    }

    /** Every folder that holds photos or videos, with item counts, biggest first. */
    fun folders(c: Context): List<Pair<String, Int>> {
        val counts = HashMap<String, Int>()
        c.contentResolver.query(
            FILES, arrayOf(MediaStore.Files.FileColumns.RELATIVE_PATH), TYPES, null, null
        )?.use { cur ->
            while (cur.moveToNext()) {
                val path = cur.getString(0) ?: continue
                counts[path] = (counts[path] ?: 0) + 1
            }
        }
        return counts.entries.map { it.key to it.value }.sortedByDescending { it.second }
    }

    fun thumb(c: Context, uri: Uri, px: Int = 400): Bitmap? = try {
        c.contentResolver.loadThumbnail(uri, Size(px, px), null)
    } catch (e: Exception) {
        null
    }

    fun label(path: String): String =
        path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
}
