package com.justsomepren.gallery.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.api.identity.Identity
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.data.GoogleAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Asks Google for access to an account, showing the consent screen when needed.
 * Returns a function (email, scope) that starts it; [onGranted] gets the email once allowed.
 */
@Composable
fun rememberGoogleConsent(vm: GalleryViewModel, onGranted: (String) -> Unit, onError: (String) -> Unit): (String, String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val granted by rememberUpdatedState(onGranted)
    val failed by rememberUpdatedState(onError)
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val email = vm.pendingAccount ?: return@rememberLauncherForActivityResult
        val token = runCatching {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data).accessToken
        }.getOrNull()
        if (result.resultCode == Activity.RESULT_OK && token != null) granted(email)
    }
    return remember {
        { email: String, scopeUrl: String ->
            vm.pendingAccount = email
            scope.launch {
                try {
                    val result = Identity.getAuthorizationClient(context).authorize(GoogleAuth.request(email, scopeUrl)).await()
                    val pending = result.pendingIntent
                    if (result.hasResolution() && pending != null) {
                        consent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                    } else {
                        granted(email)
                    }
                } catch (e: Exception) {
                    failed("Google přihlášení odmítl (${e.message}). Zkontroluj nastavení Google Cloud podle README.")
                }
            }
        }
    }
}
