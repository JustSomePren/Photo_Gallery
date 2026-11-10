package com.justsomepren.gallery.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.justsomepren.gallery.data.WebPhotos

/**
 * Google's own login page in a browser window of the app. The app never sees the password;
 * the login stays in the window's cookies and closes with true once Google Photos opens.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GooglePhotosLogin(onDone: (Boolean) -> Unit) {
    val done by rememberUpdatedState(onDone)
    Dialog(onDismissRequest = { done(false) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = WebPhotos.agent(context)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            if (!url.startsWith(WebPhotos.site)) return
                            CookieManager.getInstance().flush()
                            view.stopLoading()
                            done(true)
                        }
                    }
                    loadUrl(WebPhotos.login)
                }
            },
        )
    }
}
