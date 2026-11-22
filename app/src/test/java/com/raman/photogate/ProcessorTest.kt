package com.raman.photogate

import com.raman.photogate.Processor.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessorTest {
    private val now = 1_800_000_000_000L
    private val cam = "DCIM/Camera/"

    private class I(val folder: String, val added: Long, val modified: Long)

    private fun item(folder: String = cam, addedAgoMs: Long = 10_000, modifiedAgoMs: Long = addedAgoMs) =
        I(folder, now - addedAgoMs, now - modifiedAgoMs)

    private fun decide(i: I, mode: String = "ask", windowMin: Int = 0, folders: Set<String> = setOf(cam)) =
        Processor.decide(i.folder, i.added, i.modified, folders, mode, windowMin * 60_000L, now)

    @Test fun asksAboutNewPhotoInWatchedFolder() = assertEquals(Action.ASK, decide(item()))
    @Test fun skipsOtherFolders() = assertEquals(Action.SKIP, decide(item(folder = "Pictures/Screenshots/")))
    @Test fun quietModeStillLandsInInbox() = assertEquals(Action.ASK_QUIET, decide(item(), mode = "none"))
    @Test fun allModeApprovesStraightAway() = assertEquals(Action.APPROVE, decide(item(), mode = "all"))

    @Test fun neverTouchesItsOwnFolder() {
        val all = setOf(cam, Gallery.FOLDER, Gallery.FOLDER + ".staging/")
        assertEquals(Action.SKIP, decide(item(folder = Gallery.FOLDER), folders = all))
        assertEquals(Action.SKIP, decide(item(folder = Gallery.FOLDER + ".staging/"), folders = all))
        assertEquals(Action.SKIP, decide(item(folder = "dcim/photogate/"), folders = all + "dcim/photogate/"))
    }

    @Test fun waitsWhileCameraIsStillWriting() =
        assertEquals(Action.WAIT, decide(item(addedAgoMs = 1_000)))

    @Test fun lateArrivalsLandInInboxQuietly() =
        assertEquals(Action.ASK_QUIET, decide(item(addedAgoMs = 10 * 60_000), windowMin = 5))

    @Test fun everyFinishedPhotoInAWatchedFolderEndsUpSomewhere() {
        for (mode in listOf("ask", "none", "all")) for (w in listOf(0, 1, 5, 60)) for (ago in listOf(4_000L, 3_600_000L, 86_000_000L)) {
            val a = decide(item(addedAgoMs = ago), mode = mode, windowMin = w)
            assert(a == Action.ASK || a == Action.ASK_QUIET || a == Action.APPROVE) { "$mode/$w/$ago -> $a" }
        }
    }

    @Test fun windowKeepsFreshArrivals() =
        assertEquals(Action.ASK, decide(item(addedAgoMs = 60_000), windowMin = 5))

    @Test fun longVideoCountsFromWhenRecordingStopped() =
        assertEquals(Action.ASK, decide(item(addedAgoMs = 20 * 60_000, modifiedAgoMs = 30_000), windowMin = 1))

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
