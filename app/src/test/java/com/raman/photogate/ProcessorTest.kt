package com.raman.photogate

import com.raman.photogate.Processor.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessorTest {
    private val now = 1_800_000_000_000L
    private val cam = "DCIM/Camera/"

    private class I(val folder: String, val added: Long, val modified: Long)

    private fun item(folder: String = cam, addedAgoMs: Long = 2_000, modifiedAgoMs: Long = addedAgoMs) =
        I(folder, now - addedAgoMs, now - modifiedAgoMs)

    private fun decide(i: I, mode: String = "ask", albumOn: Boolean = false, windowMin: Int = 0) =
        Processor.decide(i.folder, i.added, i.modified, setOf(cam), mode, albumOn, windowMin * 60_000L, now)

    @Test fun asksForWatchedFolder() = assertEquals(Action.ASK, decide(item()))
    @Test fun skipsOtherFolders() = assertEquals(Action.SKIP, decide(item(folder = "Pictures/Screenshots/")))
    @Test fun noneModeKeepsOnPhone() = assertEquals(Action.SKIP, decide(item(), mode = "none"))
    @Test fun allModeUploads() = assertEquals(Action.UPLOAD, decide(item(), mode = "all"))
    @Test fun allModeRespectsCollectionSwitch() = assertEquals(Action.UPLOAD_ALBUM, decide(item(), mode = "all", albumOn = true))
    @Test fun albumModeAlwaysAlbum() = assertEquals(Action.UPLOAD_ALBUM, decide(item(), mode = "album"))

    @Test fun windowSkipsOldArrivals() =
        assertEquals(Action.SKIP, decide(item(addedAgoMs = 10 * 60_000), windowMin = 5))

    @Test fun windowKeepsFreshArrivals() =
        assertEquals(Action.ASK, decide(item(addedAgoMs = 60_000), windowMin = 5))

    @Test fun longVideoCountsFromWhenRecordingStopped() =
        // added 20 min ago when recording began, finished 30 s ago
        assertEquals(Action.ASK, decide(item(addedAgoMs = 20 * 60_000, modifiedAgoMs = 30_000), windowMin = 1))

    @Test fun pruneDropsOnlyEntriesOlderThanLookbackTwice() {
        val nowSec = now / 1000
        val m = mapOf(1L to nowSec, 2L to nowSec - Processor.LOOKBACK_SEC - 10, 3L to nowSec - 3 * Processor.LOOKBACK_SEC)
        assertEquals(setOf(1L, 2L), Processor.pruneHandled(m, nowSec).keys)
    }
}
