package com.raman.photogate

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Changes to photos that belong to other apps (the camera's, for example). Android asks the user first
 * (MediaStore.createWriteRequest / createDeleteRequest); with "Media management" (Android 12+) it agrees without
 * showing anything. Asking needs an Activity on screen, so every change goes through one: MainActivity, or
 * [ActionActivity] for notification buttons. Create it as a property of the Activity (it registers a result launcher).
 */
class Edits(private val activity: ComponentActivity) {
    private val app get() = activity.applicationContext
    private var then: ((Boolean) -> Unit)? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val t = then
        then = null
        t?.invoke(r.resultCode == Activity.RESULT_OK)
    }

    /** Asks for write (or delete) access to [uris]; [after] gets true if Android said yes. */
    private fun ask(uris: List<Uri>, delete: Boolean, after: (Boolean) -> Unit) {
        if (uris.isEmpty()) {
            after(true)
            return
        }
        try {
            val cr = activity.contentResolver
            val pi = if (delete) MediaStore.createDeleteRequest(cr, uris) else MediaStore.createWriteRequest(cr, uris)
            then = after
            launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (e: Exception) {
            // e.g. a photo was deleted meanwhile: tidy up the lists and give up on this one
            Store.log(app, "Android refused the request: $e")
            io({ Gallery.tidy(app) }) { after(false) }
        }
    }

    private fun io(work: () -> Unit, done: () -> Unit = {}) {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching(work).onFailure { Store.log(app, "Failed: $it") } }
            done()
        }
    }

    private fun declined(what: String, done: () -> Unit) {
        Store.log(app, "$what: not allowed by Android (declined)")
        done()
    }

    fun upload(ids: List<Long>, done: () -> Unit = {}) =
        ask(Gallery.stagedUris(app, ids), delete = false) { ok ->
            if (ok) io({ ids.forEach { Gallery.upload(app, it) } }, done) else declined("Upload", done)
        }

    fun undoUpload(ids: List<Long>, done: () -> Unit = {}) =
        ask(Gallery.uploadedUris(app, ids), delete = false) { ok ->
            if (ok) io({ ids.forEach { Gallery.undoUpload(app, it) } }, done) else declined("Undo upload", done)
        }

    fun deleteStaged(ids: List<Long>, done: () -> Unit = {}) {
        val ok = ids.filter { Store.stagedById(app, it)?.let { p -> Gallery.uriOf(app, p) } != null }
        ask(Gallery.stagedUris(app, ok), delete = true) { yes ->
            if (yes) io({ Gallery.deleted(app, ok) }, done) else declined("Delete", done)
        }
    }

    fun deleteUploaded(ids: List<Long>, done: () -> Unit = {}) {
        val ok = ids.filter { Store.approvedById(app, it)?.let { p -> Gallery.uriOf(app, p) } != null }
        ask(Gallery.uploadedUris(app, ok), delete = true) { yes ->
            if (yes) io({ Gallery.deleted(app, ok) }, done) else declined("Delete", done)
        }
    }

    /** Bin: wait for the timer, or delete at once when the timer is "Immediately". */
    fun bin(ids: List<Long>, done: () -> Unit = {}) {
        val now = ids.filter { Gallery.bin(app, it) }
        if (now.isEmpty()) done() else deleteStaged(now, done)
    }

    /** Deletes Bin photos whose timer ran out. */
    fun sweep(done: () -> Unit = {}) {
        val due = Gallery.dueIds(app)
        if (due.isEmpty()) done() else deleteStaged(due, done)
    }
}

/** Invisible screen behind the notification buttons (Upload / Bin / Delete now / delete what's due in the Bin). */
class ActionActivity : ComponentActivity() {
    private val edits = Edits(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish() // recreated mid-request: nothing sensible left to do
            return
        }
        val id = intent.getLongExtra("id", -1L)
        val done = { finish() }
        when (intent.action) {
            Notifier.ACTION_UPLOAD -> edits.upload(listOf(id), done)
            Notifier.ACTION_DISCARD -> edits.bin(listOf(id), done)
            Notifier.ACTION_DELETE -> edits.deleteStaged(listOf(id), done)
            Notifier.ACTION_SWEEP -> edits.sweep(done)
            else -> finish()
        }
    }
}
