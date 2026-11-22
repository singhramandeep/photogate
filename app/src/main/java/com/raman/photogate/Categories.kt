package com.raman.photogate

/** Categories of tracked folders. Each one is a subfolder of DCIM/PhotoGate where approved photos land. */
object Categories {
    val PRESETS = listOf("Camera", "Screenshots", "Social", "Downloads", "Other")

    private val SOCIAL = listOf(
        "whatsapp", "telegram", "instagram", "facebook", "messenger", "snapchat", "signal", "twitter",
        "tiktok", "discord", "viber", "wechat", "line", "threads", "reddit", "pinterest",
    )

    /** A sensible first guess from the folder path (MediaStore RELATIVE_PATH). */
    fun guess(path: String): String {
        val p = path.lowercase()
        val words = p.split('/', ' ', '_', '-', '.').filter { it.isNotEmpty() }
        return when {
            "screenshots" in words || "screenshot" in words || "screen recordings" in p || "screenrecorder" in p ->
                "Screenshots"
            SOCIAL.any { it in words } -> "Social"
            "download" in words || "downloads" in words -> "Downloads"
            p.startsWith("dcim/camera") || "camera" in words -> "Camera"
            else -> "Other"
        }
    }

    /**
     * Folder on disk for a category: "p" + name (pCamera, pScreenshots...). Galleries show only the folder name, so
     * without the prefix PhotoGate's Camera folder would look exactly like the real Camera folder.
     */
    const val PREFIX = "p"

    fun folderName(category: String): String? = clean(category)?.let { PREFIX + it }

    /** Category from a folder name under PhotoGate: "pCamera" -> "Camera". */
    fun fromFolder(name: String): String =
        if (name.length > 1 && name.startsWith(PREFIX) && name[1].isUpperCase()) name.substring(1) else name

    /** Turns what the user typed into a safe folder name, or null if nothing usable is left. */
    fun clean(name: String): String? {
        val s = name.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimStart('.')
            .trim()
            .take(40)
        return s.ifEmpty { null }
    }

    /** Short form for the tag on a thumbnail: "44m", "3h 20m", "4h". */
    fun shortLeft(ms: Long): String {
        val min = ms / 60_000
        return when {
            ms <= 0 -> "now"
            min < 1 -> "<1m"
            min < 60 -> "${min}m"
            min % 60 == 0L -> "${min / 60}h"
            else -> "${min / 60}h ${min % 60}m"
        }
    }

    /** Human-friendly time until a discarded photo is deleted. */
    fun deletesIn(ms: Long): String {
        val min = ms / 60_000
        return when {
            ms <= 0 -> "Deleting now"
            min < 1 -> "Deletes in under a minute"
            min < 60 -> "Deletes in $min min"
            min % 60 == 0L -> "Deletes in ${min / 60} h"
            else -> "Deletes in ${min / 60} h ${min % 60} min"
        }
    }
}
