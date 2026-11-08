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
import com.google.android.gms.common.api.CommonStatusCodes
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
        val token = login.getOrNull()?.accessToken
        val error = login.exceptionOrNull()
        when {
            result.resultCode == Activity.RESULT_OK && token != null -> scope.launch {
                val email = vm.pendingAccount ?: login.getOrNull()?.toGoogleSignInAccount()?.email ?: GoogleAuth.emailOf(token)
                if (email != null) granted(email) else failed("Přihlášení prošlo, ale nepodařilo se zjistit e-mail účtu. Zkus to prosím znovu.")
            }
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
                // A phone with broken Play Services fails every authorize() call; say so before trying
                GoogleAuth.playServicesProblem(context)?.let { failed(it); return@launch }
                var pinned = email
                while (true) {
                    try {
                        val result = Identity.getAuthorizationClient(context).authorize(GoogleAuth.request(pinned, scopeUrl)).await()
                        val pending = result.pendingIntent
                        when {
                            result.hasResolution() && pending != null -> {
                                consent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                                return@launch
                            }
                            // The pinned account cannot open the dialog (e.g. it left the phone): let Google pick one
                            result.hasResolution() && pinned != null -> {
                                vm.pendingAccount = null
                                pinned = null
                            }
                            result.hasResolution() -> {
                                failed("Google chce přihlášení potvrdit, ale neposlal dialog – zkus to prosím znovu.")
                                return@launch
                            }
                            else -> {
                                val account = vm.pendingAccount ?: result.toGoogleSignInAccount()?.email
                                    ?: result.accessToken?.let { GoogleAuth.emailOf(it) }
                                if (account != null) granted(account) else failed("Google nevrátil účet, zkus to prosím znovu.")
                                return@launch
                            }
                        }
                    } catch (e: ApiException) {
                        // An account that is no longer on the phone: retry once with Google's account picker
                        if (pinned != null && e.statusCode == CommonStatusCodes.INVALID_ACCOUNT) {
                            vm.pendingAccount = null
                            pinned = null
                        } else {
                            failed(GoogleAuth.problem(e))
                            return@launch
                        }
                    } catch (e: Exception) {
                        failed(GoogleAuth.problem(e))
                        return@launch
                    }
                }
            }
        }
    }
}
