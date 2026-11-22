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
    fun approveMovesPhotoOutOfItsFolderIntoPhotoGate() {
        val (sd, gate) = phone()
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val original = photo(File(sd, "DCIM/Camera"), "IMG_1.jpg", bytes)

        val moved = gate.approve(original)
        assertFalse("left the camera folder", original.exists())
        assertEquals(File(sd, "DCIM/PhotoGate/IMG_1.jpg"), moved)
        assertArrayEquals(bytes, moved.readBytes())
        assertEquals("photo date is kept", 1_700_000_000_000L, moved.lastModified())
    }

    @Test
    fun copyFallbackKeepsExactlyOneFile() {
        val (sd, _) = phone()
        val src = photo(File(sd, "DCIM/Camera"), "v.mp4", ByteArray(1_000_000) { it.toByte() })
        val dst = File(sd, "DCIM/PhotoGate/v.mp4").also { it.parentFile!!.mkdirs() }
        Gate.copy(src, dst)
        assertTrue(src.delete())
        assertEquals(1_000_000L, dst.length())
        assertEquals(1_700_000_000_000L, dst.lastModified())
    }

    @Test
    fun noHiddenFolderIsCreated() {
        val (sd, gate) = phone()
        gate.approve(photo(File(sd, "DCIM/Camera"), "a.jpg"))
        assertFalse(File(gate.root, ".staging").exists())
        assertEquals(listOf("a.jpg"), gate.root.list()!!.toList())
    }

    @Test
    fun sameNameNeverOverwrites() {
        val (sd, gate) = phone()
        val a = gate.approve(photo(File(sd, "DCIM/Camera"), "IMG.jpg", "first".toByteArray()))
        val b = gate.approve(photo(File(sd, "Pictures/Screenshots"), "IMG.jpg", "second".toByteArray()))
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
    fun deleteRemovesOriginal() {
        val (sd, gate) = phone()
        val f = photo(File(sd, "DCIM/Camera"), "x.jpg")
        assertTrue(gate.delete(f))
        assertFalse(f.exists())
        assertTrue("already gone counts as deleted", gate.delete(f))
    }

    @Test(expected = IOException::class)
    fun missingOriginalIsAnError() {
        val (sd, gate) = phone()
        gate.approve(File(sd, "DCIM/Camera/nope.jpg"))
    }

    @Test
    fun photoSurvivesJsonRoundTrip() {
        val p = Photo(7, "/sd/DCIM/Camera/a.jpg", "a.jpg", "DCIM/Camera/", true, 123, 456, "Camera", auto = true)
        assertEquals(p, Photo.fromJson(p.toJson()))
        // records saved by older versions (no category / auto) still load
        val old = org.json.JSONObject("""{"id":1,"path":"/x","name":"x","from":"DCIM/Camera/","video":false,"at":5,"deleteAt":0}""")
        assertEquals(Photo(1, "/x", "x", "DCIM/Camera/", false, 5), Photo.fromJson(old))
    }

    @Test
    fun dueAndNextDeletion() {
        val waiting = Photo(1, "p", "a", "f", false, 0)
        val soon = waiting.copy(id = 2, deleteAt = 1_000)
        val later = waiting.copy(id = 3, deleteAt = 5_000)
        val list = listOf(waiting, soon, later)
        assertEquals(listOf(soon), Gate.due(list, 2_000))
        assertEquals(listOf(soon, later), Gate.due(list, 5_000))
        assertEquals("waiting photos are never deleted", emptyList<Photo>(), Gate.due(listOf(waiting), Long.MAX_VALUE))
        assertEquals(1_000L, Gate.nextDue(list))
        assertNull(Gate.nextDue(listOf(waiting)))
    }
}
