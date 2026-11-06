package com.justsomepren.gallery.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
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
    var slider by remember(threshold) { mutableFloatStateOf(threshold.toFloat()) }
    var fullAccess by remember { mutableStateOf(hasFullAccess(context)) }
    var canManageMedia by remember { mutableStateOf(GooglePhotos.canManageMedia(context)) }
    LifecycleResumeEffect(Unit) {
        canManageMedia = GooglePhotos.canManageMedia(context)
        fullAccess = hasFullAccess(context)
        onPauseOrDispose {}
    }

    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        fullAccess = hasFullAccess(context)
        vm.refresh()
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(vm.prefs.exportAlbums().toByteArray()) }
        }.isSuccess
        toast(context, if (ok) "Alba uložena" else "Uložení se nepovedlo")
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val count = runCatching {
            val json = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            vm.prefs.importAlbums(json)
        }.getOrNull()
        toast(context, if (count != null) "Načteno alb: $count" else "Soubor se nepodařilo načíst")
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Nastavení") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!fullAccess) {
                Section("Přístup k fotkám")
                Hint("Aplikace vidí jen fotky, které jsi vybral. Můžeš přidat další nebo povolit všechny.")
                SettingsButton("Změnit přístup k fotkám") { accessLauncher.launch(MEDIA_PERMISSIONS) }
                HorizontalDivider()
            }

            Section("Baterie")
            battery?.let {
                Text("Baterie ${it.level} %" + if (it.charging) " · nabíjí se" else "")
            }
            Text(if (LocalSaver.current) "Úsporný režim je zapnutý" else "Úsporný režim je vypnutý")
            Text("Zapnout úsporný režim pod ${slider.roundToInt()} %")
            Slider(
                value = slider,
                onValueChange = { slider = it },
                onValueChangeFinished = { vm.setThreshold(slider.roundToInt()) },
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
            if (!canManageMedia) {
                SettingsButton("Oblíbené bez potvrzování") { GooglePhotos.manageMediaSettings(context) }
            }

            HorizontalDivider()
            Section("Moje alba")
            Hint("Záloha vlastních alb do souboru, např. pro přenos na nový telefon.")
            SettingsButton("Uložit alba do souboru") { exportLauncher.launch("galerie-alba.json") }
            SettingsButton("Načíst alba ze souboru") { importLauncher.launch(arrayOf("application/json")) }

        }
    }

}

@Composable
fun AddTargetDialog(vm: GalleryViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apps = remember { GooglePhotos.uploadApps(context) }
    var chosenId by rememberSaveable { mutableStateOf(apps.firstOrNull()?.second?.appId) }
    val chosen = apps.map { it.second }.firstOrNull { it.appId == chosenId }

    TextInputDialog(
        title = "Nový cíl (např. Společný cloud)",
        confirm = "Přidat",
        onDismiss = onDismiss,
        extra = {
            LazyColumn(Modifier.heightIn(max = 300.dp).padding(top = 8.dp)) {
                items(apps) { (label, target) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { chosenId = target.appId },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(chosen == target, onClick = { chosenId = target.appId })
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

fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

@Composable
fun Section(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun SettingsButton(text: String, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
