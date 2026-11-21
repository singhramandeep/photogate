package com.raman.photogate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class GateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun phone(): Pair<File, Gate> {
        val sd = tmp.newFolder("sdcard")
        return sd to Gate(File(sd, "DCIM/PhotoGate"))
    }

    private fun photo(dir: File, name: String, bytes: ByteArray = name.toByteArray()): File {
        dir.mkdirs()
        return File(dir, name).apply { writeBytes(bytes); setLastModified(1_700_000_000_000L) }
    }

    @Test
    fun stagingIsHiddenFromGalleries() {
        val (_, gate) = phone()
        gate.ensureDirs()
        assertTrue(gate.staging.name.startsWith("."))
        assertTrue(File(gate.staging, ".nomedia").exists())
        assertEquals(gate.root, gate.staging.parentFile)
    }

    @Test
    fun newPhotoLeavesCameraThenApprovedLandsInPhotoGate() {
        val (sd, gate) = phone()
        val cam = File(sd, "DCIM/Camera")
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val src = photo(cam, "IMG_1.jpg", bytes)

        val staged = gate.stage(src)
        assertFalse("left the camera folder", src.exists())
        assertEquals(gate.staging, staged.parentFile)
        assertArrayEquals(bytes, staged.readBytes())

        val approved = gate.approve(staged)
        assertFalse(staged.exists())
        assertEquals(File(sd, "DCIM/PhotoGate/IMG_1.jpg"), approved)
        assertArrayEquals(bytes, approved.readBytes())
        assertEquals("photo date is kept", 1_700_000_000_000L, approved.lastModified())
    }

    @Test
    fun sameNameNeverOverwrites() {
        val (sd, gate) = phone()
        val a = gate.approve(gate.stage(photo(File(sd, "DCIM/Camera"), "IMG.jpg", "first".toByteArray())))
        val b = gate.approve(gate.stage(photo(File(sd, "Pictures/Screenshots"), "IMG.jpg", "second".toByteArray())))
        assertEquals("IMG.jpg", a.name)
        assertEquals("IMG (1).jpg", b.name)
        assertEquals("first", a.readText())
        assertEquals("second", b.readText())
    }

    @Test
    fun uniqueHandlesNamesWithoutExtension() {
        val dir = tmp.newFolder("d")
        File(dir, "clip").writeText("x")
        assertEquals("clip (1)", Gate.unique(dir, "clip").name)
        File(dir, "a.b.mp4").writeText("x")
        assertEquals("a.b (1).mp4", Gate.unique(dir, "a.b.mp4").name)
    }

    @Test
    fun deleteRemovesStagedFile() {
        val (sd, gate) = phone()
        val s = gate.stage(photo(File(sd, "DCIM/Camera"), "x.jpg"))
        assertTrue(gate.delete(s))
        assertFalse(s.exists())
        assertTrue("already gone counts as deleted", gate.delete(s))
    }

    @Test(expected = IOException::class)
    fun missingSourceIsAnError() {
        val (sd, gate) = phone()
        gate.stage(File(sd, "DCIM/Camera/nope.jpg"))
    }

    @Test
    fun stagedSurvivesJsonRoundTrip() {
        val s = Staged(7, "/sd/DCIM/PhotoGate/.staging/a.jpg", "a.jpg", "DCIM/Camera/", true, 123, 456)
        assertEquals(s, Staged.fromJson(s.toJson()))
    }

    @Test
    fun dueAndNextDeletion() {
        val waiting = Staged(1, "p", "a", "f", false, 0)
        val soon = waiting.copy(id = 2, deleteAt = 1_000)
        val later = waiting.copy(id = 3, deleteAt = 5_000)
        val list = listOf(waiting, soon, later)
        assertEquals(listOf(soon), Gate.due(list, 2_000))
        assertEquals(listOf(soon, later), Gate.due(list, 5_000))
        assertEquals("waiting photos are never deleted", emptyList<Staged>(), Gate.due(listOf(waiting), Long.MAX_VALUE))
        assertEquals(1_000L, Gate.nextDue(list))
        assertNull(Gate.nextDue(listOf(waiting)))
    }
}
