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
        assertEquals(File(sd, "DCIM/PhotoGate/pScreenshots/s.png"), dst)
        val odd = File(sd, "DCIM/Camera").apply { mkdirs() }.let { File(it, "c.jpg").apply { writeText("y") } }
        assertEquals("unsafe name is cleaned", File(sd, "DCIM/PhotoGate/pa b/c.jpg"), gate.approve(odd, "a/b"))
    }

    @Test
    fun folderPrefix() {
        assertEquals("pCamera", Categories.folderName("Camera"))
        assertEquals("pPets", Categories.folderName("Pets"))
        assertEquals("Camera", Categories.fromFolder("pCamera"))
        assertEquals("Pets", Categories.fromFolder("pPets"))
        assertEquals("photos", Categories.fromFolder("photos")) // not a prefixed name
    }

    @Test
    fun oldFoldersGetThePrefixOnce() {
        val sd = tmp.newFolder("sd2")
        val gate = Gate(File(sd, "DCIM/PhotoGate"))
        val cam = File(gate.root, "Camera").apply { mkdirs() }
        File(cam, "a.jpg").writeText("a")
        File(gate.root, "pScreenshots").apply { mkdirs() }.let { File(it, "s.png").writeText("s") }
        // a clash: pSocial already has b.jpg and old Social has another b.jpg
        File(gate.root, "pSocial").apply { mkdirs() }.let { File(it, "b.jpg").writeText("new") }
        File(gate.root, "Social").apply { mkdirs() }.let { File(it, "b.jpg").writeText("old") }
        File(gate.root, ".staging").mkdirs()

        val moved = gate.prefixOldFolders()
        assertEquals(File(gate.root, "pCamera/a.jpg").path, moved[File(cam, "a.jpg").path])
        assertEquals("a", File(gate.root, "pCamera/a.jpg").readText())
        assertEquals("old", File(gate.root, "pSocial/b (1).jpg").readText())
        assertEquals("new", File(gate.root, "pSocial/b.jpg").readText())
        assertEquals(false, cam.exists())
        assertEquals(false, File(gate.root, "Social").exists())
        assertEquals("prefixed and hidden folders are left alone", true, File(gate.root, "pScreenshots/s.png").exists())
        assertEquals(true, File(gate.root, ".staging").exists())
        assertEquals("second run does nothing", emptyMap<String, String>(), gate.prefixOldFolders())
    }
}
