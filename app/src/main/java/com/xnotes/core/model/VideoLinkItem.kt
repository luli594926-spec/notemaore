package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextMeasurer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where a linked video comes from. Stored in the note as a ([type], [value]) pair so a reader that
 * does not know a type keeps the link (it just cannot play it).
 *
 * An AList source holds the server **path**, never a resolved raw URL: raw URLs are signed and
 * expire, so they are fetched fresh every time the link is opened.
 */
sealed class VideoSource(val type: String, val value: String) {
    /** A plain http(s) URL, played as is (mp4, webm, m3u8, ...). */
    class Url(url: String) : VideoSource(TYPE_URL, url)

    /** A path on the configured AList server, e.g. `/movies/lecture 01.mp4`. */
    class Alist(path: String) : VideoSource(TYPE_ALIST, path)

    /** A `content://` (or `file://`) uri picked from the device. */
    class Local(uri: String) : VideoSource(TYPE_LOCAL, uri)

    override fun equals(other: Any?): Boolean = other is VideoSource && other.type == type && other.value == value
    override fun hashCode(): Int = type.hashCode() * 31 + value.hashCode()
    override fun toString(): String = "$type:$value"

    companion object {
        const val TYPE_URL = "url"
        const val TYPE_ALIST = "alist"
        const val TYPE_LOCAL = "local"

        /** Rebuild a source as stored; null for an empty value. An unknown type reads as a URL. */
        fun of(type: String?, value: String?): VideoSource? {
            if (value.isNullOrBlank()) return null
            return when (type) {
                TYPE_ALIST -> Alist(value)
                TYPE_LOCAL -> Local(value)
                else -> Url(value)
            }
        }
    }
}

/** `mm:ss`, or `h:mm:ss` past an hour. Negative and NaN read as 0. */
fun formatTimestamp(seconds: Double): String {
    val total = if (seconds.isNaN() || seconds < 0) 0L else seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
}

/**
 * A tappable link to a moment in a video, drawn on the page as a small chip: a play triangle, the
 * video's [title] and the [seconds] it jumps to. A finger tap opens the in-app video panel at that
 * moment (see `Editor.onVideoLinkTap`); it moves, deletes and undoes like any other item.
 *
 * Its size follows its text, so there is nothing to resize; it never rotates or mirrors.
 * Identity semantics like every [CanvasItem] (never override equals).
 *
 * Lives in core.model (not com.xnotes.video) only because [GeometrySnapshot] is sealed to this
 * package; everything that plays it is in com.xnotes.video. (feat-video-alist)
 */
class VideoLinkItem(
    var pos: Pt,
    var source: VideoSource,
    var title: String,
    var seconds: Double,
    var pointSize: Double = DEFAULT_POINT_SIZE,
    private val measurer: TextMeasurer,
) : CanvasItem {

    override val kind = KIND
    override val resizable = false
    override val mirrorable = false
    override var locked = false

    private val font get() = FontSpec(pointSize, FontFace.SANS, bold = false)

    /** What the chip reads: "title · 12:34", the title shortened so a long file name stays a chip. */
    val label: String
        get() {
            val t = title.trim().ifEmpty { "Video" }
            val short = if (t.length > MAX_TITLE_CHARS) t.take(MAX_TITLE_CHARS - 1) + "…" else t
            return "$short · ${formatTimestamp(seconds)}"
        }

    private fun textWidth(): Double = measurer.advances(label, font).sum()

    private fun lineHeight(): Double = measurer.lineHeight(font)

    private val pad get() = pointSize * 0.45
    private val iconW get() = pointSize * 0.9

    override fun bounds(): Rect {
        val h = lineHeight() + pad * 2
        val w = pad + iconW + pad * 0.8 + textWidth() + pad * 1.2
        return Rect(pos.x, pos.y, w, h)
    }

    override fun paint(r: Renderer) {
        val b = bounds()
        r.fillPolygon(roundedRect(b, b.h / 2.0), FILL)
        r.strokePolygon(roundedRect(b, b.h / 2.0), Pen(INK, width = maxOf(1.0, pointSize / 12.0), cosmetic = false))
        // Play triangle, vertically centred.
        val cy = b.top + b.h / 2.0
        val x0 = b.left + pad
        val half = iconW * 0.55
        r.fillPolygon(listOf(Pt(x0, cy - half), Pt(x0 + iconW, cy), Pt(x0, cy + half)), INK)
        val m = measurer.metrics(font)
        val baseline = b.top + pad + m.ascent
        r.drawTextRun(label, x0 + iconW + pad * 0.8, baseline, font, INK)
    }

    override fun translate(dx: Double, dy: Double) {
        pos = Pt(pos.x + dx, pos.y + dy)
    }

    override fun contains(p: Pt): Boolean = bounds().contains(p)

    override fun centroid(): Pt = bounds().center

    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean =
        bounds().distanceTo(Pt(cx, cy)) <= radius

    override fun snapshotGeometry(): GeometrySnapshot = LinkSnapshot(pos, pointSize)

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap !is LinkSnapshot) return
        pos = snap.pos
        pointSize = snap.pointSize
    }

    /** Only position and (for a uniform scale) size follow a transform; a chip never turns. */
    override fun applyTransform(t: Affine) {
        pos = t.apply(pos)
        if (t.isUniformScale) pointSize = (pointSize * t.scaleX).coerceIn(4.0, 200.0)
    }

    /** An independent copy for copy/paste/duplicate and page cloning. */
    fun copy(): VideoLinkItem = VideoLinkItem(pos, source, title, seconds, pointSize, measurer).also { it.locked = locked }

    companion object {
        /** File-format kind. Readers before this feature skip it (forgiving loader). */
        const val KIND = "video_link"
        const val DEFAULT_POINT_SIZE = 14.0
        private const val MAX_TITLE_CHARS = 40

        /** A medium blue that reads on white, cream and dark paper alike. */
        val INK = Rgba(30, 136, 229, 255)
        val FILL = Rgba(30, 136, 229, 34)

        /** A rounded rectangle as a polygon (the renderer has no native round rect). */
        internal fun roundedRect(b: Rect, radius: Double, segments: Int = 6): List<Pt> {
            val rad = radius.coerceAtMost(minOf(b.w, b.h) / 2.0)
            val out = ArrayList<Pt>(segments * 4 + 4)
            fun arc(cx: Double, cy: Double, from: Double) {
                for (i in 0..segments) {
                    val a = from + (Math.PI / 2.0) * i / segments
                    out += Pt(cx + rad * cos(a), cy + rad * sin(a))
                }
            }
            arc(b.right - rad, b.bottom - rad, 0.0)
            arc(b.left + rad, b.bottom - rad, Math.PI / 2.0)
            arc(b.left + rad, b.top + rad, Math.PI)
            arc(b.right - rad, b.top + rad, Math.PI * 1.5)
            return out
        }
    }
}

private data class LinkSnapshot(val pos: Pt, val pointSize: Double) : GeometrySnapshot
