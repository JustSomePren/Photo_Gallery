package com.justsomepren.gallery.ui

import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.data.GooglePhotos
import com.justsomepren.gallery.data.MediaItem
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Viewer(items: List<MediaItem>, startId: Long, vm: GalleryViewModel, onBack: () -> Unit) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val context = LocalContext.current
    val saver = LocalSaver.current
    val start = remember { items.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(start) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    val actions = rememberMediaActions(vm)
    val current = items[pager.currentPage.coerceAtMost(items.lastIndex)]
    LaunchedEffect(current.id) { vm.returnTo = current.id }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            key = { items[it].id },
            beyondViewportPageCount = if (saver) 0 else 1,
        ) { page ->
            val item = items[page]
            if (item.isVideo) {
                VideoPage(item, isCurrent = page == pager.currentPage) { chrome = !chrome }
            } else {
                ZoomableImage(item) { chrome = !chrome }
            }
        }

        if (chrome) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
                title = {
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(current.date)))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zpět") }
                },
                actions = {
                    FavoriteButton(listOf(current)) { actions.toggleFavorite(listOf(current)) }
                    IconButton(onClick = { GooglePhotos.edit(context, current) }) {
                        Icon(Icons.Default.Edit, "Upravit v Google Fotkách")
                    }
                    IconButton(onClick = { actions.send(listOf(current)) }) {
                        Icon(Icons.Default.CloudUpload, "Poslat do cloudu")
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Další") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem({ Text("Otevřít v Google Fotkách") }, onClick = {
                            menu = false
                            GooglePhotos.open(context, current)
                        })
                        DropdownMenuItem({ Text("Informace") }, onClick = {
                            menu = false
                            actions.info = current
                        })
                        DropdownMenuItem({ Text("Přidat do alba") }, onClick = {
                            menu = false
                            actions.addToAlbum(listOf(current))
                        })
                        DropdownMenuItem({ Text("Přesunout do složky") }, onClick = {
                            menu = false
                            actions.move(listOf(current))
                        })
                        DropdownMenuItem({ Text("Sdílet") }, onClick = {
                            menu = false
                            actions.share(listOf(current))
                        })
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomableImage(item: MediaItem, onTap: () -> Unit) {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    // On low battery decode just for the screen, otherwise twice as big for sharp zoom.
    val maxSide = maxOf(metrics.widthPixels, metrics.heightPixels) * if (LocalSaver.current) 1 else 2
    val bitmap = rememberLoaded(item.uri, maxSide) {
        ImageCache.fullImage(context.contentResolver, item.uri, maxSide)
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(value: Offset): Offset {
        val maxX = box.width * (scale - 1) / 2
        val maxY = box.height * (scale - 1) / 2
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = clamp(offset + pan * scale)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2.5f
                        offset = Offset.Zero
                    },
                )
            }
            .transformable(transform, canPan = { scale > 1f }),
    ) {
        val layer = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }
        val full = bitmap
        if (full != null) {
            val image = remember(full) { full.asImageBitmap() }
            Image(image, null, layer, contentScale = ContentScale.Fit)
        } else {
            Thumbnail(item.uri, 512, layer.background(Color.Black), contentScale = ContentScale.Fit)
        }
    }
}

/** Plays with the built-in hardware decoder; no extra player library. */
@Composable
private fun VideoPage(item: MediaItem, isCurrent: Boolean, onTap: () -> Unit) {
    var playing by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf<VideoView?>(null) }
    var position by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isCurrent) { if (!isCurrent) playing = false }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (playing) {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        setVideoURI(item.uri)
                        setOnPreparedListener { start() }
                        setOnCompletionListener { playing = false }
                        video = this
                    }
                },
                onRelease = {
                    it.stopPlayback()
                    video = null
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Tapping pauses, swiping still goes to the pager.
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures {
                        video?.run { if (isPlaying) pause() else start() }
                        onTap()
                    }
                },
            )
            LaunchedEffect(video) {
                while (true) {
                    video?.let { if (it.duration > 0) position = it.currentPosition.toFloat() / it.duration }
                    delay(500)
                }
            }
            Slider(
                value = position,
                onValueChange = { value ->
                    position = value
                    video?.let { it.seekTo((value * it.duration).toInt()) }
                },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 24.dp),
            )
        } else {
            Thumbnail(item.uri, 1024, Modifier.fillMaxSize().background(Color.Black), contentScale = ContentScale.Fit)
            IconButton(onClick = { playing = true }, modifier = Modifier.size(96.dp)) {
                Icon(Icons.Default.PlayCircle, "Přehrát", tint = Color.White, modifier = Modifier.size(80.dp))
            }
        }
    }
}
