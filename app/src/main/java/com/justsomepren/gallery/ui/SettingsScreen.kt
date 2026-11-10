package com.justsomepren.gallery.ui

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.data.GoogleAuth
import com.justsomepren.gallery.data.GooglePhotos
import com.justsomepren.gallery.data.Takeout
import com.justsomepren.gallery.data.WebPhotos
import com.justsomepren.gallery.data.UploadTarget
import com.justsomepren.gallery.data.batteryFlow
import kotlinx.coroutines.launch
import com.justsomepren.gallery.data.onWifi
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: GalleryViewModel) {
    val context = LocalContext.current
    val battery by remember { context.batteryFlow() }.collectAsStateWithLifecycle(null)
    val threshold by vm.prefs.threshold.collectAsStateWithLifecycle()
    val warmup by vm.prefs.warmup.collectAsStateWithLifecycle()
    val groupByDay by vm.prefs.groupByDay.collectAsStateWithLifecycle()
    val trips by vm.prefs.trips.collectAsStateWithLifecycle()
    val tripSearch by vm.prefs.tripSearch.collectAsStateWithLifecycle()
    val tripResult by vm.tripResult.collectAsStateWithLifecycle()
    var showingTrips by rememberSaveable { mutableStateOf(false) }
    var keepFavorites by rememberSaveable { mutableStateOf(true) }
    var choosingFreeUp by rememberSaveable { mutableStateOf(false) }
    val freeUp by vm.freeUp.collectAsStateWithLifecycle()
    val freeUpChecking by vm.freeUpChecking.collectAsStateWithLifecycle()
    val freeUpError by vm.freeUpError.collectAsStateWithLifecycle()
    val allowCheck = rememberGoogleConsent(
        vm,
        onGranted = { vm.checkFreeUp(keepFavorites) },
        onError = { toast(context, it) },
    )
    val scope = rememberCoroutineScope()
    val timelineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val filled = runCatching { vm.importTimeline(uri) }.getOrNull()
            toast(context, if (filled != null) "Poloha doplněna u $filled fotek, hledám výlety…" else "Soubor se nepodařilo přečíst")
        }
    }
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
            context.contentResolver.openOutputStream(uri)!!.use { it.write(vm.prefs.exportAll().toByteArray()) }
        }.isSuccess
        toast(context, if (ok) "Záloha uložena" else "Uložení se nepovedlo")
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val full = runCatching {
            val json = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            vm.prefs.restore(json)
        }.getOrNull()
        when (full) {
            null -> toast(context, "Soubor se nepodařilo načíst")
            false -> toast(context, "Alba načtena")
            true -> {
                toast(context, "Záloha načtena")
                restartApp(context)
            }
        }
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

            Section("Zobrazení")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Seskupovat fotky po dnech", Modifier.weight(1f))
                Switch(groupByDay, onCheckedChange = vm.prefs::setGroupByDay)
            }
            Hint("Vypnuto: jen po měsících, víc fotek na obrazovce.")

            HorizontalDivider()
            CloudSection(vm)

            HorizontalDivider()
            Section("Výlety")
            Hint(
                "Projde, kde byly fotky z fotoaparátu pořízené, a navrhne výlety – i ty staré. " +
                    "Vše jen v telefonu. Poprvé to může pár minut trvat, pak už jen nové fotky."
            )
            val search = tripSearch
            if (search != null) {
                Text(if (search.second == 0) "Hledám…" else "Čtu polohu fotek ${search.first} / ${search.second}")
            } else {
                SettingsButton("Najít výlety") { vm.findTrips() }
            }
            if (trips.isNotEmpty() && search == null) {
                SettingsButton("Návrhy výletů: ${trips.size}") { showingTrips = true }
            }
            tripResult?.let { Hint(it) }
            Hint(
                "Fotky bez polohy? Pokud máš zapnutou Časovou osu Google, exportuj ji v telefonu " +
                    "(Nastavení → Poloha → Časová osa → Exportovat) a načti ji sem. Poloha se doplní podle času fotky."
            )
            SettingsButton("Načíst Časovou osu (Timeline.json)") { timelineLauncher.launch(arrayOf("application/json", "*/*")) }

            HorizontalDivider()
            Section("Uvolnit místo")
            Hint(
                "Smaže z telefonu jen fotky a videa, které tahle aplikace nahrála do Google Fotek a které tam " +
                    "teď ověřeně jsou: Google potvrdí, že tam položka je, se stejným názvem, a soubor v telefonu se " +
                    "od nahrání nezměnil. Potřebuje internet. Fotky zálohované samotnými Google Fotkami ověřit " +
                    "nejde – na ty použij Uvolnit místo přímo v Google Fotkách."
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ponechat oblíbené", Modifier.weight(1f))
                Switch(keepFavorites, onCheckedChange = { keepFavorites = it })
            }
            if (freeUpChecking) {
                Text("Ověřuji u Googlu…")
            } else {
                SettingsButton("Zkontrolovat, co je bezpečně v cloudu") { vm.checkFreeUp(keepFavorites) }
            }
            freeUpError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            freeUp?.let { result ->
                Text(
                    "${result.photos.size} fotek a videí (${Formatter.formatShortFileSize(context, result.bytes)}) " +
                        "je ověřeně v Google Fotkách",
                    style = MaterialTheme.typography.titleSmall,
                )
                result.perAccount.forEach { (account, count) -> Hint("$account: $count") }
                if (result.unsure > 0) {
                    Hint("${result.unsure} odeslaných nejde ověřit (soubor se od nahrání změnil) – zůstanou v telefonu.")
                }
                result.needsLogin.forEach { account ->
                    SettingsButton("Povolit ověření pro $account") { allowCheck(account, GoogleAuth.READ) }
                }
                if (result.photos.isNotEmpty()) {
                    SettingsButton("Vybrat, co smazat z telefonu") {
                        vm.selection("free-up").value = result.photos.map { it.id }.toSet()
                        choosingFreeUp = true
                    }
                }
            }

            HorizontalDivider()
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
                "Úpravy a hledání řeší Google Fotky. Aby na výletě nežraly baterku, " +
                    "nastav jim v Info o aplikaci → Baterie → Omezeno. Zálohu pak spustí, až je otevřeš."
            )
            SettingsButton("Omezit Google Fotky na pozadí") { GooglePhotos.appSettings(context) }
            SettingsButton("Systémový spořič baterie") { GooglePhotos.batterySaverSettings(context) }
            if (!canManageMedia) {
                SettingsButton("Oblíbené bez potvrzování") { GooglePhotos.manageMediaSettings(context) }
            }

            HorizontalDivider()
            Section("Záloha")
            Hint(
                "Alba, události, účty, fronta i seznam odeslaných fotek se samy zálohují do Google účtu " +
                    "(zhruba jednou denně) a po nové instalaci se vrátí. Před odinstalací je můžeš uložit i do souboru.",
            )
            SettingsButton("Uložit zálohu do souboru") { exportLauncher.launch("galerie-zaloha.json") }
            SettingsButton("Načíst zálohu ze souboru") { importLauncher.launch(arrayOf("application/json", "*/*")) }

        }
    }


    if (showingTrips) TripsDialog(vm) { showingTrips = false }
    if (choosingFreeUp) freeUp?.let { FreeUpDialog(it.photos, vm) { choosingFreeUp = false } }
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

/** Shows a short message; safe to call from any thread. */
fun toast(context: Context, text: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
}

/** Starts the app again, so every screen and the upload worker read the restored data. */
private fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    Runtime.getRuntime().exit(0)
}

@Composable
fun Section(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun SettingsButton(text: String, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }

/** Old photos that are only in Google Photos, shown in the gallery and calendar from small previews. */
@Composable
private fun CloudSection(vm: GalleryViewModel) {
    val context = LocalContext.current
    val accounts by vm.prefs.accounts.collectAsStateWithLifecycle()
    val show by vm.prefs.showCloud.collectAsStateWithLifecycle()
    val sync by vm.cloudSync.collectAsStateWithLifecycle()
    val start = rememberGoogleConsent(vm, onGranted = vm::startCloudSync, onError = { toast(context, it) })

    Section("Fotky z Google Fotek")
    Hint(
        "Stáhne malé náhledy fotek, které máš jen v Google Fotkách, aby byly vidět v mřížce i v kalendáři. " +
            "Jen přes Wi-Fi, nikdy přes data. Originály zůstávají v cloudu, nic se neodesílá ani nemaže."
    )
    Hint(
        "Fotky, které poslala tahle Galerie a v telefonu už nejsou, se doplní samy: jen na Wi-Fi " +
            "a nad hranicí baterie (nebo při nabíjení)."
    )
    Hint("Staženo: ${vm.cloud.count} náhledů (${Formatter.formatShortFileSize(context, vm.cloud.sizeBytes())})")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Ukazovat v galerii", Modifier.weight(1f))
        Switch(show, onCheckedChange = vm::setShowCloud)
    }
    var clearing by remember { mutableStateOf(false) }
    SettingsButton("Smazat všechny náhledy") { clearing = true }
    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text("Smazat všechny náhledy?") },
            text = { Text("Smažou se jen náhledy v telefonu a zastaví se jejich načítání. V Google Fotkách se nic nezmění, náhledy jde načíst znovu.") },
            confirmButton = {
                TextButton(onClick = {
                    clearing = false
                    vm.clearCloud()
                }) { Text("Smazat") }
            },
            dismissButton = { TextButton(onClick = { clearing = false }) { Text("Zrušit") } },
        )
    }
    WebPhotosSync(vm)
    Text("Ruční výběr v Google Fotkách", style = MaterialTheme.typography.titleSmall)
    val state = sync
    when {
        state == null || state.done || state.error != null -> {
            state?.error?.let { Text("Zastaveno: $it", color = MaterialTheme.colorScheme.error) }
            if (state?.done == true) Text("Hotovo, přibylo ${state.added} fotek.")
            if (accounts.isEmpty()) Hint("Nejdřív přidej Google účet v záložce Odesílání.")
            accounts.forEach { email ->
                SettingsButton("Načíst z $email") {
                    if (onWifi(context)) start(email, GoogleAuth.PICKER) else toast(context, "Připoj se k Wi-Fi")
                }
            }
        }
        state.downloading > 0 -> Text("Kolo ${state.round}: stahuji ${state.downloading} náhledů…")
        else -> {
            Text("Kolo ${state.round}: vyber fotky v Google Fotkách (max 2 000)", style = MaterialTheme.typography.titleSmall)
            vm.cloud.oldest()?.let {
                Hint(
                    "Zatím ${state.added} nových. Pokračuj fotkami staršími než " +
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) +
                        ". Už stažené klidně vyber znovu, přeskočí se. Když nic nového nepřibude, načítání skončí."
                )
            }
        }
    }
    if (state != null && !state.done && state.error == null) {
        SettingsButton("Ukončit načítání") { vm.stopCloudSync() }
    }
    TakeoutImport(vm)
}

/** The whole library by itself, after one login in the app's browser window. */
@Composable
private fun WebPhotosSync(vm: GalleryViewModel) {
    val context = LocalContext.current
    val on by vm.prefs.webPhotos.collectAsStateWithLifecycle()
    val progress by WebPhotos.progress.collectAsStateWithLifecycle()
    var login by remember { mutableStateOf(false) }

    Text("Celá knihovna najednou", style = MaterialTheme.typography.titleSmall)
    Hint(
        "Přihlásíš se do Google Fotek v okně Galerie a ta stáhne náhledy všech fotek a videí, které nejsou v telefonu. " +
            "Jakmile je hotovo, přihlášení se z telefonu smaže; heslo se neukládá nikdy. Nové fotky pak posílá a ukazuje Galerie sama. " +
            "Neoficiální cesta (čte se stejně jako web photos.google.com): po změně na straně Googlu může přestat fungovat."
    )
    val state = progress
    when {
        state?.error != null -> Text("Zastaveno: ${state.error}", color = MaterialTheme.colorScheme.error)
        state?.done == true -> Text(
            "Hotovo: v knihovně ${state.listed} položek, přibylo ${state.added} náhledů" +
                if (state.failed > 0) ", ${state.failed} se nepodařilo stáhnout (${state.reason})." else "."
        )
        state != null -> Text("Načítám: prošel jsem ${state.listed} položek, přibylo ${state.added} náhledů…")
    }
    if (on) {
        SettingsButton("Načíst teď") {
            if (onWifi(context)) vm.setWebPhotos(true) else toast(context, "Připoj se k Wi-Fi")
        }
        SettingsButton("Přihlásit se znovu") { login = true }
        SettingsButton("Zrušit a smazat přihlášení") { vm.setWebPhotos(false) }
    } else {
        SettingsButton("Přihlásit se a načíst knihovnu") { login = true }
    }
    if (login) {
        GooglePhotosLogin { signedIn ->
            login = false
            if (signedIn) vm.setWebPhotos(true)
        }
    }
}

/** All old photos at once: Google exports them as ZIP files, the app keeps only previews. */
@Composable
private fun TakeoutImport(vm: GalleryViewModel) {
    val context = LocalContext.current
    val progress by Takeout.progress.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files ->
        if (files.isNotEmpty()) vm.importTakeout(files)
    }

    Text("Všechny staré fotky najednou (Google Takeout)", style = MaterialTheme.typography.titleSmall)
    Hint(
        "1. Otevři Takeout, nech vybrané jen Google Fotky a dej Další krok → Vytvořit export.\n" +
            "2. Až přijde e-mail, stáhni ZIP soubory do telefonu.\n" +
            "3. Vyber je tady, klidně všechny části najednou. Uloží se jen malé náhledy, ZIPy pak smaž.\n" +
            "Fotky, které v telefonu pořád máš, a už načtené se přeskočí."
    )
    val state = progress
    when {
        state == null || state.done -> {
            state?.error?.let { Text("Zastaveno: $it", color = MaterialTheme.colorScheme.error) }
            if (state?.done == true && state.error == null) Text("Hotovo, přibylo ${state.added} fotek.")
            SettingsButton("Otevřít Google Takeout") {
                GooglePhotos.openLink(context, "https://takeout.google.com/settings/takeout/custom/photos")
            }
            SettingsButton("Vybrat ZIP z Takeoutu") { pick.launch(arrayOf("application/zip", "application/x-zip-compressed")) }
        }
        else -> {
            Text("Čtu ZIP: ${state.read} fotek… Běží i se zavřenou aplikací.")
            SettingsButton("Zastavit import") { vm.stopTakeout() }
        }
    }
}
