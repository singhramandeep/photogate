package com.raman.photogate

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { Store.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.channels(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF16A34A))) {
                App(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // back from the All files access screen, or from anywhere else: refresh screens
        Store.refresh()
        if (Store.watch(this) && Gallery.hasAccess()) startWatching(this) // also restarts a refused boot start
        val app = applicationContext
        Thread {
            Processor.scan(app)
            Gallery.sweep(app) // deletions that came due while the phone was off
        }.start()
    }

    fun askAccess() {
        try {
            startActivity(Gallery.accessIntent(this))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        fun startWatching(c: Context) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, WatchService::class.java))
            } catch (e: IllegalStateException) {
                Toast.makeText(c, "Could not start the watcher: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        fun stopWatching(c: Context) {
            c.stopService(Intent(c, WatchService::class.java))
        }

        fun hasNotifications(c: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun App(act: MainActivity) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("Pending", "Folders", "Modes", "Settings")
    val marks = listOf("📥", "📁", "⏱", "⚙")
    Scaffold(
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(marks[i]) },
                        label = { Text(t) },
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> PendingScreen()
                1 -> FoldersScreen()
                2 -> ModesScreen()
                else -> SettingsScreen(act)
            }
        }
    }
}

/** Runs a file action off the main thread (moving a big video can take a moment). */
@Composable
private fun rememberRunner(): (suspend () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return { block -> scope.launch { withContext(Dispatchers.IO) { block() } } }
}

private fun timeLeft(ms: Long): String {
    val min = (ms / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> "under a minute"
        min < 60 -> "$min min"
        else -> "${min / 60} h ${min % 60} min"
    }
}

// ---------------------------------------------------------------- Pending

@Composable
fun PendingScreen() {
    val c = LocalContext.current
    val tick = Store.tick.intValue
    val run = rememberRunner()
    val all = remember(tick) { Store.staged(c).sortedByDescending { it.stagedAt } }
    val waiting = all.filter { !it.discarded }
    val discarded = all.filter { it.discarded }.sortedBy { it.deleteAt }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(2) }) {
            Column {
                Text("Pending", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${waiting.size} waiting in staging. Approved ones go to ${Gallery.FOLDER.trimEnd('/')}.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = waiting.isNotEmpty(), onClick = {
                        run { waiting.forEach { Gallery.approve(c, it.id) } }
                    }) { Text("Approve all") }
                    OutlinedButton(enabled = waiting.isNotEmpty(), onClick = {
                        run { waiting.forEach { Gallery.discard(c, it.id) } }
                    }) { Text("Discard all") }
                }
            }
        }
        items(waiting, key = { it.id }) { s ->
            StagedCell(s) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { run { Gallery.approve(c, s.id) } }) { Text("Approve") }
                    TextButton(onClick = { run { Gallery.discard(c, s.id) } }) { Text("Discard") }
                }
                TextButton(
                    onClick = { run { Gallery.deleteNow(c, s.id) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete now", color = MaterialTheme.colorScheme.error) }
            }
        }
        if (discarded.isNotEmpty()) {
            item(span = { GridItemSpan(2) }) {
                Column(Modifier.padding(top = 16.dp)) {
                    Text("Discarded", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Deleted from the phone when their time is up. Changed your mind? Approve still works.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { run { discarded.forEach { Gallery.deleteNow(c, it.id) } } }) {
                        Text("Delete all now", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            items(discarded, key = { it.id }) { s ->
                StagedCell(s) {
                    Text(
                        "Deleted in " + timeLeft(s.deleteAt - now),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { run { Gallery.approve(c, s.id) } }) { Text("Approve") }
                        TextButton(onClick = { run { Gallery.deleteNow(c, s.id) } }) {
                            Text("Delete now", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StagedCell(s: Staged, actions: @Composable () -> Unit) {
    var bmp by remember(s.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(s.id) {
        bmp = withContext(Dispatchers.IO) { Media.thumb(File(s.path), s.isVideo) }
    }
    Card {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                val b = bmp
                if (b != null) {
                    Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = s.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (s.isVideo) Text("▶ video", Modifier.align(Alignment.BottomStart).padding(6.dp), color = Color.White)
            }
            Text(
                Media.label(s.from),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
            )
            actions()
        }
    }
}

// ---------------------------------------------------------------- Folders

@Composable
fun FoldersScreen() {
    val c = LocalContext.current
    val tick = Store.tick.intValue
    var folders by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    val hasPerm = remember(tick) { Media.hasPermission(c) }
    LaunchedEffect(reload, hasPerm) {
        folders = withContext(Dispatchers.IO) {
            if (hasPerm) Media.folders(c).filter { !it.first.startsWith(Gallery.FOLDER, ignoreCase = true) }
            else emptyList()
        }
    }
    val on = remember(tick) { Store.folders(c) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Folders", style = MaterialTheme.typography.headlineMedium)
        Text("Which folders PhotoGate catches new photos from")
        Spacer(Modifier.height(8.dp))
        Text(
            "New photos and videos in the folders you switch on are moved into PhotoGate's hidden staging folder " +
                "and wait for your decision. A folder you add only catches what arrives from now on. " +
                "Careful with chat apps (WhatsApp...): moving their files away breaks them in the chat.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { reload++ }) { Text("Refresh folder list") }
        if (folders.isEmpty()) {
            Text(if (hasPerm) "No folders found." else "Allow All files access in Settings first.")
        }
        LazyColumn {
            items(folders, key = { it.first }) { (path, count) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(Media.label(path), style = MaterialTheme.typography.titleMedium)
                        Text("$path · $count items", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = path in on, onCheckedChange = { Store.setFolder(c, path, it) })
                }
                HorizontalDivider()
            }
        }
    }
}

// ---------------------------------------------------------------- Modes

@Composable
fun ModesScreen() {
    val c = LocalContext.current
    val tick = Store.tick.intValue
    var choice by remember { mutableStateOf("none") }
    var hours by remember { mutableIntStateOf(8) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        // a mode ends on its own; re-check so the screen does not keep saying it is running
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val until = remember(tick, now) { Store.modeUntil(c) }
    val current = remember(tick, now) { Store.mode(c) }
    val running = current != "ask"

    val options = listOf(
        Triple("none", "Leave everything alone", "New photos stay where they are. Nothing is staged or approved."),
        Triple("all", "Approve everything", "Stop asking me. Each photo goes straight to PhotoGate. Useful on a trip."),
    )
    val durations = listOf(2 to "2 hours", 8 to "8 hours", 24 to "1 day", 96 to "4 days", 336 to "14 days")

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Modes", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        if (running) {
            Text(
                (options.firstOrNull { it.first == current }?.second ?: current) + " until " +
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(until)),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(onClick = { Store.stopMode(c) }) { Text("Stop mode now") }
            Spacer(Modifier.height(16.dp))
        }
        Text("WHAT SHOULD HAPPEN", style = MaterialTheme.typography.labelMedium)
        options.forEach { (key, title, sub) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = choice == key, role = Role.RadioButton) { choice = key }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(sub, style = MaterialTheme.typography.bodySmall)
                }
                RadioButton(selected = choice == key, onClick = { choice = key })
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("FOR HOW LONG", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            durations.take(3).forEach { (h, label) ->
                FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text(label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            durations.drop(3).forEach { (h, label) ->
                FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text(label) })
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { Store.startMode(c, choice, hours * 3_600_000L) }) { Text("Start") }
        Spacer(Modifier.height(8.dp))
        Text("Modes always expire on their own, so you can't leave one running by accident.",
            style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------- Settings

@Composable
fun SettingsScreen(act: MainActivity) {
    val c = LocalContext.current
    val tick = Store.tick.intValue
    val watch = remember(tick) { Store.watch(c) }
    val window = remember(tick) { Store.windowMin(c) }
    val deleteAfter = remember(tick) { Store.deleteAfterMin(c) }
    val approved = remember(tick) { Store.approvedCount(c) }
    val deleted = remember(tick) { Store.deletedCount(c) }
    val hasAccess = remember(tick) { Gallery.hasAccess() }
    val hasNotif = remember(tick) { MainActivity.hasNotifications(c) }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("$approved approved · $deleted deleted", color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))

        SettingRow("All files access",
            if (hasAccess) "Granted." else "Needed to move new photos into PhotoGate. Opens a system screen: switch it on and come back.") {
            if (!hasAccess) Button(onClick = { act.askAccess() }) { Text("Allow") }
        }
        HorizontalDivider()

        if (!hasNotif) {
            SettingRow("Notifications", "So each new photo can ask Approve / Discard / Delete now.") {
                Button(onClick = { act.askNotifications() }) { Text("Allow") }
            }
            HorizontalDivider()
        }

        SettingRow("Watch for new photos",
            "Leaves a permanent notification. That's the price of catching a new photo right away.") {
            Switch(checked = watch, onCheckedChange = {
                Store.setWatch(c, it)
                if (it) {
                    if (!Gallery.hasAccess()) act.askAccess() else MainActivity.startWatching(c)
                } else MainActivity.stopWatching(c)
            })
        }
        HorizontalDivider()

        Spacer(Modifier.height(12.dp))
        Text("DELETE DISCARDED PHOTOS", style = MaterialTheme.typography.labelMedium)
        Text("How long a discarded photo stays in staging before it is deleted from the phone. Delete now is always there.",
            style = MaterialTheme.typography.bodySmall)
        val delays = listOf(0 to "Immediately", 60 to "1 hour", 240 to "4 hours", 480 to "8 hours", 1440 to "24 hours")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            delays.take(3).forEach { (m, label) ->
                FilterChip(selected = deleteAfter == m, onClick = { Store.setDeleteAfterMin(c, m) }, label = { Text(label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            delays.drop(3).forEach { (m, label) ->
                FilterChip(selected = deleteAfter == m, onClick = { Store.setDeleteAfterMin(c, m) }, label = { Text(label) })
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("DECISION WINDOW", style = MaterialTheme.typography.labelMedium)
        Text("Only photos taken within this window are caught. Anything older stays where it is.",
            style = MaterialTheme.typography.bodySmall)
        val windows = listOf(1 to "1 minute", 5 to "5 minutes", 15 to "15 minutes", 60 to "60 minutes", 0 to "No limit")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            windows.take(3).forEach { (m, label) ->
                FilterChip(selected = window == m, onClick = { Store.setWindowMin(c, m) }, label = { Text(label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            windows.drop(3).forEach { (m, label) ->
                FilterChip(selected = window == m, onClick = { Store.setWindowMin(c, m) }, label = { Text(label) })
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        SettingRow("Back up only PhotoGate",
            "In Google Photos: Settings > Backup > Back up device folders, switch on PhotoGate. " +
                "The folder appears there after your first approved photo.") {
            OutlinedButton(onClick = {
                val i = c.packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")
                if (i != null) c.startActivity(i)
                else Toast.makeText(c, "Google Photos is not installed", Toast.LENGTH_SHORT).show()
            }) { Text("Open") }
        }
    }
}

@Composable
private fun SettingRow(title: String, sub: String, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}
