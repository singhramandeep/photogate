package com.raman.photogate

import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Writes the category into an approved photo's description (EXIF ImageDescription), so it travels with the file.
 * Works for JPEG, PNG and WebP. HEIC and videos are left as they are (Android cannot rewrite their metadata).
 */
object Describe {
    private val WRITABLE = setOf("jpg", "jpeg", "png", "webp")

    /** Descriptions some cameras put in by default; they are replaced rather than kept. */
    private val JUNK = setOf(
        "", "default", "image", "picture", "photo", "description", "exif_jpeg_picture",
        "olympus digital camera", "sony dsc", "digital camera", "samsung", "camera",
    )

    fun supports(f: File): Boolean = f.extension.lowercase() in WRITABLE

    /**
     * The new description: the category, added to what the photo already says (never twice).
     * EXIF descriptions are plain ASCII, so the separator is " | " (a "·" would turn into "?").
     */
    fun merge(existing: String?, category: String): String {
        val old = existing?.trim().orEmpty()
        if (old.lowercase() in JUNK) return category
        if (old.split("|").any { it.trim().equals(category, ignoreCase = true) }) return old
        return "$old | $category"
    }

    /** True if the description was written. Failure is harmless: the photo is already in its category folder. */
    fun write(f: File, category: String): Boolean {
        if (!supports(f)) return false
        return try {
            val time = f.lastModified()
            val exif = ExifInterface(f)
            exif.setAttribute(
                ExifInterface.TAG_IMAGE_DESCRIPTION,
                merge(exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION), category),
            )
            exif.saveAttributes()
            f.setLastModified(time) // keep the photo's date
            true
        } catch (e: Exception) {
            false
        }
    }
}
