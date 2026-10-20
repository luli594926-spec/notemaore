package com.xnotes.video

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.xnotes.core.model.VideoItem
import com.xnotes.platform.AndroidRenderer
import com.xnotes.ui.Editor

/**
 * A TextureView (not a SurfaceView: it composes with the canvas and the controls above it, turns
 * with the view, and can be read back for frame capture) letterboxed to the video's shape.
 */
@OptIn(UnstableApi::class)
internal class VideoFrame(context: Context) : AspectRatioFrameLayout(context) {
    val texture = TextureView(context)

    init {
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(texture, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

/**
 * Shows [pb]'s picture while [pb]'s [Playback.surfaceSlot] is [slot]; otherwise the same view just
 * stays black (fullscreen has borrowed the picture). The binding is identity-checked in [Playback],
 * so the order in which two slots attach and detach never leaves the player without a surface.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun VideoSurface(pb: Playback, slot: SurfaceSlot, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val frame = remember { VideoFrame(context) }
    AndroidView(factory = { frame }, modifier = modifier)
    val aspect = if (pb.videoW > 0 && pb.videoH > 0) pb.videoW.toFloat() / pb.videoH else 0f
    SideEffect { frame.setAspectRatio(aspect) }
    val active = pb.surfaceSlot == slot
    DisposableEffect(pb, active) {
        if (active) pb.bind(frame.texture)
        onDispose { pb.unbind(frame.texture) }
    }
}

/**
 * The ink drawn over an embedded video while it plays. The page cache has the ink too, but under
 * the live picture, so this view redraws, clipped to the video's box, every page item that sits
 * above the video in z-order (ink, shapes, text, pictures), in the box's own page coordinates
 * scaled to this view. It is laid out in the box's upright frame and turns with it.
 *
 * Ink being written *right now* is drawn by the canvas's own wet-ink path, beneath the video; it
 * shows here once the stroke is lifted and committed (see FEATURE_VIDEO_ALIST.md).
 */
internal class InkOverVideo(context: Context) : View(context) {
    var editor: Editor? = null
    var item: VideoItem? = null

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    override fun onDraw(canvas: Canvas) {
        val ed = editor ?: return
        val video = item ?: return
        val index = ed.pageIndexOfItem(video)
        if (index < 0) return
        val page = ed.state.document.pages[index]
        val rect = video.rect
        if (rect.w <= 0.0 || rect.h <= 0.0 || width <= 0 || height <= 0) return
        val at = page.items.indexOfFirst { it === video }
        if (at < 0 || at == page.items.lastIndex) return
        // Through the renderer's own transform calls, so it knows the scale and decodes pictures sharp.
        val r = AndroidRenderer(canvas)
        r.save()
        r.scale(width / rect.w, height / rect.h)
        r.translate(-rect.left, -rect.top)
        r.clipRect(rect)
        for (i in at + 1 until page.items.size) {
            val it = page.items[i]
            if (it.paintBounds().intersects(rect)) it.paint(r)
        }
        r.restore()
    }
}

/** The picture with the ink above it, stacked in one view. */
@OptIn(UnstableApi::class)
internal class EmbeddedVideoHost(context: Context) : FrameLayout(context) {
    val frame = VideoFrame(context)
    val ink = InkOverVideo(context)

    init {
        addView(frame, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(ink, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
