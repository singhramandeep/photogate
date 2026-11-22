package com.raman.photogate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** First-launch guide: what PhotoGate does, permissions, folders, how often to check, and the sync setup. */
@Composable
fun Onboarding(act: Actions, startPage: Int = 0, onDone: () -> Unit) {
    val c = LocalContext.current
    val pages = 6
    val pager = rememberPagerState(initialPage = startPage) { pages }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages - 1

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${pager.currentPage + 1} of $pages", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
                if (!last) TextButton(onClick = { Scheduler.apply(c); onDone() }) { Text("Skip") }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> Welcome()
                    1 -> HowItWorks()
                    2 -> Page("Permissions", "PhotoGate needs these to do its job. Nothing leaves your phone through PhotoGate.") {
                        PermissionLines(act)
                    }
                    3 -> Column(Modifier.fillMaxSize()) {
                        PageTitle("Choose folders", "Switch on the folders to watch and pick a category for each.")
                        Box(Modifier.weight(1f)) { SourcesScreen() }
                    }
                    4 -> Page("How often to check", "Immediately is best for the camera. Less often means fewer notifications.") {
                        val freq = Store.checkFreqMin(c).also { Store.tick.intValue }
                        Choices(Scheduler.CHOICES, freq) {
                            Store.setCheckFreqMin(c, it)
                            Scheduler.apply(c)
                        }
                    }
                    else -> Page("Sync to Google Photos", "One-time setup, so only what you upload reaches the cloud.") {
                        Step(1, "Google Photos app > Backup: turn it off.")
                        Step(2, "Upload one photo in PhotoGate, so the PhotoGate folders exist.")
                        Step(3, "Samsung Gallery > Settings > Sync with Google Photos: switch on only the p-folders (pCamera, pScreenshots...), leave Camera off.")
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { act.openGallery() }) { Text("Open gallery") }
                        Spacer(Modifier.height(16.dp))
                        Text("Tips on each screen show the rest: tap to review, long-press to select, pinch to resize.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pager.currentPage > 0) {
                    TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) { Text("Back") }
                } else Spacer(Modifier.width(64.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                    repeat(pages) { i ->
                        Box(
                            Modifier.padding(3.dp).size(if (i == pager.currentPage) 10.dp else 7.dp).clip(CircleShape)
                                .background(
                                    if (i == pager.currentPage) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    }
                }
                Button(onClick = {
                    if (last) {
                        Scheduler.apply(c)
                        onDone()
                    } else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                }) { Text(if (last) "Start" else "Next") }
            }
        }
    }
}

@Composable
private fun PageTitle(title: String, sub: String) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Page(title: String, sub: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle(title, sub)
        Column(Modifier.padding(horizontal = 24.dp)) { content() }
    }
}

@Composable
private fun Welcome() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Logo(88)
        Spacer(Modifier.height(24.dp))
        Text("Welcome to PhotoGate", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "You decide which photos reach Google Photos. Everything else stays on your phone, or goes in the Bin.",
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HowItWorks() {
    Page("How it works", "Every new photo in a folder you watch goes to your Inbox. For each one:") {
        Choice("⬆", "Upload", "Moves it to PhotoGate/pCamera (or its category). Your gallery syncs that folder to Google Photos.",
            MaterialTheme.colorScheme.primaryContainer)
        Choice("🗑", "Bin", "Waits in the Bin with a countdown, then it is deleted from the phone. You can still change your mind.",
            MaterialTheme.colorScheme.tertiaryContainer)
        Choice("✕", "Delete now", "Gone from the phone right away.", MaterialTheme.colorScheme.errorContainer)
        Spacer(Modifier.height(8.dp))
        Text("Until you decide, the photo stays exactly where it is.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Choice(mark: String, title: String, body: String, bg: androidx.compose.ui.graphics.Color) {
    Card(
        shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = bg),
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(mark, style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(36.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) { Text("$n", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium) }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
