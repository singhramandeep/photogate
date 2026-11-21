package com.raman.photogate

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            Store.setWatch(this, Store.watch(this)) // refresh screens
            if (Store.watch(this)) startWatching(this)
        }

    private val authLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
            try {
                val res = Identity.getAuthorizationClient(this)
                    .getAuthorizationResultFromIntent(r.data)
                Store.setConnected(this, res.accessToken != null)
            } catch (e: Exception) {
                Toast.makeText(this, "Google sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Media.init(this)
        Notifier.channels(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF16A34A))) {
                App(this)
            }
        }
    }

    fun askPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        )
    }

    fun connectGoogle() {
        Identity.getAuthorizationClient(this).authorize(Auth.request())
            .addOnSuccessListener { r ->
                if (r.hasResolution()) {
                    authLauncher.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build())
                } else {
                    Store.setConnected(this, true)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Google sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    companion object {
        fun startWatching(c: Context) {
            ContextCompat.startForegroundService(c, Intent(c, WatchService::class.java))
        }

        fun stopWatching(c: Context) {
            c.stopService(Intent(c, WatchService::class.java))
        }
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

// ---------------------------------------------------------------- Pending

@Composable
fun PendingScreen() {
    val c = LocalContext.current
    val tick = Store.tick.intValue
    val list = remember(tick) { Media.byIds(c, Store.pending(c)) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Pending", style = MaterialTheme.typography.headlineMedium)
        Text("${list.size} photos undecided", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = list.isNotEmpty(),
                onClick = {
                    list.forEach { Uploader.approve(c, it.id); Notifier.cancel(c, it.id) }
                },
            ) { Text("Upload all") }
            OutlinedButton(
                enabled = list.isNotEmpty(),
                onClick = {
                    list.forEach { Store.discard(c, it.id); Notifier.cancel(c, it.id) }
                },
            ) { Text("Discard all") }
        }
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(list, key = { it.id }) { item -> PendingCell(item) }
        }
    }
}

@Composable
fun PendingCell(item: Item) {
    val c = LocalContext.current
    val bmp by produceState<Bitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { Media.thumb(c, item.uri) }
    }
    Card {
        Column {
            val b = bmp
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { Uploader.approve(c, item.id); Notifier.cancel(c, item.id) }) {
                    Text("Upload")
                }
                TextButton(onClick = { Store.discard(c, item.id); Notifier.cancel(c, item.id) }) {
                    Text("Discard")
                }
            }
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
    LaunchedEffect(reload) {
        folders = withContext(Dispatchers.IO) { Media.folders(c) }
    }
    val on = remember(tick) { Store.folders(c) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Folders", style = MaterialTheme.typography.headlineMedium)
        Text("Which folders PhotoGate asks about")
        Spacer(Modifier.height(8.dp))
        Text(
            "New photos and videos in the folders you switch on ask before uploading. " +
                "A folder you add only asks about what arrives from now on. " +
                "Turn off the same folders in Google Photos' backup, or Google keeps uploading them all.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { reload++ }) { Text("Refresh folder list") }
        if (folders.isEmpty()) {
            Text("No folders found. Allow photo access in Settings first.")
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
    val until = remember(tick) { Store.modeUntil(c) }
    val running = remember(tick) { Store.mode(c) != "ask" }

    val options = listOf(
        Triple("none", "Don't upload anything", "Photos stay on the phone and never reach the cloud."),
        Triple("all", "Upload everything", "Stop asking me. Useful on a trip or at an event."),
        Triple("album", "Everything into a collection", "Each photo goes straight into your PhotoGate album."),
    )
    val durations = listOf(2 to "2 hours", 8 to "8 hours", 24 to "1 day", 96 to "4 days", 336 to "14 days")

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Modes", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        if (running) {
            Text(
                "Mode running until " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(until)),
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
    val connected = remember(tick) { Store.connected(c) }
    val albumOn = remember(tick) { Store.albumOn(c) }
    val window = remember(tick) { Store.windowMin(c) }
    val kept = remember(tick) { Store.discardedCount(c) }
    var albumName by remember { mutableStateOf(Store.albumName(c)) }
    val hasPerm = remember(tick) { Media.hasPermission(c) }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("$kept photos kept out of the cloud", color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))

        SettingRow("Access to your photos",
            if (hasPerm) "Granted." else "Needed to see what's in your folders.") {
            if (!hasPerm) Button(onClick = { act.askPermissions() }) { Text("Allow") }
        }
        HorizontalDivider()

        SettingRow("Watch the camera",
            "Leaves a permanent notification. That's the price of finding out about a new photo right away.") {
            Switch(checked = watch, onCheckedChange = {
                Store.setWatch(c, it)
                if (it) {
                    if (!Media.hasPermission(c)) act.askPermissions() else MainActivity.startWatching(c)
                } else MainActivity.stopWatching(c)
            })
        }
        HorizontalDivider()

        SettingRow("Google account",
            if (connected) "Connected, so approved photos upload straight to your library."
            else "Not connected. Photos can't upload until you connect.") {
            Button(onClick = { act.connectGoogle() }) { Text(if (connected) "Reconnect" else "Connect") }
        }
        HorizontalDivider()

        SettingRow("Collection",
            "Send approved photos into a Google Photos album created by this app.") {
            Switch(checked = albumOn, onCheckedChange = { Store.setAlbumOn(c, it) })
        }
        if (albumOn) {
            OutlinedTextField(
                value = albumName,
                onValueChange = { albumName = it; Store.setAlbumName(c, it) },
                label = { Text("Album name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HorizontalDivider()

        Spacer(Modifier.height(12.dp))
        Text("DECISION WINDOW", style = MaterialTheme.typography.labelMedium)
        Text("Only photos taken within this window are offered. Anything older stays on the phone without being uploaded.",
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
        SettingRow("Turn off Google Photos' own backup",
            "While it stays on, Google Photos uploads your whole camera roll no matter what you decide here.") {
            OutlinedButton(onClick = {
                val i = c.packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")
                if (i != null) c.startActivity(i)
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
