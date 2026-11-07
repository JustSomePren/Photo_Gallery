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
import com.google.android.gms.common.api.ApiException
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.data.GoogleAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Asks Google for access to an account, showing the consent screen when needed.
 * Returns a function (email, scope) that starts it; [onGranted] gets the email once allowed.
 * With no email Google lets the user pick the account with one tap.
 */
@Composable
fun rememberGoogleConsent(vm: GalleryViewModel, onGranted: (String) -> Unit, onError: (String) -> Unit): (String?, String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val granted by rememberUpdatedState(onGranted)
    val failed by rememberUpdatedState(onError)
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        // Google puts the reason into the result also when its dialog ends without success
        val login = runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data) }
        val email = vm.pendingAccount ?: login.getOrNull()?.toGoogleSignInAccount()?.email
        val error = login.exceptionOrNull()
        when {
            result.resultCode == Activity.RESULT_OK && login.getOrNull()?.accessToken != null && email != null -> granted(email)
            error is ApiException -> failed(GoogleAuth.problem(error))
            else -> failed(
                "Přihlášení neprošlo. Jestli jsi ho nezrušil sám, Google tuhle aplikaci nejspíš ještě nezná: " +
                    "je potřeba jednou nastavit Google Cloud (README, Automatické odesílání – nastavení).",
            )
        }
    }
    return remember {
        { email: String?, scopeUrl: String ->
            vm.pendingAccount = email
            scope.launch {
                try {
                    val result = Identity.getAuthorizationClient(context).authorize(GoogleAuth.request(email, scopeUrl)).await()
                    val pending = result.pendingIntent
                    if (result.hasResolution() && pending != null) {
                        consent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                    } else {
                        val account = email ?: result.toGoogleSignInAccount()?.email
                        if (account != null) granted(account) else failed("Google nevrátil účet, zkus to prosím znovu.")
                    }
                } catch (e: Exception) {
                    failed(GoogleAuth.problem(e))
                }
            }
        }
    }
}
