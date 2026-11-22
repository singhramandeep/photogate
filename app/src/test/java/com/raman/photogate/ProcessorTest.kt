package com.raman.photogate

import com.raman.photogate.Processor.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessorTest {
    private val now = 1_800_000_000_000L
    private val cam = "DCIM/Camera/"

    private fun decide(folder: String = cam, modifiedAgoMs: Long = 10_000, folders: Set<String> = setOf(cam)) =
        Processor.decide(folder, now - modifiedAgoMs, folders, now)

    @Test fun newPhotoInWatchedFolderGoesToInbox() = assertEquals(Action.ASK, decide())
    @Test fun skipsFoldersThatAreOff() = assertEquals(Action.SKIP, decide(folder = "Pictures/Screenshots/"))
    @Test fun waitsWhileCameraIsStillWriting() = assertEquals(Action.WAIT, decide(modifiedAgoMs = 1_000))

    @Test fun oldOrLateArrivalsStillGoToInbox() {
        for (ago in listOf(4_000L, 3_600_000L, 86_000_000L)) assertEquals(Action.ASK, decide(modifiedAgoMs = ago))
    }

    @Test fun neverTouchesItsOwnFolder() {
        val all = setOf(cam, Gallery.FOLDER, Gallery.FOLDER + "Camera/")
        assertEquals(Action.SKIP, decide(folder = Gallery.FOLDER, folders = all))
        assertEquals(Action.SKIP, decide(folder = Gallery.FOLDER + "Camera/", folders = all))
        assertEquals(Action.SKIP, decide(folder = "dcim/photogate/", folders = all + "dcim/photogate/"))
    }

    @Test fun skipReasonsAreSpecific() {
        assertEquals("folder Pictures/X/ is not switched on in Sources", Processor.skipReason("Pictures/X/", setOf(cam)))
        assertEquals("PhotoGate's own folder", Processor.skipReason(Gallery.FOLDER + "Camera/", setOf(cam)))
    }

    @Test fun pruneDropsOnlyEntriesOlderThanLookbackTwice() {
        val nowSec = now / 1000
        val m = mapOf(1L to nowSec, 2L to nowSec - Processor.LOOKBACK_SEC - 10, 3L to nowSec - 3 * Processor.LOOKBACK_SEC)
        assertEquals(setOf(1L, 2L), Processor.pruneHandled(m, nowSec).keys)
    }
}
