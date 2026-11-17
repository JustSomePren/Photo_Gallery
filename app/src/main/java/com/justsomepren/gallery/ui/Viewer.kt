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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.justsomepren.gallery.data.WebPhotos
import com.justsomepren.gallery.data.wifiFlow
import android.graphics.Rect
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import android.net.Uri
import androidx.compose.runtime.produceState
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
    // A zoomed photo pans instead of closing
    var zoomed by remember { mutableStateOf(false) }
    // Full quality from Google Photos over mobile data, once asked for in this viewer
    var overData by remember { mutableStateOf(false) }
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
                    enabled = !zoomed,
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
                ZoomableImage(item, isCurrent = page == pager.settledPage, overData, onZoomed = { zoomed = it }) { chrome = !chrome }
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
                        CloudActions(current, vm, actions, overData) { overData = true }
                        return@TopAppBar
                    }
                    IconButton(onClick = { actions.trash(listOf(current)) }) { Icon(Icons.Default.Delete, "Do koše") }
                    FavoriteButton(listOf(current)) { actions.toggleFavorite(listOf(current)) }
                    IconButton(onClick = { GooglePhotos.edit(context, current) }) {
                        Icon(Icons.Default.Edit, "Upravit v Google Fotkách")
                    }
                    SendButton(listOf(current), actions)
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

/** A photo that is only in Google Photos: a heart, albums, sharing, downloading and a login for full quality. */
@Composable
private fun CloudActions(item: MediaItem, vm: GalleryViewModel, actions: MediaActions, overData: Boolean, onOverData: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf(false) }
    var signedIn by remember { mutableStateOf(WebPhotos.signedIn()) }
    val wifi by remember { context.wifiFlow() }.collectAsStateWithLifecycle(true)
    val loadable = WebPhotos.key(item) != null

    FavoriteButton(listOf(item)) { actions.toggleFavorite(listOf(item)) }
    if (loadable && (!signedIn || (!wifi && !overData))) {
        // Off Wi-Fi the real photo comes only when asked for, over mobile data
        TextButton(onClick = { if (signedIn) onOverData() else login = true }) { Text("Plná kvalita", color = Color.White) }
    }
    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Další") }
    DropdownMenu(menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem({ Text("Otevřít v Google Fotkách") }, onClick = {
            menu = false
            GooglePhotos.openCloud(context, item)
        })
        DropdownMenuItem({ Text("Přidat do alba") }, onClick = {
            menu = false
            actions.addToAlbum(listOf(item))
        })
        if (loadable) {
            DropdownMenuItem({ Text("Sdílet v plné kvalitě") }, onClick = {
                menu = false
                if (signedIn) actions.share(listOf(item)) else login = true
            })
            DropdownMenuItem({ Text("Stáhnout v plné kvalitě") }, onClick = {
                menu = false
                if (signedIn) actions.saveToPhone(listOf(item), overData) else login = true
            })
        }
    }
    if (login) {
        GooglePhotosLogin { done ->
            login = false
            if (done) {
                vm.setWebStay(true)
                signedIn = true
            }
        }
    }
}

@Composable
private fun ZoomableImage(item: MediaItem, isCurrent: Boolean, overData: Boolean, onZoomed: (Boolean) -> Unit, onTap: () -> Unit) {
    val context = LocalContext.current
    val saver = LocalSaver.current
    val scope = rememberCoroutineScope()
    val metrics = context.resources.displayMetrics
    val screen = maxOf(metrics.widthPixels, metrics.heightPixels)

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var wantSharp by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<Job?>(null) }
    val zoomed = scale > 1.01f

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
    LaunchedEffect(zoomed, isCurrent) { if (isCurrent) onZoomed(zoomed) }

    // A photo only in Google Photos: its preview first, the real photo once it is loaded (Wi-Fi, signed in),
    // and when zoomed in the photo as it was uploaded
    val cloudView by produceState<Uri?>(null, item.id, isCurrent, overData) {
        if (item.cloud && isCurrent && value == null) value = WebPhotos.cached(context, item, overData = overData)?.let(Uri::fromFile)
    }
    val cloudOriginal by produceState<Uri?>(null, item.id, wantSharp, overData) {
        if (item.cloud && wantSharp && value == null) {
            value = WebPhotos.cached(context, item, original = true, overData = overData)?.let(Uri::fromFile)
        }
    }
    val uri = cloudView ?: item.uri
    val detailUri = if (item.cloud) cloudOriginal ?: cloudView else item.uri

    val normal = rememberLoaded(uri, screen, ImageCache.version) { ImageCache.fullImage(context.contentResolver, uri, screen) }

    // Zoomed in, only the part on screen is read from the file, as sharp as the screen can show it
    var regions by remember(detailUri) { mutableStateOf<PhotoRegions?>(null) }
    var noRegions by remember(detailUri) { mutableStateOf(false) }
    LaunchedEffect(detailUri, zoomed) {
        if (zoomed && !saver && detailUri != null && regions == null && !noRegions) {
            regions = withContext(Dispatchers.IO) { PhotoRegions.open(context.contentResolver, detailUri) }
            noRegions = regions == null
        }
    }
    DisposableEffect(detailUri) { onDispose { regions?.close() } }
    // Files that cannot be read in parts are loaded whole, twice the screen size
    val sharp = if (wantSharp && noRegions && !saver && detailUri != null) {
        val sharpSize = minOf(screen * 2, 4096)
        rememberLoaded(detailUri, sharpSize, ImageCache.version) { ImageCache.fullImage(context.contentResolver, detailUri, sharpSize) }
    } else {
        null
    }
    val bitmap = sharp ?: normal
    val aspect = rememberUpdatedState(bitmap?.let { it.width.toFloat() / it.height })

    /** The photo's size on screen before zooming: fitted into the box. */
    fun fitted(): Size {
        val ratio = aspect.value ?: return Size(box.width.toFloat(), box.height.toFloat())
        return if (box.width / ratio <= box.height) Size(box.width.toFloat(), box.width / ratio)
        else Size(box.height * ratio, box.height.toFloat())
    }

    fun clamp(value: Offset, zoom: Float = scale): Offset {
        val size = fitted()
        val maxX = maxOf(0f, (size.width * zoom - box.width) / 2)
        val maxY = maxOf(0f, (size.height * zoom - box.height) / 2)
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    /** Zooms so that the point under the fingers stays under them. */
    fun zoomAround(point: Offset, value: Float) {
        val zoom = value.coerceIn(1f, MAX_ZOOM)
        val center = Offset(box.width / 2f, box.height / 2f)
        offset = clamp((point - center) - (point - center - offset) * (zoom / scale), zoom)
        zoomTo(zoom)
    }

    fun animateTo(zoom: Float, target: Offset) {
        moving?.cancel()
        val fromScale = scale
        val fromOffset = offset
        moving = scope.launch {
            animate(0f, 1f, animationSpec = tween(250)) { t, _ ->
                zoomTo(lerp(fromScale, zoom, t))
                offset = lerp(fromOffset, target, t)
            }
        }
    }

    fun fling(velocity: Velocity) {
        val speed = sqrt(velocity.x * velocity.x + velocity.y * velocity.y)
        if (speed < 300f) return
        val direction = Offset(velocity.x / speed, velocity.y / speed)
        val from = offset
        moving = scope.launch {
            AnimationState(0f, speed).animateDecay(exponentialDecay()) { offset = clamp(from + direction * value) }
        }
    }

    // The detail of the part on screen, read once the fingers rest for a moment
    var tile by remember(detailUri) { mutableStateOf<Pair<ImageBitmap, Rect>?>(null) }
    LaunchedEffect(regions, scale, offset, box) {
        val source = regions
        if (source == null || !zoomed || box == IntSize.Zero) {
            tile = null
            return@LaunchedEffect
        }
        delay(150)
        val fit = minOf(box.width / source.width.toFloat(), box.height / source.height.toFloat())
        val left = (box.width - source.width * fit) / 2
        val top = (box.height - source.height * fit) / 2
        val center = Offset(box.width / 2f, box.height / 2f)
        // Screen corners → the photo's own pixels
        fun photoX(x: Float) = ((center.x + (x - center.x - offset.x) / scale) - left) / fit
        fun photoY(y: Float) = ((center.y + (y - center.y - offset.y) / scale) - top) / fit
        val rect = Rect(
            photoX(0f).toInt().coerceAtLeast(0),
            photoY(0f).toInt().coerceAtLeast(0),
            ceil(photoX(box.width.toFloat())).toInt().coerceAtMost(source.width),
            ceil(photoY(box.height.toFloat())).toInt().coerceAtMost(source.height),
        )
        if (rect.isEmpty) return@LaunchedEffect
        val sample = Integer.highestOneBit((1f / (fit * scale)).toInt().coerceAtLeast(1))
        val part = withContext(Dispatchers.IO) { source.decode(rect, sample) } ?: return@LaunchedEffect
        tile = part.asImageBitmap() to rect
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { at ->
                        if (scale > 1.01f) {
                            animateTo(1f, Offset.Zero)
                        } else {
                            val center = Offset(box.width / 2f, box.height / 2f)
                            animateTo(DOUBLE_TAP_ZOOM, clamp((at - center) * (1 - DOUBLE_TAP_ZOOM), DOUBLE_TAP_ZOOM))
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    moving?.cancel()
                    val tracker = VelocityTracker()
                    var pinched = false
                    do {
                        val event = awaitPointerEvent()
                        val down = event.changes.filter { it.pressed }
                        if (down.size >= 2) {
                            // Two fingers always zoom: neither the pager nor closing the photo take them
                            pinched = true
                            zoomAround(event.calculateCentroid(), scale * event.calculateZoom())
                            offset = clamp(offset + event.calculatePan())
                            event.changes.forEach { it.consume() }
                        } else if (down.size == 1 && scale > 1.01f) {
                            val pan = event.calculatePan()
                            val next = clamp(offset + pan)
                            tracker.addPosition(down[0].uptimeMillis, down[0].position)
                            // At the side of a zoomed photo a sideways swipe goes on to the next photo
                            val sideways = abs(pan.x) > abs(pan.y)
                            if (pan != Offset.Zero && (if (sideways) next.x != offset.x else next != offset)) {
                                offset = next
                                down[0].consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (!pinched && scale > 1.01f) fling(tracker.calculateVelocity())
                }
            },
    ) {
        val layer = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }
        if (bitmap != null) {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            // High quality: a picture drawn smaller than it is would look rough otherwise
            Image(image, null, layer, contentScale = ContentScale.Fit, filterQuality = FilterQuality.High)
        } else {
            Thumbnail(item.uri, 512, layer.background(Color.Black), contentScale = ContentScale.Fit)
        }
        val source = regions
        val detail = tile
        if (source != null && detail != null && zoomed) {
            val (part, rect) = detail
            Canvas(layer) {
                val fit = minOf(size.width / source.width, size.height / source.height)
                val left = (size.width - source.width * fit) / 2
                val top = (size.height - source.height * fit) / 2
                drawImage(
                    part,
                    dstOffset = IntOffset((left + rect.left * fit).roundToInt(), (top + rect.top * fit).roundToInt()),
                    dstSize = IntSize((rect.width() * fit).roundToInt(), (rect.height() * fit).roundToInt()),
                    filterQuality = FilterQuality.High,
                )
            }
        }
        if (item.cloud && cloudView == null) {
            Text(
                "Jen náhled",
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
            )
        }
    }
}

private const val MAX_ZOOM = 10f
private const val DOUBLE_TAP_ZOOM = 2.5f

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
