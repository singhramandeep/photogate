package com.raman.photogate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CategoriesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun guessesFromFolderNames() {
        assertEquals("Camera", Categories.guess("DCIM/Camera/"))
        assertEquals("Screenshots", Categories.guess("Pictures/Screenshots/"))
        assertEquals("Screenshots", Categories.guess("DCIM/Screenshots/"))
        assertEquals("Social", Categories.guess("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"))
        assertEquals("Social", Categories.guess("Pictures/Telegram/"))
        assertEquals("Social", Categories.guess("Pictures/Instagram/"))
        assertEquals("Downloads", Categories.guess("Download/"))
        assertEquals("Other", Categories.guess("Pictures/Edited/"))
    }

    @Test
    fun cleansNamesIntoSafeFolders() {
        assertEquals("Family trip", Categories.clean("  Family   trip "))
        assertEquals("a b", Categories.clean("a/b"))
        assertEquals("hidden", Categories.clean("..hidden"))
        assertNull(Categories.clean("   "))
        assertNull(Categories.clean("..."))
        assertEquals(40, Categories.clean("x".repeat(100))!!.length)
    }

    @Test
    fun countdownText() {
        assertEquals("Deleting now", Categories.deletesIn(0))
        assertEquals("Deletes in under a minute", Categories.deletesIn(30_000))
        assertEquals("Deletes in 45 min", Categories.deletesIn(45 * 60_000L + 5_000))
        assertEquals("Deletes in 4 h", Categories.deletesIn(4 * 3_600_000L))
        assertEquals("Deletes in 3 h 20 min", Categories.deletesIn(200 * 60_000L))
        assertEquals("Deletes in 23 h 59 min", Categories.deletesIn(24 * 3_600_000L - 1))
    }

    @Test
    fun shortCountdownFitsATile() {
        assertEquals("44m", Categories.shortLeft(44 * 60_000L + 59_000))
        assertEquals("3h 20m", Categories.shortLeft(200 * 60_000L))
        assertEquals("24h", Categories.shortLeft(24 * 3_600_000L))
        assertEquals("<1m", Categories.shortLeft(10_000))
        assertEquals("now", Categories.shortLeft(-5))
    }

    @Test
    fun approveMovesIntoCategoryFolder() {
        val sd = tmp.newFolder("sdcard")
        val gate = Gate(File(sd, "DCIM/PhotoGate"))
        val shot = File(sd, "Pictures/Screenshots").apply { mkdirs() }.let { File(it, "s.png").apply { writeText("x") } }
        val dst = gate.approve(shot, "Screenshots")
        assertEquals(File(sd, "DCIM/PhotoGate/Screenshots/s.png"), dst)
        val odd = File(sd, "DCIM/Camera").apply { mkdirs() }.let { File(it, "c.jpg").apply { writeText("y") } }
        assertEquals("unsafe name is cleaned", File(sd, "DCIM/PhotoGate/a b/c.jpg"), gate.approve(odd, "a/b"))
    }
}
