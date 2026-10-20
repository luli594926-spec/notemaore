package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextMeasurer
import kotlin.math.abs

/**
 * An item the whole-item erasers (stroke eraser, scribble-to-erase) leave alone, as they leave
 * images and text boxes: it was placed on purpose, and ink is often written right over it.
 * Delete it with select + delete. (feat-video-alist)
 */
interface EraserProtected

/**
 * A video embedded on a page (feat-video-alist): a box like an image, movable and resizable
 * (aspect-locked), persisted with its [source], [title] and the [startSeconds] it resumes from.
 *
 * The page caches only ever see its *poster*: a dark frame with a play mark and its title, or the
 * last frame grabbed from it ([poster], runtime only, never saved). While it plays, the video
 * package lays a live player over the item's on-screen rect and redraws the ink above it, so
 * nothing in the canvas pipeline has to rasterize video frames.
 *
 * Identity semantics like every [CanvasItem]. It never rotates or mirrors (the selection offers no
 * rotate grip for it); it follows the view's own rotation and zoom like everything else on a page.
 */
class VideoItem(
    var rect: Rect,
    var source: VideoSource,
    var title: String,
    /** Where playback resumes, seconds; updated when the inline player pauses or is released. */
    var startSeconds: Double = 0.0,
    private val measurer: TextMeasurer,
) : CanvasItem, EraserProtected {

    override val kind = KIND
    override val resizable = true
    override val mirrorable = false
    override var locked = false

    /** The last frame shown, painted in place of the dark placeholder. Runtime only. */
    @Volatile var poster: ImageData? = null

    /** Width / height, from the video once known (else 16:9); resize keeps it. */
    val aspect: Double get() = if (rect.h > 0.0) rect.w / rect.h else DEFAULT_ASPECT

    override fun bounds(): Rect = rect

    override fun paint(r: Renderer) {
        val b = rect
        r.fillRect(b, BACKDROP)
        poster?.let { r.drawImage(it, b) }
        // Play mark, centred, sized to the box.
        val s = (minOf(b.w, b.h) * 0.18).coerceIn(8.0, 96.0)
        val c = b.center
        r.fillCircle(c, s, DISC)
        r.fillPolygon(
            listOf(Pt(c.x - s * 0.35, c.y - s * 0.5), Pt(c.x + s * 0.55, c.y), Pt(c.x - s * 0.35, c.y + s * 0.5)),
            MARK,
        )
        // Title and resume time along the bottom, when the box is tall enough to read them.
        val pt = (b.h * 0.06).coerceIn(6.0, 22.0)
        val font = FontSpec(pt, FontFace.SANS)
        val line = measurer.lineHeight(font)
        if (b.h > line * 3 && b.w > pt * 6) {
            val label = buildString {
                append(title.ifBlank { "Video" })
                if (startSeconds > 0.5) append("  ·  ").append(formatTimestamp(startSeconds))
            }
            val pad = pt * 0.6
            val strip = Rect(b.left, b.bottom - line - pad * 2, b.w, line + pad * 2)
            r.fillRect(strip, STRIP)
            r.withSave {
                r.clipRect(strip)
                r.drawTextRun(label, b.left + pad, strip.top + pad + measurer.metrics(font).ascent, font, MARK)
            }
        }
        r.strokeRect(b, Pen(FRAME, 1.0, cosmetic = true))
    }

    override fun translate(dx: Double, dy: Double) {
        rect = rect.translate(dx, dy)
    }

    override fun contains(p: Pt): Boolean = rect.contains(p)

    override fun centroid(): Pt = rect.center

    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean =
        rect.distanceTo(Pt(cx, cy)) <= radius

    /** Whether page-local [p] is on the centre play mark (a tap there starts playback). */
    fun hitsPlayMark(p: Pt): Boolean {
        val s = (minOf(rect.w, rect.h) * 0.18).coerceIn(8.0, 96.0)
        return rect.center.distanceTo(p) <= s * 1.6
    }

    override fun snapshotGeometry(): GeometrySnapshot = VideoSnapshot(rect)

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap is VideoSnapshot) rect = snap.rect
    }

    /**
     * Scale about the mapped centre, keeping the aspect: an edge drag scales by its own axis and a
     * corner drag by whichever axis moved more, so the picture is never stretched. Rotation in [t]
     * is ignored (a video plays upright in its box).
     */
    override fun applyTransform(t: Affine) {
        val sx = t.scaleX
        val sy = t.scaleY
        val s = (if (abs(sx - 1.0) >= abs(sy - 1.0)) sx else sy).coerceAtLeast(0.05)
        val c = t.apply(rect.center)
        val w = (rect.w * s).coerceAtLeast(MIN_W)
        val h = w / aspect
        rect = Rect(c.x - w / 2.0, c.y - h / 2.0, w, h)
    }

    /** Re-fit the box to the video's real aspect once it is known, keeping width and top-left. */
    fun fitAspect(videoW: Int, videoH: Int) {
        if (videoW <= 0 || videoH <= 0) return
        val a = videoW.toDouble() / videoH
        if (abs(a - aspect) < 0.01) return
        rect = Rect(rect.x, rect.y, rect.w, rect.w / a)
    }

    fun copy(): VideoItem =
        VideoItem(rect, source, title, startSeconds, measurer).also {
            it.locked = locked
            it.poster = poster
        }

    companion object {
        const val KIND = "video"
        const val DEFAULT_ASPECT = 16.0 / 9.0
        const val MIN_W = 80.0
        private val BACKDROP = Rgba(18, 18, 20, 255)
        private val DISC = Rgba(0, 0, 0, 140)
        private val MARK = Rgba(255, 255, 255, 235)
        private val STRIP = Rgba(0, 0, 0, 150)
        private val FRAME = Rgba(0, 0, 0, 90)
    }
}

private data class VideoSnapshot(val rect: Rect) : GeometrySnapshot
