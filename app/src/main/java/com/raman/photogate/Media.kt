package com.raman.photogate

import android.Manifest
import android.content.Context
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
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
    )

    fun hasPermission(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED

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
        )
    }

    private fun query(selection: String, args: Array<String>): List<Item> {
        val out = ArrayList<Item>()
        val cr = appContext?.contentResolver ?: return out
        cr.query(
            FILES, PROJECTION, "$TYPES AND ($selection)", args,
            "${MediaStore.Files.FileColumns._ID} ASC"
        )?.use { cur ->
            while (cur.moveToNext()) out.add(toItem(cur))
        }
        return out
    }

    // Set from Application-less code paths: every entry point calls init() first.
    private var appContext: Context? = null
    fun init(c: Context) {
        appContext = c.applicationContext
    }

    fun newerThan(c: Context, lastId: Long): List<Item> {
        init(c)
        return query("${MediaStore.Files.FileColumns._ID} > ?", arrayOf(lastId.toString()))
    }

    fun byId(c: Context, id: Long): Item? {
        init(c)
        return query("${MediaStore.Files.FileColumns._ID} = ?", arrayOf(id.toString())).firstOrNull()
    }

    fun byIds(c: Context, ids: Collection<Long>): List<Item> {
        init(c)
        if (ids.isEmpty()) return emptyList()
        val set = ids.toSet()
        // ids are plain longs, safe to inline
        val inList = set.joinToString(",")
        return query("${MediaStore.Files.FileColumns._ID} IN ($inList)", emptyArray())
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
