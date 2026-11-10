package com.justsomepren.gallery.ui

import android.app.Activity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.text.style.TextOverflow
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

    // Pulling the photo down or up closes it; a short pull springs back.
    val scope = rememberCoroutineScope()
    val pull = remember { Animatable(0f) }
    val height = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val pulled = (abs(pull.value) / height).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f - pulled * 1.5f))) {
        HorizontalPager(
            state = pager,
            key = { items[it].id },
            beyondViewportPageCount = if (saver) 0 else 1,
            modifier = Modifier
                .draggable(
                    rememberDraggableState { delta -> scope.launch { pull.snapTo(pull.value + delta) } },
                    Orientation.Vertical,
                    onDragStopped = { velocity ->
                        if (abs(pull.value) > height * 0.15f || abs(velocity) > 1500f) onBack() else pull.animateTo(0f)
                    },
                )
                .graphicsLayer {
                    translationY = pull.value
                    scaleX = 1f - pulled * 0.3f
                    scaleY = 1f - pulled * 0.3f
                },
        ) { page ->
            val item = items[page]
            if (item.isVideo && !item.cloud) {
                VideoPage(item, isCurrent = page == pager.settledPage) { chrome = !chrome }
            } else {
                ZoomableImage(item, isCurrent = page == pager.settledPage) { chrome = !chrome }
            }
        }

        if (chrome && pull.value == 0f) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
                title = {
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(current.date)),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                    IconButton(onClick = { actions.trash(listOf(current)) }) { Icon(Icons.Default.Delete, "Do koše") }
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

/**
 * Plays with Media3's ExoPlayer: starts by itself when swiped to, pauses when swiped away.
 * Tap shows the controls, a double tap on the left or right skips 10 s back or forward,
 * dragging the bar shows the frames on the way.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPage(item: MediaItem, isCurrent: Boolean, onTap: () -> Unit) {
    var positionMs by rememberSaveable(item.id) { mutableLongStateOf(0L) }
    if (!isCurrent) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Thumbnail(item.uri, 1024, Modifier.fillMaxSize().background(Color.Black), contentScale = ContentScale.Fit)
            Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.size(80.dp))
        }
        return
    }

    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val player = remember(item.id) {
        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(SKIP_MS)
            .setSeekForwardIncrementMs(SKIP_MS)
            .build()
            .apply {
                setMediaItem(androidx.media3.common.MediaItem.fromUri(item.uri))
                seekTo(positionMs)
                prepare()
                playWhenReady = true
            }
    }
    var playing by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    var muted by rememberSaveable { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var skipped by remember { mutableStateOf<String?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) durationMs = player.duration.coerceAtLeast(0)
                ended = state == Player.STATE_ENDED
                if (ended) controls = true
            }

            override fun onRenderedFirstFrame() {
                shown = true
            }
        }
        player.addListener(listener)
        onDispose {
            positionMs = if (ended) 0 else player.currentPosition
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(playing, seeking) {
        while (playing && !seeking) {
            positionMs = player.currentPosition
            delay(200)
        }
    }
    // The controls hide by themselves while the video plays.
    LaunchedEffect(controls, playing) {
        if (controls && playing) {
            delay(3000)
            controls = false
        }
    }
    LaunchedEffect(skipped) {
        if (skipped != null) {
            delay(700)
            skipped = null
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) player.pause()
    }

    fun toggle() {
        when {
            ended -> {
                player.seekTo(0)
                player.play()
            }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!shown) Thumbnail(item.uri, 1024, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)

        // Taps and double taps; swipes still go to the pager and to closing.
        Box(
            Modifier.fillMaxSize().pointerInput(player) {
                detectTapGestures(
                    onTap = {
                        controls = !controls
                        onTap()
                    },
                    onDoubleTap = { at ->
                        if (at.x < size.width / 2) {
                            player.seekBack()
                            skipped = "−10 s"
                        } else {
                            player.seekForward()
                            skipped = "+10 s"
                        }
                        positionMs = player.currentPosition
                    },
                )
            },
        )
        skipped?.let {
            Text(it, color = Color.White, style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(16.dp, 8.dp))
        }

        if (controls || !playing) {
            IconButton(
                onClick = ::toggle,
                modifier = Modifier.size(80.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape),
            ) {
                Icon(
                    when {
                        ended -> Icons.Default.Replay
                        playing -> Icons.Default.Pause
                        else -> Icons.Default.PlayArrow
                    },
                    if (playing) "Pozastavit" else "Přehrát",
                    tint = Color.White,
                    modifier = Modifier.size(56.dp),
                )
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(formatTime(positionMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                    onValueChange = {
                        seeking = true
                        positionMs = (it * durationMs).toLong()
                        // Nearest key frame while dragging: fast, so the picture follows the finger.
                        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                        player.seekTo(positionMs)
                    },
                    onValueChangeFinished = {
                        player.setSeekParameters(SeekParameters.EXACT)
                        player.seekTo(positionMs)
                        seeking = false
                        scope.launch { controls = true }
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(formatTime(durationMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { muted = !muted }) {
                    Icon(
                        if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        if (muted) "Zapnout zvuk" else "Ztlumit",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

private const val SKIP_MS = 10_000L

private fun formatTime(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
