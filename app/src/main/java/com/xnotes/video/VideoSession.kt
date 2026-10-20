package com.xnotes.video

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.R
import com.xnotes.alist.AlistClient
import com.xnotes.alist.AlistConfig
import com.xnotes.alist.AlistException
import com.xnotes.alist.AlistSettings
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.VideoItem
import com.xnotes.core.model.VideoLinkItem
import com.xnotes.core.model.VideoSource
import com.xnotes.ui.Editor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.WeakHashMap

/** One [VideoSession] per editor pane, created on first use and dropped with the pane. */
object VideoSessions {
    private val sessions = WeakHashMap<Editor, VideoSession>()

    fun of(editor: Editor, context: Context): VideoSession =
        sessions.getOrPut(editor) { VideoSession(context.applicationContext, editor) }

    /**
     * Forget [editor]'s session once its pane has closed. The session holds its editor strongly
     * (so the weak map alone would never let a released split pane go); this breaks that cycle.
     */
    fun drop(editor: Editor) {
        sessions.remove(editor)?.let {
            it.stopAll()
            it.dispose()
        }
    }
}

/**
 * An embedded video's place on screen this frame, in the canvas view's pixels. [rotation] is the
 * view's quarter turn (0/90/180/270), which the picture is turned by; [w]×[h] is the box before
 * that turn, centred on ([cx], [cy]).
 */
data class ScreenBox(val cx: Float, val cy: Float, val w: Float, val h: Float, val rotation: Int) {
    val left: Float get() = cx - (if (rotation % 180 == 0) w else h) / 2f
    val top: Float get() = cy - (if (rotation % 180 == 0) h else w) / 2f
    val outerW: Float get() = if (rotation % 180 == 0) w else h
    val outerH: Float get() = if (rotation % 180 == 0) h else w
}

/**
 * All video state for one editor pane (feat-video-alist):
 *
 * - **Embedded** playbacks, one per [VideoItem] the user started, drawn over the item's box. Each
 *   frame the layer calls [updateGeometry]; a playing item scrolled fully off screen (or deleted)
 *   is paused, its position saved into [VideoItem.startSeconds], its last frame kept as a poster,
 *   and its player returned to the [PlayerPool].
 * - One **floating** playback in a screen-fixed window over the canvas, reached by popping an
 *   embedded video out or by opening a link whose video is not on screen.
 * - **Fullscreen**, which borrows whichever playback asked for it.
 *
 * Lives outside composition, so configuration changes, multi-window resizes and the pane moving
 * between split slots never restart a video.
 */
class VideoSession internal constructor(private val appContext: Context, val editor: Editor) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val alistSettings = AlistSettings(appContext)
    private var alistClient: AlistClient? = null
    private var alistClientConfig: AlistConfig? = null

    /** item → its live playback; identity-keyed because items never override equals. */
    val embedded = mutableStateMapOf<VideoItem, Playback>()

    var floating by mutableStateOf<Playback?>(null)
        private set

    /** The embedded item the floating video came from, so "pin to canvas" can put it back. */
    private var floatingOrigin: VideoItem? = null

    var fullscreen by mutableStateOf<Playback?>(null)
        private set
    private var slotBeforeFullscreen = SurfaceSlot.INLINE

    /** The embedded item whose inline controls are showing, if any. */
    var controlsFor by mutableStateOf<VideoItem?>(null)

    // Floating window geometry, in dp from the layer's top-left; NaN until first laid out.
    var floatX by mutableFloatStateOf(Float.NaN)
    var floatY by mutableFloatStateOf(Float.NaN)
    var floatW by mutableFloatStateOf(Float.NaN)
    var floatMinimized by mutableStateOf(false)
    var floatControls by mutableStateOf(true)

    // Dialog requests, shown by the layer.
    var pickerOpen by mutableStateOf(false)
    var alistBrowserOpen by mutableStateOf(false)
    var alistSettingsOpen by mutableStateOf(false)
    var urlDialogOpen by mutableStateOf(false)

    /** Video items currently on screen, with their boxes; replaced only when the set changes. */
    val visible = mutableStateListOf<VideoItem>()
    private val boxes = java.util.IdentityHashMap<VideoItem, ScreenBox>()

    /** Bumped every frame the boxes are recomputed; read in layout lambdas, not composition. */
    var frame by mutableLongStateOf(0L)
        private set

    fun boxOf(item: VideoItem): ScreenBox? = boxes[item]

    /** The canvas view's size at the last [updateGeometry], for on-demand refreshes. */
    private var lastViewW = 0
    private var lastViewH = 0

    // ----- AList -------------------------------------------------------------------------------

    /** The client for the saved config, or null when no server is set. Rebuilt when it changes. */
    fun alist(): AlistClient? {
        val cfg = alistSettings.load()
        if (!cfg.isConfigured) return null
        if (cfg != alistClientConfig) {
            alistClient = AlistClient(cfg)
            alistClientConfig = cfg
        }
        return alistClient
    }

    fun alistConfigChanged() {
        alistClient = null
        alistClientConfig = null
    }

    /** Playable URLs for [source], resolved now (AList raw URLs expire, so never cached). */
    private suspend fun resolve(source: VideoSource): List<String> = when (source) {
        is VideoSource.Alist -> {
            val client = alist() ?: throw AlistException(-1, appContext.getString(R.string.video_alist_not_configured))
            client.playbackUrls(source.value)
        }
        else -> listOf(source.value)
    }

    // ----- playbacks ---------------------------------------------------------------------------

    private fun allPlaybacks(): List<Playback> = embedded.values + listOfNotNull(floating)

    /** A free player, evicting the least recently used embedded playback (then the floating one). */
    private fun obtainPlayer(): androidx.media3.exoplayer.ExoPlayer? {
        PlayerPool.obtain(appContext)?.let { return it }
        val victim = embedded.entries.minByOrNull { it.value.lastUsed }
        if (victim != null) deactivate(victim.key) else floating?.let { closeFloating() }
        return PlayerPool.obtain(appContext)
    }

    private fun newPlayback(source: VideoSource, title: String, startSeconds: Double, autoplay: Boolean): Playback? {
        val player = obtainPlayer() ?: return null
        val pb = Playback(appContext, player, source, title)
        start(pb, (startSeconds * 1000).toLong(), autoplay, allowRetry = true)
        return pb
    }

    private fun start(pb: Playback, startMs: Long, autoplay: Boolean, allowRetry: Boolean) {
        pb.loading = true
        scope.launch {
            val urls = try {
                resolve(pb.source)
            } catch (e: AlistException) {
                pb.loading = false
                pb.error = e.message
                editor.message = appContext.getString(R.string.video_open_failed, e.message ?: "")
                return@launch
            }
            if (pb.released) return@launch
            // An expired raw URL fails at the player: resolve once more from the path, then give up.
            if (allowRetry && pb.source is VideoSource.Alist) {
                pb.onAllUrlsFailed = { p, at -> if (!p.released) start(p, at, autoplay = true, allowRetry = false) }
            }
            pb.load(urls, startMs, autoplay)
        }
    }

    /** Start (or resume) [item] in place. */
    fun activate(item: VideoItem, autoplay: Boolean = true, seekSeconds: Double? = null) {
        val existing = embedded[item]
        if (existing != null) {
            seekSeconds?.let { existing.seekTo((it * 1000).toLong()) }
            if (autoplay) existing.play()
            controlsFor = item
            return
        }
        // A turned view (View ▸ rotate) would need the picture and its touch forwarding turned too;
        // play such a video in the floating window instead, which is always upright.
        if (editor.state.rotationDeg % 360 != 0) {
            openFloating(item.source, item.title, seekSeconds ?: item.startSeconds, origin = item)
            return
        }
        val pb = newPlayback(item.source, item.title, seekSeconds ?: item.startSeconds, autoplay) ?: return
        pb.surfaceSlot = SurfaceSlot.INLINE
        embedded[item] = pb
        controlsFor = item
    }

    /**
     * Stop [item]'s inline playback: keep where it got to and its last frame (as the poster the page
     * draws), and give the player back. [persist] marks the note dirty so the resume point is saved.
     */
    fun deactivate(item: VideoItem, persist: Boolean = true) {
        val pb = embedded.remove(item) ?: return
        if (controlsFor === item) controlsFor = null
        if (fullscreen === pb) exitFullscreen()
        pb.tick()
        val frameBitmap = pb.grabFrame(POSTER_WIDTH)
        item.startSeconds = pb.seconds
        pb.release()
        if (!editor.noteOpen || editor.pageIndexOfItem(item) < 0) {
            frameBitmap?.recycle()
            return
        }
        if (frameBitmap != null) savePoster(item, frameBitmap)
        editor.videoItemChanged(item, persist)
    }

    private fun savePoster(item: VideoItem, bitmap: Bitmap) {
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(appContext.cacheDir, "video-posters").apply { mkdirs() }
                    val f = File.createTempFile("poster", ".jpg", dir)
                    f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                    ImageData(f, bitmap.width, bitmap.height)
                }.getOrNull()
            }
            bitmap.recycle()
            if (data != null && editor.pageIndexOfItem(item) >= 0) {
                item.poster = data
                editor.videoItemChanged(item, persist = false)
            }
        }
    }

    /** Open [source] in the floating window (replacing what is there), at [seconds]. */
    fun openFloating(source: VideoSource, title: String, seconds: Double = 0.0, origin: VideoItem? = null) {
        floating?.let { cur ->
            if (cur.source == source) {
                cur.seekTo((seconds * 1000).toLong())
                cur.play()
                floatMinimized = false
                return
            }
            closeFloating()
        }
        val pb = newPlayback(source, title, seconds, autoplay = true) ?: return
        pb.surfaceSlot = SurfaceSlot.FLOATING
        floating = pb
        floatingOrigin = origin
        floatMinimized = false
    }

    /** Move [item]'s playback into the floating window without interrupting it. */
    fun popOut(item: VideoItem) {
        val pb = embedded[item]
        if (pb == null) {
            openFloating(item.source, item.title, item.startSeconds, origin = item)
            return
        }
        floating?.let { closeFloating() }
        embedded.remove(item)
        if (controlsFor === item) controlsFor = null
        item.startSeconds = pb.seconds
        pb.surfaceSlot = SurfaceSlot.FLOATING
        floating = pb
        floatingOrigin = item
        floatMinimized = false
        editor.videoItemChanged(item, persist = true)
    }

    /**
     * Put the floating video back on the canvas: into the item it came from when that is still on
     * screen, else as a new embedded video in the middle of the view. Playback carries on.
     */
    fun pinToCanvas() {
        updateGeometry(lastViewW, lastViewH)
        val pb = floating ?: return
        if (editor.state.rotationDeg % 360 != 0) {
            editor.message = appContext.getString(R.string.video_pin_rotated)
            return
        }
        val origin = floatingOrigin?.takeIf { editor.pageIndexOfItem(it) >= 0 && boxes.containsKey(it) }
        val target = origin ?: editor.insertVideoItem(pb.source, pb.title, pb.seconds, pb.videoW, pb.videoH)
        if (target == null) return
        floating = null
        floatingOrigin = null
        embedded[target]?.let { deactivate(target) }
        pb.surfaceSlot = SurfaceSlot.INLINE
        embedded[target] = pb
        controlsFor = target
    }

    fun closeFloating() {
        val pb = floating ?: return
        if (fullscreen === pb) exitFullscreen()
        floatingOrigin?.let { o ->
            if (editor.pageIndexOfItem(o) >= 0 && o.source == pb.source) {
                pb.tick()
                o.startSeconds = pb.seconds
                editor.videoItemChanged(o, persist = true)
            }
        }
        floating = null
        floatingOrigin = null
        pb.release()
    }

    fun enterFullscreen(pb: Playback) {
        if (fullscreen != null) return
        slotBeforeFullscreen = pb.surfaceSlot
        pb.surfaceSlot = SurfaceSlot.FULLSCREEN
        fullscreen = pb
    }

    fun exitFullscreen() {
        val pb = fullscreen ?: return
        pb.surfaceSlot = slotBeforeFullscreen
        fullscreen = null
    }

    /**
     * A finger tap on a video item or link (see [Editor.onVideoTap]). An idle video starts; a
     * playing one shows or hides its controls; a link seeks its video, or opens it floating.
     */
    fun onTap(item: CanvasItem): Boolean = when (item) {
        is VideoItem -> {
            if (embedded.containsKey(item)) {
                controlsFor = if (controlsFor === item) null else item
            } else {
                activate(item)
            }
            true
        }
        is VideoLinkItem -> {
            openLink(item)
            true
        }
        else -> false
    }

    /** Jump to a link's moment: in its embedded video when that is on screen, else floating. */
    fun openLink(link: VideoLinkItem) {
        updateGeometry(lastViewW, lastViewH)
        val onScreen = visible.firstOrNull { it.source == link.source }
        if (onScreen != null) {
            activate(onScreen, autoplay = true, seekSeconds = link.seconds)
            return
        }
        val playing = embedded.entries.firstOrNull { it.key.source == link.source }
        if (playing != null) {
            playing.value.seekTo((link.seconds * 1000).toLong())
            playing.value.play()
            return
        }
        openFloating(link.source, link.title, link.seconds)
    }

    /** The user paused [pb]: write where it stopped into its item, so closing the note keeps it. */
    fun notePaused(pb: Playback) {
        val item = itemOf(pb) ?: return
        pb.tick()
        item.startSeconds = pb.seconds
        if (editor.noteOpen) editor.videoItemChanged(item, persist = true)
    }

    /** The embedded item showing [pb], if it is an inline one. */
    fun itemOf(pb: Playback): VideoItem? = embedded.entries.firstOrNull { it.value === pb }?.key

    /** Insert a timestamp link to [pb]'s current moment, next to its video when it has one on the page. */
    fun insertLink(pb: Playback) {
        pb.tick()
        editor.insertVideoLink(pb.source, pb.title, pb.seconds, near = itemOf(pb) ?: floatingOrigin?.takeIf { editor.pageIndexOfItem(it) >= 0 })
    }

    /** Grab [pb]'s frame and insert it on the page beside its video, labelled with a timestamp link. */
    fun captureFrame(pb: Playback) {
        pb.tick()
        val bmp = pb.grabFrame()
        if (bmp == null) {
            editor.message = appContext.getString(R.string.video_capture_failed)
            return
        }
        val seconds = pb.seconds
        val near = itemOf(pb) ?: floatingOrigin?.takeIf { editor.pageIndexOfItem(it) >= 0 }
        scope.launch {
            val bytes = withContext(Dispatchers.Default) {
                ByteArrayOutputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    out.toByteArray()
                }
            }
            val w = bmp.width
            val h = bmp.height
            bmp.recycle()
            editor.insertVideoFrame(bytes, w, h, pb.source, pb.title, seconds, near)
        }
    }

    /** Embed a newly chosen video in the middle of the view and start it there. */
    fun insertAndPlay(source: VideoSource, title: String) {
        val item = editor.insertVideoItem(source, title) ?: run {
            editor.message = appContext.getString(R.string.video_needs_paged_note)
            return
        }
        activate(item)
    }

    // ----- per-frame geometry ------------------------------------------------------------------

    /**
     * Recompute where every video on the visible pages sits on screen, and stop inline playbacks
     * that have left the screen or the note. Called by the layer once per frame while it is shown.
     */
    fun updateGeometry(viewW: Int, viewH: Int) {
        if (viewW <= 0 || viewH <= 0) return // not laid out yet: keep what we have
        lastViewW = viewW
        lastViewH = viewH
        val state = editor.state
        boxes.clear()
        val seen = ArrayList<VideoItem>()
        if (editor.noteOpen && !editor.canvasOpen) {
            val viewRect = Rect(0.0, 0.0, viewW.toDouble(), viewH.toDouble())
            val visibleContent = state.visibleContentRect()
            val pages = state.document.pages
            for (i in pages.indices) {
                val pr = state.pageRects.getOrNull(i) ?: continue
                if (!pr.intersects(visibleContent)) continue
                for (item in pages[i].items) {
                    if (item !is VideoItem) continue
                    val content = state.fromPageSpaceRect(i, item.rect)
                    val a = state.contentToViewport(Pt(content.left, content.top))
                    val b = state.contentToViewport(Pt(content.right, content.bottom))
                    val vp = Rect.fromPoints(a, b)
                    if (!vp.intersects(viewRect)) continue
                    val rot = ((state.rotationDeg % 360) + 360) % 360
                    val (w, h) = if (rot % 180 == 0) vp.w to vp.h else vp.h to vp.w
                    boxes[item] = ScreenBox(vp.centerX.toFloat(), vp.centerY.toFloat(), w.toFloat(), h.toFloat(), rot)
                    seen += item
                }
            }
        }
        // Off screen or gone: pause, remember, release. (Not while fullscreen borrows it.)
        for (item in embedded.keys.toList()) {
            if (!boxes.containsKey(item) && fullscreen !== embedded[item]) {
                deactivate(item, persist = editor.pageIndexOfItem(item) >= 0)
            }
        }
        if (seen.size != visible.size || seen.indices.any { seen[it] !== visible[it] }) {
            visible.clear()
            visible.addAll(seen)
        }
        for (pb in allPlaybacks()) pb.tick()
        frame++
    }

    /** Pause everything (activity stopped). Positions are kept. */
    fun pauseAll() {
        for (pb in allPlaybacks()) pb.pause()
    }

    /** End every playback (the note closed or the pane went away). */
    fun stopAll() {
        exitFullscreen()
        for (item in embedded.keys.toList()) deactivate(item, persist = editor.pageIndexOfItem(item) >= 0)
        closeFloating()
        PlayerPool.trim()
    }

    /** Fit [item]'s box to its video's real shape the first time that is known. */
    fun fitToVideo(item: VideoItem, pb: Playback) {
        if (pb.videoW <= 0 || pb.videoH <= 0) return
        val before = item.rect
        item.fitAspect(pb.videoW, pb.videoH)
        if (item.rect != before) editor.videoItemChanged(item, persist = true)
    }

    internal fun dispose() = scope.cancel()

    private companion object {
        const val POSTER_WIDTH = 640
    }
}
