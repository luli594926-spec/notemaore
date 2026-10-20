package com.xnotes.video

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xnotes.R
import com.xnotes.core.model.VideoItem
import com.xnotes.ui.Editor
import kotlin.math.roundToInt

/**
 * Everything video for one paged-note pane, composed over its canvas inside the canvas's own Box
 * (so its coordinates are the canvas view's): the live pictures of embedded videos over their page
 * boxes, their inline controls, the floating window, fullscreen, and the open-video dialogs.
 *
 * Integration: one call, `VideoLayer(editor)`, as the last child of the canvas Box in
 * MainActivity's EditorPane, plus `Editor.onVideoTap` hit-testing (installed here).
 */
@Composable
fun VideoLayer(editor: Editor) {
    val context = LocalContext.current
    val session = remember(editor) { VideoSessions.of(editor, context) }
    var viewW by remember { mutableIntStateOf(0) }
    var viewH by remember { mutableIntStateOf(0) }

    DisposableEffect(editor, session) {
        editor.onVideoTap = { item, _, _ -> session.onTap(item) }
        onDispose { editor.onVideoTap = null }
    }
    // A note closing ends its videos (positions are written back first).
    LaunchedEffect(editor.noteOpen) {
        if (!editor.noteOpen) session.stopAll()
    }
    DisposableEffect(editor) {
        onDispose {
            session.stopAll()
            // The pane itself went away (a closed split pane is released): drop its session too.
            if (!editor.noteOpen) VideoSessions.drop(editor)
        }
    }
    // Backgrounded: pause, never tear down (a config change or a multi-window resize never lands
    // here; the activity handles those itself, and the session outlives composition anyway).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) session.pauseAll() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // Track the boxes every frame while anything plays inline; otherwise the canvas is left alone.
    val anyInline = session.embedded.isNotEmpty()
    LaunchedEffect(anyInline, viewW, viewH) {
        session.updateGeometry(viewW, viewH)
        if (!anyInline) return@LaunchedEffect
        while (true) withFrameNanos { session.updateGeometry(viewW, viewH) }
    }
    // A slow tick for the floating window's clock when nothing inline drives updateGeometry.
    val floating = session.floating
    LaunchedEffect(floating, anyInline) {
        if (floating == null || anyInline) return@LaunchedEffect
        while (true) {
            floating.tick()
            kotlinx.coroutines.delay(250)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().onSizeChanged { viewW = it.width; viewH = it.height }) {
        for ((item, pb) in session.embedded) {
            androidx.compose.runtime.key(System.identityHashCode(item)) {
                EmbeddedPlayer(session, editor, item, pb)
            }
        }
        floating?.let { FloatingWindow(session, it, maxWidth, maxHeight) }
    }

    session.fullscreen?.let { FullscreenPlayer(session, it) }
    VideoDialogs(session)
}

/** One embedded video's live picture + ink + controls, laid over its page box each frame. */
@Composable
private fun EmbeddedPlayer(session: VideoSession, editor: Editor, item: VideoItem, pb: Playback) {
    // Fit the box to the real picture once the first frame says what shape it is.
    LaunchedEffect(pb.videoW, pb.videoH) { session.fitToVideo(item, pb) }
    val boxModifier = Modifier.layout { measurable, _ ->
        session.frame // read: relayout every geometry update, without recomposing
        val b = session.boxOf(item)
        if (b == null) {
            val p = measurable.measure(Constraints.fixed(0, 0))
            layout(0, 0) { p.place(0, 0) }
        } else {
            val w = b.outerW.roundToInt().coerceAtLeast(1)
            val h = b.outerH.roundToInt().coerceAtLeast(1)
            val p = measurable.measure(Constraints.fixed(w, h))
            layout(w, h) { p.place(IntOffset(b.left.roundToInt(), b.top.roundToInt())) }
        }
    }
    Box(boxModifier) {
        // The picture passes every touch on to the canvas, so the pen writes over a playing video,
        // a finger scrolls it away, and a tap reaches the canvas's video hit test (controls toggle).
        ForwardingVideoSurface(editor, item, pb)
        if (pb.loading && pb.error == null) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center).size(36.dp))
        }
        if (session.controlsFor === item) {
            VideoControls(session, pb, ControlsHost.INLINE, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
    }
}

@Composable
private fun ForwardingVideoSurface(editor: Editor, item: VideoItem, pb: Playback) {
    val context = LocalContext.current
    val host = remember { ForwardingHost(context, EmbeddedVideoHost(context)) }
    host.target = editor.surfaces
    AndroidView(factory = { host }, modifier = Modifier.fillMaxSize(), update = {
        it.inner.ink.editor = editor
        it.inner.ink.item = item
        it.inner.ink.invalidate()
    })
    // A committed stroke bumps the content version: redraw the ink above the picture.
    val version = editor.contentVersion
    LaunchedEffect(version) { host.inner.ink.invalidate() }
    LaunchedEffect(Unit) { host.inner.frame.setAspectRatio(0f) }
    val active = pb.surfaceSlot == SurfaceSlot.INLINE
    DisposableEffect(pb, active) {
        if (active) pb.bind(host.inner.frame.texture)
        onDispose { pb.unbind(host.inner.frame.texture) }
    }
}

/**
 * Wraps the embedded picture and hands every touch and hover event it receives to [target] (the
 * pane's canvas surfaces), moved into the target's coordinates. Compose stops hit-testing lower
 * siblings once a higher one is hit, so without this the canvas would never see a pen over a
 * playing video. Only used unrotated (see [VideoSession.activate]), so a plain offset suffices.
 */
internal class ForwardingHost(context: android.content.Context, val inner: EmbeddedVideoHost) :
    android.widget.FrameLayout(context) {
    var target: View? = null
    private val me = IntArray(2)
    private val them = IntArray(2)

    init {
        addView(inner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun forward(ev: MotionEvent, generic: Boolean): Boolean {
        val t = target ?: return false
        getLocationOnScreen(me)
        t.getLocationOnScreen(them)
        val copy = MotionEvent.obtain(ev)
        copy.offsetLocation((me[0] - them[0]).toFloat(), (me[1] - them[1]).toFloat())
        return try {
            if (generic) t.dispatchGenericMotionEvent(copy) else t.dispatchTouchEvent(copy)
        } finally {
            copy.recycle()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = forward(ev, generic = false)

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean = forward(ev, generic = true)
}

/**
 * The screen-fixed floating window: dragged by its title bar, resized from its corner grip (keeping
 * the video's shape), minimised to a slim bar. Sizes are in dp and clamped to the pane, so it stays
 * sensible from a phone-width split to a 13" landscape tablet, and through any window resize.
 */
@Composable
private fun FloatingWindow(session: VideoSession, pb: Playback, areaW: Dp, areaH: Dp) {
    val density = LocalDensity.current
    val aspect = if (pb.videoW > 0 && pb.videoH > 0) pb.videoW.toFloat() / pb.videoH else 16f / 9f
    val wide = areaW >= 840.dp // expanded width class
    val minW = 240f
    val maxW = (areaW.value * if (wide) 0.6f else 0.95f).coerceAtLeast(minW)
    if (session.floatW.isNaN()) session.floatW = (areaW.value * if (wide) 0.36f else 0.7f).coerceIn(minW, maxW.coerceAtMost(560f))
    val w = session.floatW.coerceIn(minW, maxW)
    val barH = 40f
    val videoH = if (session.floatMinimized) 0f else w / aspect
    val totalH = barH + videoH
    if (session.floatX.isNaN()) {
        session.floatX = areaW.value - w - 16f
        session.floatY = (areaH.value - totalH - 16f).coerceAtLeast(56f)
    }
    val x = session.floatX.coerceIn(0f, (areaW.value - w).coerceAtLeast(0f))
    val y = session.floatY.coerceIn(0f, (areaH.value - totalH).coerceAtLeast(0f))
    val shape = RoundedCornerShape(12.dp)

    Column(
        Modifier
            .offset(x.dp, y.dp)
            .width(w.dp)
            .shadow(12.dp, shape)
            .clip(shape)
            .background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.15f), shape),
    ) {
        // Title bar: drag handle, title, minimise / close.
        Row(
            Modifier
                .fillMaxWidth()
                .height(barH.dp)
                .background(Color(0xFF202124))
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        with(density) {
                            session.floatX = (session.floatX + drag.x.toDp().value)
                            session.floatY = (session.floatY + drag.y.toDp().value)
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.DragIndicator, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(start = 8.dp).size(20.dp))
            Text(
                pb.title.ifBlank { stringResource(R.string.video_untitled) },
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (session.floatMinimized) {
                VideoIconButton(
                    if (pb.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    stringResource(if (pb.isPlaying) R.string.video_pause else R.string.video_play),
                    onClick = { pb.toggle() },
                )
            }
            VideoIconButton(
                if (session.floatMinimized) Icons.Filled.OpenInFull else Icons.Filled.Remove,
                stringResource(if (session.floatMinimized) R.string.video_restore else R.string.video_minimize),
                onClick = { session.floatMinimized = !session.floatMinimized },
            )
            VideoIconButton(Icons.Filled.Close, stringResource(R.string.video_close), onClick = { session.closeFloating() })
        }
        if (!session.floatMinimized) {
            Box(Modifier.fillMaxWidth().aspectRatio(aspect)) {
                VideoSurface(pb, SurfaceSlot.FLOATING, Modifier.fillMaxSize().clickable { session.floatControls = !session.floatControls })
                if (pb.loading && pb.error == null) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center).size(36.dp))
                }
                if (session.floatControls || !pb.isPlaying) {
                    VideoControls(session, pb, ControlsHost.FLOATING, compact = w < 360f, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                }
                // Resize grip, top-right of the picture (the controls own the bottom); 40dp so a
                // finger or pen finds it. Width drives the size; the height follows the video.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(40.dp)
                        .pointerHoverIcon(PointerIcon.Crosshair)
                        .pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                with(density) { session.floatW = (session.floatW + drag.x.toDp().value).coerceIn(minW, maxW) }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.OpenInFull, stringResource(R.string.video_resize), tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** The borrowed playback over the whole window; Back or the exit button returns it. */
@Composable
private fun FullscreenPlayer(session: VideoSession, pb: Playback) {
    BackHandler { session.exitFullscreen() }
    var showControls by remember { mutableIntStateOf(1) }
    Dialog(
        onDismissRequest = { session.exitFullscreen() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoSurface(pb, SurfaceSlot.FULLSCREEN, Modifier.fillMaxSize().clickable { showControls = 1 - showControls })
            if (pb.loading && pb.error == null) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center).size(48.dp))
            }
            if (showControls == 1 || !pb.isPlaying) {
                VideoControls(session, pb, ControlsHost.FULLSCREEN, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
    }
}
