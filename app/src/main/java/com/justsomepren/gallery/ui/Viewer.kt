package com.justsomepren.gallery.ui

import android.app.Activity
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlin.math.abs
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
            if (item.isVideo && !item.cloud) {
                VideoPage(item, isCurrent = page == pager.settledPage) { chrome = !chrome }
            } else {
                ZoomableImage(item, isCurrent = page == pager.settledPage) { chrome = !chrome }
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
                    if (current.cloud) {
                        TextButton(onClick = { GooglePhotos.openCloud(context, current) }) {
                            Text("Jen náhled · otevřít v Google Fotkách", color = Color.White)
                        }
                        return@TopAppBar
                    }
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
                            actions.showInfo(current)
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
private fun ZoomableImage(item: MediaItem, isCurrent: Boolean, onTap: () -> Unit) {
    val context = LocalContext.current
    val saver = LocalSaver.current
    val metrics = context.resources.displayMetrics
    val screen = maxOf(metrics.widthPixels, metrics.heightPixels)

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var wantSharp by remember { mutableStateOf(false) }

    fun zoomTo(value: Float) {
        scale = value
        if (value > 1.5f) wantSharp = true else if (value < 1.1f) wantSharp = false
    }

    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            zoomTo(1f)
            offset = Offset.Zero
        }
    }

    // Screen size is enough until the user really zooms in; then a sharper copy (not on low battery).
    val normal = rememberLoaded(item.uri, screen, ImageCache.version) { ImageCache.fullImage(context.contentResolver, item.uri, screen) }
    val sharpSize = minOf(screen * 2, 4096)
    val sharp = if (wantSharp && !saver) {
        rememberLoaded(item.uri, sharpSize, ImageCache.version) { ImageCache.fullImage(context.contentResolver, item.uri, sharpSize) }
    } else {
        null
    }

    fun clamp(value: Offset): Offset {
        val maxX = box.width * (scale - 1) / 2
        val maxY = box.height * (scale - 1) / 2
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    val transform = rememberTransformableState { zoom, pan, _ ->
        zoomTo((scale * zoom).coerceIn(1f, 6f))
        offset = clamp(offset + pan)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        zoomTo(if (scale > 1f) 1f else 2.5f)
                        offset = Offset.Zero
                    },
                )
            }
            // At the edge of a zoomed photo a sideways swipe goes to the next photo.
            .transformable(transform, canPan = { pan ->
                scale > 1f && (abs(pan.y) > abs(pan.x) || clamp(offset + pan) != offset)
            }),
    ) {
        val layer = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }
        val bitmap = sharp ?: normal
        if (bitmap != null) {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Image(image, null, layer, contentScale = ContentScale.Fit)
        } else {
            Thumbnail(item.uri, 512, layer.background(Color.Black), contentScale = ContentScale.Fit)
        }
    }
}

/** Plays with the built-in hardware decoder; no extra player library. */
@Composable
private fun VideoPage(item: MediaItem, isCurrent: Boolean, onTap: () -> Unit) {
    val activity = LocalContext.current as? Activity
    var playing by rememberSaveable(item.id) { mutableStateOf(false) }
    var positionMs by rememberSaveable(item.id) { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }
    var seeking by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf<VideoView?>(null) }
    // What the user wants; the player sometimes forgets to start, so it is kept to this below
    var paused by remember(item.id) { mutableStateOf(false) }
    var prepared by remember { mutableStateOf(false) }

    LaunchedEffect(isCurrent) { if (!isCurrent) playing = false }
    // Leaving the app stops the video; it continues from the same spot on the next play.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        video?.let { positionMs = it.currentPosition }
        if (activity?.isChangingConfigurations != true) playing = false
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (playing) {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        setVideoURI(item.uri)
                        setOnPreparedListener {
                            durationMs = duration
                            prepared = true
                            seekTo(positionMs)
                            if (!paused) start()
                        }
                        setOnCompletionListener {
                            positionMs = 0
                            paused = true
                            playing = false
                        }
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
                        paused = !paused
                        video?.run { if (paused) pause() else start() }
                        onTap()
                    }
                },
            )
            LaunchedEffect(video) {
                while (true) {
                    video?.let { player ->
                        // Started once more when the player stopped on its own, e.g. after its surface changed
                        if (prepared && !paused && !seeking && !player.isPlaying) player.start()
                        if (player.isPlaying && !seeking) positionMs = player.currentPosition
                    }
                    delay(500)
                }
            }
            Slider(
                value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                onValueChange = {
                    seeking = true
                    positionMs = (it * durationMs).toInt()
                },
                onValueChangeFinished = {
                    video?.seekTo(positionMs)
                    seeking = false
                },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 24.dp),
            )
        } else {
            Thumbnail(item.uri, 1024, Modifier.fillMaxSize().background(Color.Black), contentScale = ContentScale.Fit)
            IconButton(onClick = {
                paused = false
                prepared = false
                playing = true
            }, modifier = Modifier.size(96.dp)) {
                Icon(Icons.Default.PlayCircle, "Přehrát", tint = Color.White, modifier = Modifier.size(80.dp))
            }
        }
    }
}
