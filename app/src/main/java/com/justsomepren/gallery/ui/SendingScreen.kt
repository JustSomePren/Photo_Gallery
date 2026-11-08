package com.justsomepren.gallery.ui

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.data.CameraAuto
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

    // The phone's list of accounts, for a second and further account
    val chooseAccount = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        when {
            email == null -> loginError = "Účet se nevybral. V seznamu ho označ a potvrď tlačítkem OK."
            email in prefs.accounts.value -> loginError = "Účet $email už je přidaný, vyber jiný."
            else -> authorize(email, GoogleAuth.UPLOAD)
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (prefs.accounts.value.isEmpty()) {
            // Google's own list: one tap picks the first account
            authorize(null, GoogleAuth.UPLOAD)
        } else {
            // Google's list would quietly reuse the account it already allowed, so the phone's list picks another
            chooseAccount.launch(
                AccountManager.newChooseAccountIntent(
                    null, null, arrayOf("com.google"), "Vyber účet, který chceš přidat, a potvrď OK.", null, null, null,
                ),
            )
        }
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
            CameraAutoSection(vm)

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
            val default by prefs.defaultAccount.collectAsStateWithLifecycle()
            accounts.forEach { email ->
                val isDefault = email == (default?.takeIf { it in accounts } ?: accounts.firstOrNull())
                ListItem(
                    // The star makes the account the default for sending; it is offered first everywhere
                    leadingContent = {
                        IconButton(onClick = { prefs.setDefaultAccount(email) }) {
                            if (isDefault) Icon(Icons.Default.Star, "Výchozí účet", tint = MaterialTheme.colorScheme.primary)
                            else Icon(Icons.Default.StarBorder, "Nastavit jako výchozí")
                        }
                    },
                    headlineContent = { Text(email) },
                    supportingContent = {
                        if (email in problems) Text("Je potřeba znovu přihlásit", color = MaterialTheme.colorScheme.error)
                        else if (isDefault && accounts.size > 1) Text("Výchozí pro posílání")
                    },
                    trailingContent = {
                        Row {
                            if (email in problems) TextButton(onClick = { connect(email) }) { Text("Přihlásit") }
                            IconButton(onClick = { prefs.removeAccount(email) }) { Icon(Icons.Default.Delete, "Odebrat") }
                        }
                    },
                )
            }
            TextButton(onClick = {
                val intent = Intent(Settings.ACTION_ADD_ACCOUNT).putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
                else toast(context, "Přidej Google účet v Nastavení telefonu → Hesla a účty.")
            }) { Text("Přidat Google účet do telefonu") }
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

/** One switch for sending new camera photos by itself, with where they go. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CameraAutoSection(vm: GalleryViewModel) {
    val accounts by vm.prefs.accounts.collectAsStateWithLifecycle()
    val camera by vm.prefs.cameraAuto.collectAsStateWithLifecycle()
    val threshold by vm.prefs.threshold.collectAsStateWithLifecycle()
    // The album is typed freely and saved when the field is left or the switch changes
    var album by rememberSaveable(camera?.album) { mutableStateOf(camera?.album.orEmpty()) }

    Section("Nové fotky z fotoaparátu")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Posílat do Google Fotek automaticky", Modifier.weight(1f))
        Switch(
            checked = camera != null,
            enabled = accounts.isNotEmpty(),
            onCheckedChange = { on ->
                val account = vm.prefs.mainAccount
                vm.setCameraAuto(if (on && account != null) CameraAuto(account, album.ifBlank { null }, System.currentTimeMillis()) else null)
            },
        )
    }
    val current = camera
    if (accounts.isEmpty()) {
        Hint("Nejdřív přidej Google účet níže.")
    } else if (current == null) {
        Hint("Fotky a videa pořízené od zapnutí půjdou samy do zvoleného účtu. Starší fotky se neposílají.")
    } else {
        Text("Účet", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            vm.prefs.accountsDefaultFirst.forEach { email ->
                FilterChip(
                    selected = current.account == email,
                    onClick = { vm.setCameraAuto(current.copy(account = email)) },
                    label = { Text(email) },
                )
            }
        }
        OutlinedTextField(
            value = album,
            onValueChange = { album = it },
            label = { Text("Do alba (nepovinné)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                if (!focus.isFocused && album.ifBlank { null } != current.album) vm.setCameraAuto(current.copy(album = album.ifBlank { null }))
            },
        )
        Hint(
            "Posílá se jen přes Wi-Fi, nikdy přes mobilní data. Pod $threshold % baterie jen při nabíjení. " +
                "Aby se fotky neposílaly dvakrát, vypni zálohu v Google Fotkách (Nastavení Fotek → Zálohování).",
        )
    }
}
