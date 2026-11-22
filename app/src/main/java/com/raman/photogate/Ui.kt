package com.raman.photogate

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

// ================================================================ theme

private val Light = lightColorScheme(
    primary = Color(0xFF4F46E5), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF), onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF0F766E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1), onSecondaryContainer = Color(0xFF042F2E),
    tertiary = Color(0xFFB45309), tertiaryContainer = Color(0xFFFEF3C7), onTertiaryContainer = Color(0xFF78350F),
    background = Color(0xFFF7F7FB), surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDEDF5), onSurfaceVariant = Color(0xFF55566B),
    error = Color(0xFFDC2626),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA5B4FC), onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF312E81), onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF5EEAD4), onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0xFF134E4A), onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFFFBBF24), tertiaryContainer = Color(0xFF78350F), onTertiaryContainer = Color(0xFFFEF3C7),
    background = Color(0xFF0F0F17), surface = Color(0xFF171723),
    surfaceVariant = Color(0xFF26263A), onSurfaceVariant = Color(0xFFB4B5C8),
    error = Color(0xFFF87171),
)

@Composable
fun PhotoGateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

// ================================================================ shell

/** What the screens need from the Activity (an interface so screens can be previewed without one). */
interface Actions {
    fun askAccess()
    fun askNotifications()
    fun openGallery()
    fun startWatching()
    fun stopWatching()
    fun scanNow()
}

/** Runs a file action off the main thread (moving a big video can take a moment). */
@Composable
private fun rememberRunner(): (suspend () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return { block -> scope.launch { withContext(Dispatchers.IO) { block() } } }
}

/** Current time, refreshed every 30 s so countdowns and mode expiry stay correct on screen. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun App(act: Actions) {
    val c = LocalContext.current
    val tick = Store.tick.intValue
    var tab by remember { mutableIntStateOf(0) }
    val all = remember(tick) { Store.staged(c) }
    val inbox = all.count { !it.discarded }
    val bin = all.count { it.discarded }
    val approved = remember(tick) { Store.approved(c).size }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Header()
            PrimaryScrollableTabRow(
                selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, edgePadding = 8.dp,
            ) {
                TabLabel("Inbox", inbox, tab == 0) { tab = 0 }
                TabLabel("Approved", approved, tab == 1, quiet = true) { tab = 1 }
                TabLabel("Bin", bin, tab == 2) { tab = 2 }
                TabLabel("Sources", 0, tab == 3) { tab = 3 }
                TabLabel("Settings", 0, tab == 4) { tab = 4 }
            }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> InboxScreen({ tab = 3 }, { tab = 4 })
                    1 -> ApprovedScreen()
                    2 -> BinScreen()
                    3 -> SourcesScreen()
                    else -> SettingsScreen(act)
                }
            }
        }
    }
}

@Composable
private fun TabLabel(text: String, count: Int, selected: Boolean, quiet: Boolean = false, onClick: () -> Unit) {
    Tab(selected = selected, onClick = onClick, text = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, maxLines = 1)
            if (count > 0) {
                Spacer(Modifier.width(6.dp))
                Badge(
                    containerColor = if (quiet) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
                    contentColor = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
                ) { Text("$count") }
            }
        }
    })
}

@Composable
private fun Header() {
    val c = LocalContext.current
    val tick = Store.tick.intValue
    val now = rememberNow()
    val watching = remember(tick) { Store.watch(c) && Gallery.hasAccess() }
    val sources = remember(tick) { Store.folders(c).size }
    val mode = remember(tick, now) { Store.mode(c) }

    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF4F46E5), Color(0xFF0F766E)))),
            contentAlignment = Alignment.Center,
        ) { Text("PG", color = Color.White, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("PhotoGate", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (watching) Color(0xFF16A34A) else MaterialTheme.colorScheme.outline)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (watching) "Watching $sources source${if (sources == 1) "" else "s"}" else "Paused",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (mode != "ask") {
            Pill(
                if (mode == "all") "Auto-approve on" else "Quiet mode",
                MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(50), color = bg, modifier = modifier) {
        Text(
            text, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun EmptyState(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Text("✓", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

// ================================================================ photo tiles

@Composable
private fun Thumb(p: Photo, px: Int, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    var bmp by remember(p.path, px) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(p.path, px) {
        bmp = withContext(Dispatchers.IO) { Media.thumb(File(p.path), p.isVideo, px) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let {
            Image(it.asImageBitmap(), p.name, Modifier.fillMaxSize(), contentScale = scale)
        }
    }
}

@Composable
private fun Tile(p: Photo, topTag: (@Composable () -> Unit)?, onClick: () -> Unit) {
    val c = LocalContext.current
    val tick = Store.tick.intValue
    val cat = remember(tick, p.from, p.category, p.path) {
        when {
            p.category.isNotEmpty() -> p.category
            p.path.contains("/" + Gallery.FOLDER) -> "PhotoGate" // approved before categories existed
            else -> Store.categoryOf(c, p.from)
        }
    }
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        Thumb(p, 400, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxWidth().height(44.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (p.isVideo) {
                Text("▶ ", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            Pill(cat, Color(0x33FFFFFF), Color.White)
        }
        if (topTag != null) Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { topTag() }
    }
}

// ================================================================ Inbox

@Composable
fun InboxScreen(goToSources: () -> Unit, goToSettings: () -> Unit = {}) {
    val c = LocalContext.current
    val tick = Store.tick.intValue
    val run = rememberRunner()
    val list = remember(tick) { Store.staged(c).filter { !it.discarded }.sortedByDescending { it.seenAt } }
    var reviewing by remember { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize()) {
    ModeBanner(goToSettings)
    if (list.isEmpty()) {
        EmptyState(
            "All clear",
            "New photos from your sources wait here for a decision. They stay in their folder until then. " +
                "Approved ones are in the Approved tab, discarded ones in the Bin.",
            "Choose sources", goToSources,
        )
    } else {
        PhotoGrid(list, header = {
            Text(
                "${list.size} waiting · tap one to review",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { run { list.forEach { Gallery.approve(c, it.id) } } }) { Text("Approve all") }
                OutlinedButton(onClick = { run { list.forEach { Gallery.discard(c, it.id) } } }) { Text("Discard all") }
            }
        }) { p -> Tile(p, null) { reviewing = p.id } }
    }
    }

    reviewing?.let { id ->
        Review(list, id, Kind.INBOX, onClose = { reviewing = null }, onMove = { reviewing = it })
    }
}

/** Shown on top of the Inbox while a quick mode runs, so it is never a surprise. */
@Composable
private fun ModeBanner(goToSettings: () -> Unit) {
    val c = LocalContext.current
    val tick = Store.tick.intValue
    val now = rememberNow()
    val mode = remember(tick, now) { Store.mode(c) }
    if (mode == "ask") return
    val until = remember(tick, now) { Store.modeUntil(c) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = goToSettings)) {
                Text(
                    if (mode == "all") "Auto-approve is on" else "Quiet mode is on",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    (if (mode == "all") "New photos go straight to Approved" else "New photos land here without notifications") +
                        " until " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            TextButton(onClick = { Store.stopMode(c) }) { Text("Stop") }
        }
    }
}

// ================================================================ Approved

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApprovedScreen() {
    val c = LocalContext.current
    val tick = Store.tick.intValue
    val all = remember(tick) { Store.approved(c).sortedByDescending { it.seenAt } }
    var filter by remember { mutableStateOf<String?>(null) }
    val cats = all.map { it.category.ifEmpty { "PhotoGate" } }.distinct().sorted()
    val list = all.filter { filter == null || it.category.ifEmpty { "PhotoGate" } == filter }
    var reviewing by remember { mutableStateOf<Long?>(null) }

    if (all.isEmpty()) {
        EmptyState(
            "Nothing approved yet",
            "Approved photos move to DCIM/PhotoGate/<category> and are listed here. Undo puts one back in the Inbox.",
        )
    } else {
        PhotoGrid(list, header = {
            Text(
                "${all.size} approved · in DCIM/PhotoGate, synced by your gallery",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (cats.size > 1) {
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
                    cats.forEach { cat ->
                        FilterChip(selected = filter == cat, onClick = { filter = cat }, label = { Text(cat) })
                    }
                }
            }
        }) { p ->
            Tile(p, topTag = if (p.auto) ({
                Pill("Auto", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            }) else null) { reviewing = p.id }
        }
    }

    reviewing?.let { id ->
        Review(list, id, Kind.APPROVED, onClose = { reviewing = null }, onMove = { reviewing = it })
    }
}

@Composable
private fun PhotoGrid(list: List<Photo>, header: @Composable () -> Unit, tile: @Composable (Photo) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column { header() } }
        items(list, key = { it.id }) { tile(it) }
    }
}

// ================================================================ Bin

@Composable
fun BinScreen() {
    val c = LocalContext.current
    val tick = Store.tick.intValue
    val run = rememberRunner()
    val now = rememberNow()
    val list = remember(tick) { Store.staged(c).filter { it.discarded }.sortedBy { it.deleteAt } }
    val after = remember(tick) { Store.deleteAfterMin(c) }
    var reviewing by remember { mutableStateOf<Long?>(null) }

    if (list.isEmpty()) {
        EmptyState(
            "Bin is empty",
            "Discarded photos wait here, still in their folder, until their timer runs out. " +
                "Timer: " + delayLabel(after) + " (change it in Settings).",
        )
    } else {
        PhotoGrid(list, header = {
            Text(
                "${list.size} discarded · each is deleted from the phone when its ⏱ timer runs out",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { run { list.forEach { Gallery.deleteNow(c, it.id) } } },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete all now") }
        }) { p ->
            Tile(p, topTag = { CountdownTag(p.deleteAt - now) }) { reviewing = p.id }
        }
    }

    reviewing?.let { id ->
        Review(list, id, Kind.BIN, onClose = { reviewing = null }, onMove = { reviewing = it })
    }
}

@Composable
private fun CountdownTag(msLeft: Long, long: Boolean = false) {
    Pill(
        "⏱ " + if (long) Categories.deletesIn(msLeft) else Categories.shortLeft(msLeft),
        MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer,
    )
}

// ================================================================ full-screen review

private enum class Kind { INBOX, APPROVED, BIN }

@Composable
private fun Review(list: List<Photo>, id: Long, kind: Kind, onClose: () -> Unit, onMove: (Long?) -> Unit) {
    val binMode = kind == Kind.BIN
    val c = LocalContext.current
    val run = rememberRunner()
    val now = rememberNow()
    val index = list.indexOfFirst { it.id == id }
    if (index < 0) {
        // acted on: go to the photo that took its place, or close when none are left
        LaunchedEffect(list) { onMove(list.firstOrNull()?.id) }
        return
    }
    val p = list[index]
    val next = list.getOrNull(index + 1)?.id ?: list.getOrNull(index - 1)?.id
    val cat = remember(p.from, p.category, Store.tick.intValue) { p.category.ifEmpty { Store.categoryOf(c, p.from) } }
    fun act(block: () -> Unit) {
        onMove(next)
        run { block() }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClose) { Text("Close", color = Color.White) }
                    Spacer(Modifier.weight(1f))
                    Text("${index + 1} / ${list.size}", color = Color(0xFFBBBBCC))
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = index > 0, onClick = { onMove(list[index - 1].id) }) {
                        Text("‹ Prev", color = if (index > 0) Color.White else Color.Gray)
                    }
                    TextButton(enabled = index < list.size - 1, onClick = { onMove(list[index + 1].id) }) {
                        Text("Next ›", color = if (index < list.size - 1) Color.White else Color.Gray)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Thumb(p, 1600, Modifier.fillMaxSize(), ContentScale.Fit)
                    if (binMode) Box(Modifier.align(Alignment.TopStart).padding(12.dp)) { CountdownTag(p.deleteAt - now, long = true) }
                }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(MaterialTheme.colorScheme.surface).padding(20.dp)
                ) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (kind == Kind.APPROVED) {
                            "In ${p.path.substringAfter(Gallery.FOLDER, p.path).let { Gallery.FOLDER + it }.substringBeforeLast('/')}" +
                                (if (p.auto) " · auto-approved" else "") + " · Undo puts it back in ${p.from.trimEnd('/')} and the Inbox"
                        } else {
                            "In ${p.from.trimEnd('/')} · Approve moves it to PhotoGate/$cat and adds \"$cat\" to its description"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    if (kind == Kind.APPROVED) {
                        FilledTonalButton(onClick = { act { Gallery.undoApprove(c, p.id) } }, modifier = Modifier.fillMaxWidth()) {
                            Text("Undo approval")
                        }
                        TextButton(onClick = { act { Gallery.deleteApproved(c, p.id) } }, modifier = Modifier.fillMaxWidth()) {
                            Text("Delete from phone", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { act { Gallery.approve(c, p.id) } }, modifier = Modifier.weight(1f)) {
                            Text("Approve")
                        }
                        if (binMode) {
                            FilledTonalButton(onClick = { act { Gallery.undiscard(c, p.id) } }, modifier = Modifier.weight(1f)) {
                                Text("Back to Inbox")
                            }
                        } else {
                            FilledTonalButton(onClick = { act { Gallery.discard(c, p.id) } }, modifier = Modifier.weight(1f)) {
                                Text("Discard")
                            }
                        }
                    }
                    TextButton(
                        onClick = { act { Gallery.deleteNow(c, p.id) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Delete now", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

// ================================================================ Sources

@Composable
fun SourcesScreen(preview: List<Pair<String, Int>>? = null) {
    val c = LocalContext.current
    val tick = Store.tick.intValue
    var folders by remember { mutableStateOf(preview ?: emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    val hasPerm = remember(tick) { Gallery.hasAccess() }
    LaunchedEffect(reload, hasPerm) {
        if (preview != null) return@LaunchedEffect
        folders = withContext(Dispatchers.IO) {
            if (hasPerm) Media.folders(c).filter { !it.first.startsWith(Gallery.FOLDER, ignoreCase = true) }
            else emptyList()
        }
    }
    val on = remember(tick) { Store.folders(c) }
    // tracked folders first, then by size
    val sorted = folders.sortedWith(compareByDescending<Pair<String, Int>> { it.first in on }.thenByDescending { it.second })

    if (!hasPerm) {
        EmptyState("No access yet", "Allow All files access in Settings so PhotoGate can see your folders.")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                "Turn on the folders to watch and give each one a category. Approved photos move to " +
                    "PhotoGate/<category>. A folder only catches what arrives after you turn it on.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { reload++ }) { Text("Refresh list") }
        }
        items(sorted, key = { it.first }) { (path, count) -> SourceCard(path, count, path in on) }
    }
}

@Composable
private fun SourceCard(path: String, count: Int, tracked: Boolean) {
    val c = LocalContext.current
    val tick = Store.tick.intValue
    val cat = remember(tick, path) { Store.categoryOf(c, path) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (tracked) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Media.label(path), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "$path · $count items",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = tracked, onCheckedChange = { Store.setFolder(c, path, it) })
            }
            if (tracked) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Category", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(10.dp))
                    CategoryPicker(cat) { Store.setCategory(c, path, it) }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "→ PhotoGate/$cat",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryPicker(current: String, onPick: (String) -> Unit) {
    val c = LocalContext.current
    val tick = Store.tick.intValue
    var open by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val options = remember(tick) { Store.categories(c) }
    Box {
        FilledTonalButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text("$current ▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(if (o == current) "✓  $o" else "    $o") },
                    onClick = { open = false; onPick(o) },
                )
            }
            DropdownMenuItem(text = { Text("+  New category…") }, onClick = { open = false; creating = true })
        }
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        val clean = Categories.clean(name)
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New category") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Name, e.g. Family or Work") },
                )
            },
            confirmButton = {
                TextButton(enabled = clean != null, onClick = { creating = false; clean?.let(onPick) }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}

// ================================================================ Settings

private fun delayLabel(min: Int) = when (min) {
    0 -> "immediately"
    60 -> "1 hour"
    else -> "${min / 60} hours"
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Line(title: String, sub: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, label) ->
            FilterChip(selected = selected == v, onClick = { onPick(v) }, label = { Text(label) })
        }
    }
}

@Composable
fun SettingsScreen(act: Actions) {
    val c = LocalContext.current
    val tick = Store.tick.intValue
    val now = rememberNow()
    val watch = remember(tick) { Store.watch(c) }
    val window = remember(tick) { Store.windowMin(c) }
    val deleteAfter = remember(tick) { Store.deleteAfterMin(c) }
    val approved = remember(tick) { Store.approvedCount(c) }
    val deleted = remember(tick) { Store.deletedCount(c) }
    val hasAccess = remember(tick) { Gallery.hasAccess() }
    val hasNotif = remember(tick) { runCatching { MainActivity.hasNotifications(c) }.getOrDefault(true) }
    val mode = remember(tick, now) { Store.mode(c) }
    val until = remember(tick, now) { Store.modeUntil(c) }
    var modeChoice by remember { mutableStateOf<String?>(null) } // nothing preselected: a mode never starts by accident
    var hours by remember { mutableIntStateOf(8) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("$approved", "approved", Modifier.weight(1f))
            Stat("$deleted", "deleted", Modifier.weight(1f))
        }

        Section("Permissions") {
            Line("All files access", if (hasAccess) "Granted" else "Needed to move approved photos and delete discarded ones") {
                if (!hasAccess) Button(onClick = { act.askAccess() }) { Text("Allow") }
                else Text("✓", color = MaterialTheme.colorScheme.secondary)
            }
            Line("Notifications", if (hasNotif) "Granted" else "So each new photo can ask right away") {
                if (!hasNotif) Button(onClick = { act.askNotifications() }) { Text("Allow") }
                else Text("✓", color = MaterialTheme.colorScheme.secondary)
            }
        }

        Section("Watching") {
            Line("Watch for new photos", "Keeps a small permanent notification so new photos are caught right away") {
                Switch(checked = watch, onCheckedChange = {
                    Store.setWatch(c, it)
                    if (it) {
                        if (!Gallery.hasAccess()) act.askAccess() else act.startWatching()
                    } else act.stopWatching()
                })
            }
            Spacer(Modifier.height(8.dp))
            Text("Notify only for photos newer than", style = MaterialTheme.typography.titleSmall)
            Text("Older ones (e.g. noticed late) still land in the Inbox, just quietly.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Choices(
                listOf(1 to "1 min", 5 to "5 min", 15 to "15 min", 60 to "1 hour", 0 to "No limit"),
                window,
            ) { Store.setWindowMin(c, it) }
        }

        Section("Bin timer") {
            Text("Discarded photos are deleted from the phone after", style = MaterialTheme.typography.bodyMedium)
            Choices(
                listOf(0 to "Immediately", 60 to "1 h", 240 to "4 h", 480 to "8 h", 1440 to "24 h"),
                deleteAfter,
            ) { Store.setDeleteAfterMin(c, it) }
            Text(
                "Applies to photos discarded from now on. Delete now is always there.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Quick modes") {
            if (mode != "ask") {
                Text(
                    (if (mode == "all") "Auto-approve" else "Quiet mode") + " until " +
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(until)),
                    style = MaterialTheme.typography.titleSmall,
                )
                OutlinedButton(onClick = { Store.stopMode(c) }) { Text("Stop now") }
            } else {
                Choices(listOf<Pair<String?, String>>("all" to "Auto-approve everything", "none" to "Quiet mode"), modeChoice) {
                    modeChoice = if (modeChoice == it) null else it
                }
                Text(
                    when (modeChoice) {
                        "all" -> "Every new photo moves straight to its PhotoGate folder and shows up under Approved. Handy on a trip."
                        "none" -> "New photos still land in the Inbox, just without notifications."
                        else -> "Pick a mode to run for a while. Nothing is on right now: every new photo asks."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Choices(listOf(2 to "2 h", 8 to "8 h", 24 to "1 day", 96 to "4 days", 336 to "14 days"), hours) { hours = it }
                Spacer(Modifier.height(6.dp))
                Button(enabled = modeChoice != null, onClick = {
                    modeChoice?.let { Store.startMode(c, it, hours * 3_600_000L) }
                    modeChoice = null
                }) { Text("Start") }
                Text("Modes switch themselves off when the time is up.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Section("Activity") {
            val lines = remember(tick) { Store.logLines(c).takeLast(25).reversed() }
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { act.scanNow() }) { Text("Scan now") }
                OutlinedButton(onClick = {
                    clip.setText(androidx.compose.ui.text.AnnotatedString(Store.logLines(c).joinToString("\n")))
                }) { Text("Copy") }
                TextButton(onClick = { Store.clearLog(c) }) { Text("Clear") }
            }
            Spacer(Modifier.height(6.dp))
            if (lines.isEmpty()) {
                Text("Nothing yet. Take a photo, then come back here.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            lines.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    modifier = Modifier.padding(vertical = 2.dp))
            }
        }

        Section("Sync to Google Photos") {
            Text(
                "Turn off Google Photos' own backup. In Samsung Gallery: Settings > Sync with Google Photos > " +
                    "choose albums: switch on only your PhotoGate category folders (they appear after the first " +
                    "approved photo in each). Leave Camera off.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { act.openGallery() }) { Text("Open gallery") }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Card(
        modifier = modifier, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
