package com.justsomepren.gallery

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.justsomepren.gallery.ui.GalleryApp

class MainActivity : ComponentActivity() {
    private val vm: GalleryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) vm.openFrom(intent)
        setContent { GalleryApp(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.openFrom(intent)
    }
}
