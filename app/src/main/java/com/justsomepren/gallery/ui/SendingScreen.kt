package com.justsomepren.gallery.ui

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.data.Event
import com.justsomepren.gallery.data.CloudWorker
import com.justsomepren.gallery.data.GoogleAuth
import com.justsomepren.gallery.data.UploadWorker
import com.justsomepren.gallery.data.formatRange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendingScreen(vm: GalleryViewModel) {
    val context = LocalContext.current
    val prefs = vm.prefs
    val queue by prefs.queue.collectAsStateWithLifecycle()
    val failed by prefs.failed.collectAsStateWithLifecycle()
    val uploading by prefs.uploading.collectAsStateWithLifecycle()
    val events by prefs.events.collectAsStateWithLifecycle()
    val accounts by prefs.accounts.collectAsStateWithLifecycle()
    val problems by prefs.accountProblems.collectAsStateWithLifecycle()
    val mobileData by prefs.mobileData.collectAsStateWithLifecycle()
    val targets by prefs.targets.collectAsStateWithLifecycle()
    val threshold by prefs.threshold.collectAsStateWithLifecycle()
    var addingTarget by rememberSaveable { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }

    val authorize = rememberGoogleConsent(
        vm,
        onGranted = { email ->
            prefs.addAccount(email)
            UploadWorker.schedule(context, restart = true)
            CloudWorker.schedule(context)
            loginError = null
        },
        onError = { loginError = it },
    )

    fun connect(email: String) = authorize(email, GoogleAuth.UPLOAD)

    val chooseAccount = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.let(::connect)
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        chooseAccount.launch(AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null))
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Odesílání") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Fronta")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        queueStatus(queue.size, failed.size, uploading, mobileData) ?: "Vše odesláno",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    queue.groupBy { it.account to it.album }.forEach { (where, items) ->
                        Text("${where.first}${where.second?.let { " · $it" } ?: ""}: ${items.size}")
                    }
                    if (queue.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(queue.take(30), key = { it.sentKey }) {
                                Thumbnail(Uri.parse(it.uri), gridThumbPx(), Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)))
                            }
                        }
                    }
                    Hint("Odešle se samo na Wi-Fi. Pod $threshold % baterie jen při nabíjení.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::sendNow, enabled = queue.isNotEmpty() && uploading == null) { Text("Odeslat hned") }
                        OutlinedButton(onClick = prefs::clearQueue, enabled = queue.isNotEmpty()) { Text("Vyprázdnit") }
                    }
                    if (failed.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${failed.size} se nepodařilo odeslat", Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = vm::retryFailed) { Text("Zkusit znovu") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Posílat i přes mobilní data", Modifier.weight(1f))
                        Switch(mobileData, onCheckedChange = vm::setMobileData)
                    }
                }
            }

            HorizontalDivider()
            SectionWithAdd("Události") { vm.newEvent() }
            Hint("Barevné období v kalendáři. Může samo posílat všechny fotky z těch dní do účtu a alba.")
            events.sortedByDescending { it.startDay }.forEach { EventRow(it) { vm.editingEvent = it } }

            HorizontalDivider()
            SectionWithAdd("Google účty") { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
            Hint("Do těchto účtů se fotky z fronty nahrávají do Google Fotek automaticky.")
            // Google lets an app log in to Photos only after its owner registered it once
            if (accounts.isEmpty()) {
                Hint("Před prvním účtem je potřeba jednou nastavit Google Cloud (README, Automatické odesílání – nastavení), jinak Google přihlášení nepustí.")
            }
            accounts.forEach { email ->
                ListItem(
                    headlineContent = { Text(email) },
                    supportingContent = {
                        if (email in problems) Text("Je potřeba znovu přihlásit", color = MaterialTheme.colorScheme.error)
                    },
                    trailingContent = {
                        Row {
                            if (email in problems) TextButton(onClick = { connect(email) }) { Text("Přihlásit") }
                            IconButton(onClick = { prefs.removeAccount(email) }) { Icon(Icons.Default.Delete, "Odebrat") }
                        }
                    },
                )
            }
            loginError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            HorizontalDivider()
            SectionWithAdd("Poslat ručně přes aplikaci") { addingTarget = true }
            Hint("Otevře aplikaci s vybranými fotkami. Google Disk se zeptá na účet.")
            targets.forEach { target ->
                ListItem(
                    headlineContent = { Text(target.name) },
                    supportingContent = { Text(target.packageName) },
                    trailingContent = {
                        IconButton(onClick = { prefs.deleteTarget(target) }) { Icon(Icons.Default.Delete, "Smazat") }
                    },
                )
            }
        }
    }

    if (addingTarget) AddTargetDialog(vm) { addingTarget = false }
}

@Composable
private fun SectionWithAdd(title: String, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Section(title) }
        IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Přidat") }
    }
}

@Composable
private fun EventRow(event: Event, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Box(Modifier.size(16.dp).background(Color(event.color), CircleShape)) },
        headlineContent = { Text(event.name + if (event.isActive) " · probíhá" else "") },
        supportingContent = {
            val sending = event.account?.takeIf { event.autoSend }?.let { " → $it" + (event.album?.let { a -> " · $a" } ?: "") }
            val from = if (event.isLinked) " · z Kalendáře" else ""
            Text(formatRange(event.startDay, event.endDay) + from + (sending ?: ""))
        },
        trailingContent = {
            if (event.autoSend) Icon(Icons.Default.CloudSync, "Posílá automaticky", tint = MaterialTheme.colorScheme.primary)
        },
    )
}
