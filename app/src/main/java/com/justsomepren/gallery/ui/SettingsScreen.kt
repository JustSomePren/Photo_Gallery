package com.justsomepren.gallery.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.data.GooglePhotos
import com.justsomepren.gallery.data.UploadTarget
import com.justsomepren.gallery.data.batteryFlow
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: GalleryViewModel) {
    val context = LocalContext.current
    val battery by remember { context.batteryFlow() }.collectAsStateWithLifecycle(null)
    val threshold by vm.prefs.threshold.collectAsStateWithLifecycle()
    val warmup by vm.prefs.warmup.collectAsStateWithLifecycle()
    val targets by vm.prefs.targets.collectAsStateWithLifecycle()
    var slider by remember(threshold) { mutableFloatStateOf(threshold.toFloat()) }
    var addingTarget by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Nastavení") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Baterie")
            battery?.let {
                Text("Baterie ${it.level} %" + if (it.charging) " · nabíjí se" else "")
            }
            Text(if (LocalSaver.current) "Úsporný režim je zapnutý" else "Úsporný režim je vypnutý")
            Text("Zapnout úsporný režim pod ${slider.roundToInt()} %")
            Slider(
                value = slider,
                onValueChange = { slider = it },
                onValueChangeFinished = { vm.prefs.setThreshold(slider.roundToInt()) },
                valueRange = 30f..100f,
                steps = 13,
            )
            Hint(
                "V úsporném režimu: černé pozadí, 60 Hz displej, menší náhledy, " +
                    "nové fotky se načtou až při dalším otevření. Zapne se i se systémovým spořičem."
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Připravovat náhledy jen při nabíjení", Modifier.weight(1f))
                Switch(warmup, onCheckedChange = vm::setWarmup)
            }

            HorizontalDivider()
            Section("Google Fotky")
            Hint(
                "Úpravy, hledání a přehrávání videí řeší Google Fotky. Aby na výletě nežraly baterku, " +
                    "nastav jim v Info o aplikaci → Baterie → Omezeno. Zálohu pak spustí, až je otevřeš."
            )
            SettingsButton("Omezit Google Fotky na pozadí") { GooglePhotos.appSettings(context) }
            SettingsButton("Systémový spořič baterie") { GooglePhotos.batterySaverSettings(context) }
            if (!GooglePhotos.canManageMedia(context)) {
                SettingsButton("Oblíbené bez potvrzování") { GooglePhotos.manageMediaSettings(context) }
            }

            HorizontalDivider()
            Section("Kam posílat fotky")
            Hint(
                "Každý cíl otevře danou aplikaci s vybranými fotkami. Google Disk se při nahrávání " +
                    "zeptá na účet. Google Fotky nahrávají do účtu, který je v nich právě aktivní."
            )
            targets.forEach { target ->
                ListItem(
                    headlineContent = { Text(target.name) },
                    supportingContent = { Text(target.packageName) },
                    trailingContent = {
                        IconButton(onClick = { vm.prefs.deleteTarget(target) }) { Icon(Icons.Default.Delete, "Smazat") }
                    },
                )
            }
            SettingsButton("Přidat cíl") { addingTarget = true }
        }
    }

    if (addingTarget) AddTargetDialog(vm) { addingTarget = false }
}

@Composable
private fun AddTargetDialog(vm: GalleryViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apps = remember { GooglePhotos.uploadApps(context) }
    var chosen by remember { mutableStateOf<UploadTarget?>(apps.firstOrNull()?.second) }

    TextInputDialog(
        title = "Nový cíl (např. Společný cloud)",
        confirm = "Přidat",
        onDismiss = onDismiss,
        extra = {
            LazyColumn(Modifier.heightIn(max = 300.dp).padding(top = 8.dp)) {
                items(apps) { (label, target) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { chosen = target },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(chosen == target, onClick = { chosen = target })
                        Text(label)
                    }
                }
            }
        },
    ) { name ->
        chosen?.let { vm.prefs.addTargets(listOf(it.copy(name = name))) }
        onDismiss()
    }
}

@Composable
private fun Section(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SettingsButton(text: String, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
