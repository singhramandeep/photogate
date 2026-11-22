package com.raman.photogate

import androidx.exifinterface.media.ExifInterface
import app.cash.paparazzi.Paparazzi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DescribeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun mergeAddsCategoryOnce() {
        assertEquals("Screenshots", Describe.merge(null, "Screenshots"))
        assertEquals("Camera", Describe.merge("  ", "Camera"))
        assertEquals("Camera", Describe.merge("OLYMPUS DIGITAL CAMERA", "Camera"))
        assertEquals("Sunset at the lake | Family", Describe.merge("Sunset at the lake", "Family"))
        assertEquals("Sunset | Family", Describe.merge("Sunset | Family", "family"))
    }

    @Test
    fun onlyRewritableFormats() {
        assertTrue(Describe.supports(File("a.JPG")))
        assertTrue(Describe.supports(File("a.png")))
        assertFalse(Describe.supports(File("a.heic")))
        assertFalse(Describe.supports(File("a.mp4")))
        assertFalse(Describe.write(File(tmp.root, "clip.mp4").apply { writeText("x") }, "Camera"))
    }

    /** Smallest valid JPEG (1x1 px). */
    private fun jpeg(f: File): File {
        val hex = "FFD8FFE000104A46494600010100000100010000FFDB004300080606070605080707070909080A0C140D0C0B0B0C1912130F" +
            "141D1A1F1E1D1A1C1C20242E2720222C231C1C2837292C30313434341F27393D38323C2E333432FFC0000B080001000101011100" +
            "FFC4001F0000010501010101010100000000000000000102030405060708090A0BFFC400B5100002010303020403050504040000" +
            "017D01020300041105122131410613516107227114328191A1082342B1C11552D1F02433627282090A161718191A25262728292A" +
            "3435363738393A434445464748494A535455565758595A636465666768696A737475767778797A838485868788898A9293949596" +
            "9798999AA2A3A4A5A6A7A8A9AAB2B3B4B5B6B7B8B9BAC2C3C4C5C6C7C8C9CAD2D3D4D5D6D7D8D9DAE1E2E3E4E5E6E7E8E9EAF1F2" +
            "F3F4F5F6F7F8F9FAFFDA0008010100003F00FBD3FFD9"
        f.writeBytes(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        f.setLastModified(1_700_000_000_000L)
        return f
    }

    // loads Android's native runtime (layoutlib) so ExifInterface can run on the JVM
    @get:Rule
    val android = Paparazzi()

    @Test
    fun writesDescriptionIntoJpegAndKeepsDate() {
        val f = jpeg(File(tmp.root, "IMG_1.jpg"))
        assertTrue(Describe.write(f, "Screenshots"))
        assertEquals("Screenshots", ExifInterface(f).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
        assertEquals(1_700_000_000_000L, f.lastModified())
        // approving again (e.g. after a move between categories) does not repeat it
        assertTrue(Describe.write(f, "Screenshots"))
        assertEquals("Screenshots", ExifInterface(f).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
        assertTrue(Describe.write(f, "Family"))
        assertEquals("Screenshots | Family", ExifInterface(f).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
    }
}
