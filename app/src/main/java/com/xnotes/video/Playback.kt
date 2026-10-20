package com.xnotes.video

import android.content.Context
import android.graphics.Bitmap
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.xnotes.alist.AlistPaths
import com.xnotes.core.model.VideoSource

/**
 * At most [MAX_PLAYERS] ExoPlayer instances for the whole process, reused across videos: a decoder
 * per player is the expensive part, and a note can hold many embedded videos of which only one or
 * two are ever on screen and playing. When all are taken the caller evicts its least recently used
 * playback (see [VideoSession]). Main thread only, as ExoPlayer itself is.
 */
@OptIn(UnstableApi::class)
internal object PlayerPool {
    const val MAX_PLAYERS = 2

    private val idle = ArrayDeque<ExoPlayer>()
    private var inUse = 0

    val canObtain: Boolean get() = idle.isNotEmpty() || inUse < MAX_PLAYERS

    /** A player, or null when [MAX_PLAYERS] are already out. */
    fun obtain(context: Context): ExoPlayer? {
        val p = idle.removeFirstOrNull() ?: if (inUse < MAX_PLAYERS) build(context.applicationContext) else null
        if (p != null) inUse++
        return p
    }

    /** Hand [player] back: stopped and emptied, kept for the next video (one idle at most). */
    fun recycle(player: ExoPlayer) {
        inUse = (inUse - 1).coerceAtLeast(0)
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        player.clearVideoSurface()
        player.setPlaybackParameters(PlaybackParameters.DEFAULT)
        if (idle.isEmpty()) idle.addLast(player) else player.release()
    }

    /** Free the idle players (memory pressure, last note closed). Players in use are untouched. */
    fun trim() {
        while (idle.isNotEmpty()) idle.removeFirst().release()
    }

    private fun build(context: Context): ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(SKIP_MS)
        .setSeekForwardIncrementMs(SKIP_MS)
        .build()

    const val SKIP_MS = 10_000L
}

/** Which on-screen surface shows a [Playback]'s picture; exactly one is bound at a time. */
enum class SurfaceSlot { INLINE, FLOATING, FULLSCREEN }

/**
 * One video loaded into one pooled player, with its state mirrored into Compose state for the
 * controls. A playback outlives the composables showing it (config changes, a pane resize, popping
 * out to the floating window and back never restart it); it ends only with [release], which
 * returns the player to the [PlayerPool].
 */
@OptIn(UnstableApi::class)
class Playback internal constructor(
    private val appContext: Context,
    internal var player: ExoPlayer?,
    val source: VideoSource,
    val title: String,
) {
    var isPlaying by mutableStateOf(false)
        private set
    var loading by mutableStateOf(true)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var bufferedMs by mutableLongStateOf(0L)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var videoW by mutableIntStateOf(0)
        private set
    var videoH by mutableIntStateOf(0)
        private set

    /** Which slot should show the picture now; the surface composables follow it. */
    var surfaceSlot by mutableStateOf(SurfaceSlot.INLINE)

    /** Monotonic use stamp for LRU eviction. */
    internal var lastUsed = System.nanoTime()

    private var boundView: TextureView? = null
    private var urls: List<String> = emptyList()
    private var urlIndex = 0

    /** Called when every URL failed, so the owner may re-resolve (AList raw URLs expire). */
    internal var onAllUrlsFailed: ((Playback, positionMs: Long) -> Unit)? = null

    val seconds: Double get() = positionMs / 1000.0
    val released: Boolean get() = player == null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            if (playing) error = null
        }

        override fun onPlaybackStateChanged(state: Int) {
            loading = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE && urls.isEmpty()
            if (state == Player.STATE_READY) tick()
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            if (size.width <= 0 || size.height <= 0) return
            // Anamorphic sources store a pixel ratio; the box wants the displayed shape.
            videoW = (size.width * size.pixelWidthHeightRatio).toInt()
            videoH = size.height
        }

        override fun onPlaybackParametersChanged(params: PlaybackParameters) {
            speed = params.speed
        }

        override fun onPlayerError(e: PlaybackException) {
            val at = player?.currentPosition ?: positionMs
            if (urlIndex + 1 < urls.size) {
                urlIndex++
                prepareCurrent(at, autoplay = true)
                return
            }
            val retry = onAllUrlsFailed
            if (retry != null) {
                onAllUrlsFailed = null // one fresh resolution per open
                retry(this@Playback, at)
                return
            }
            loading = false
            error = e.message ?: e.errorCodeName
        }
    }

    init {
        player?.addListener(listener)
    }

    /** Load [candidates] (best first; later ones are fallbacks) and start at [startMs]. */
    internal fun load(candidates: List<String>, startMs: Long, autoplay: Boolean) {
        urls = candidates
        urlIndex = 0
        error = null
        if (candidates.isEmpty()) {
            loading = false
            return
        }
        prepareCurrent(startMs, autoplay)
    }

    private fun prepareCurrent(startMs: Long, autoplay: Boolean) {
        val p = player ?: return
        val url = urls[urlIndex]
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        // No Authorization header ever: raw URLs and signed /d links carry their own grant, and
        // a redirect would hand the AList token to whatever storage host it points at.
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, http))
        val hls = AlistPaths.isHls(url.substringBefore('?')) || AlistPaths.isHls(source.value)
        val item = MediaItem.Builder()
            .setUri(url)
            .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        loading = true
        p.setMediaSource(factory.createMediaSource(item), startMs.coerceAtLeast(0L))
        p.prepare()
        p.playWhenReady = autoplay
        positionMs = startMs
    }

    /** Pull the position/duration into Compose state; the layer calls it every few frames. */
    fun tick() {
        val p = player ?: return
        positionMs = p.currentPosition.coerceAtLeast(0L)
        val d = p.duration
        durationMs = if (d == C.TIME_UNSET || d < 0) 0L else d
        bufferedMs = p.bufferedPosition.coerceAtLeast(0L)
    }

    fun play() { touch(); player?.play() }
    fun pause() { player?.pause(); tick() }
    fun toggle() = if (isPlaying) pause() else play()

    fun seekTo(ms: Long) {
        touch()
        val max = if (durationMs > 0) durationMs else Long.MAX_VALUE
        player?.seekTo(ms.coerceIn(0L, max))
        tick()
    }

    fun skip(deltaMs: Long) = seekTo(positionMs + deltaMs)

    fun setSpeed(value: Float) {
        touch()
        player?.setPlaybackSpeed(value.coerceIn(MIN_SPEED, MAX_SPEED))
    }

    internal fun touch() { lastUsed = System.nanoTime() }

    internal fun bind(view: TextureView) {
        val p = player ?: return
        if (boundView === view) return
        p.setVideoTextureView(view)
        boundView = view
    }

    internal fun unbind(view: TextureView) {
        if (boundView !== view) return
        player?.clearVideoTextureView(view)
        boundView = null
    }

    /**
     * The picture now, at the video's own resolution capped to [maxWidth] px wide, or null when no
     * surface shows it (or nothing has been drawn yet). Main thread.
     */
    fun grabFrame(maxWidth: Int = 1920): Bitmap? {
        val v = boundView ?: return null
        if (!v.isAvailable) return null
        val srcW = if (videoW > 0) videoW else v.width
        val srcH = if (videoH > 0) videoH else v.height
        if (srcW <= 0 || srcH <= 0) return null
        val scale = minOf(1f, maxWidth.toFloat() / srcW)
        return runCatching { v.getBitmap((srcW * scale).toInt().coerceAtLeast(1), (srcH * scale).toInt().coerceAtLeast(1)) }.getOrNull()
    }

    /** Stop and give the player back. Idempotent. */
    internal fun release() {
        tick()
        val p = player ?: return
        p.removeListener(listener)
        boundView?.let { p.clearVideoTextureView(it) }
        boundView = null
        player = null
        isPlaying = false
        PlayerPool.recycle(p)
    }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3f
        val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        private const val USER_AGENT = "xnotes-android (Media3)"
    }
}
