package com.xnotes.format

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.VideoItem
import com.xnotes.core.model.VideoLinkItem
import com.xnotes.core.model.VideoSource
import com.xnotes.core.pal.TextMeasurer

/**
 * The `.xnote` manifest form of the two video items (feat-video-alist):
 *
 * ```json
 * { "kind": "video_link", "pos": [x, y],
 *   "video": { "source_type": "alist", "source": "/movies/a.mp4", "title": "a", "t": 83.5, "size": 14 },
 *   "locked": true }
 * { "kind": "video", "rect": [x, y, w, h],
 *   "video": { "source_type": "url", "source": "https://…/a.m3u8", "title": "a", "t": 12.0 } }
 * ```
 *
 * For a `video` item `t` is where the embedded player resumes; for a `video_link` it is the
 * moment the link jumps to.
 *
 * The link's own fields sit under one nested `video` object so [DocumentCodec]'s flat per-item
 * scratch gains a single key, and so a reader that predates this kind skips the whole item
 * (its loader drops unknown kinds) without misreading `title`/`t` as anything of its own.
 * Every field is optional on read; `t` is clamped to ≥ 0.
 */
internal object VideoLinkJson {

    /** The nested `video` object, parsed; held in the item scratch until the kind is known. */
    class Scratch {
        var sourceType: String? = null
        var source: String? = null
        var title: String = ""
        var seconds: Double = 0.0
        var pointSize: Double = VideoLinkItem.DEFAULT_POINT_SIZE
    }

    fun write(j: JsonWrite, item: VideoLinkItem) {
        j.beginObject()
        j.name("kind").value(VideoLinkItem.KIND)
        j.name("pos").beginArray().value(item.pos.x).value(item.pos.y).endArray()
        j.name("video").beginObject()
        j.name("source_type").value(item.source.type)
        j.name("source").value(item.source.value)
        j.name("title").value(item.title)
        j.name("t").value(item.seconds)
        if (item.pointSize != VideoLinkItem.DEFAULT_POINT_SIZE) j.name("size").value(item.pointSize)
        j.endObject()
        if (item.locked) j.name("locked").value(true)
        j.endObject()
    }

    fun writeVideo(j: JsonWrite, item: VideoItem) {
        j.beginObject()
        j.name("kind").value(VideoItem.KIND)
        j.name("rect").beginArray().value(item.rect.x).value(item.rect.y).value(item.rect.w).value(item.rect.h).endArray()
        j.name("video").beginObject()
        j.name("source_type").value(item.source.type)
        j.name("source").value(item.source.value)
        j.name("title").value(item.title)
        j.name("t").value(item.startSeconds)
        j.endObject()
        if (item.locked) j.name("locked").value(true)
        j.endObject()
    }

    /** The embedded video, or null without a source or a usable box. */
    fun buildVideo(s: Scratch?, rect: Rect?, measurer: TextMeasurer): VideoItem? {
        if (s == null) return null
        val source = VideoSource.of(s.sourceType, s.source) ?: return null
        val r = rect?.takeIf { it.w.isFinite() && it.h.isFinite() && it.w > 1.0 && it.h > 1.0 }
            ?: Rect(0.0, 0.0, 480.0, 270.0)
        val seconds = s.seconds.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        return VideoItem(r, source, s.title, seconds, measurer)
    }

    /** Reads the `video` value; anything but an object is skipped and yields null. */
    fun read(p: JsonPull): Scratch? {
        if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
            p.skipValue()
            return null
        }
        val s = Scratch()
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "source_type" -> s.sourceType = string(p)
                "source" -> s.source = string(p)
                "title" -> s.title = string(p) ?: ""
                "t" -> s.seconds = number(p) ?: 0.0
                "size" -> s.pointSize = number(p) ?: VideoLinkItem.DEFAULT_POINT_SIZE
                else -> p.skipValue()
            }
        }
        p.endObject()
        return s
    }

    /** The item, or null when the link has no source to open (dropped like an unknown kind). */
    fun build(s: Scratch?, pos: Pt?, measurer: TextMeasurer): VideoLinkItem? {
        if (s == null) return null
        val source = VideoSource.of(s.sourceType, s.source) ?: return null
        val seconds = s.seconds.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val size = s.pointSize.takeIf { it.isFinite() && it > 0.0 } ?: VideoLinkItem.DEFAULT_POINT_SIZE
        return VideoLinkItem(pos ?: Pt.ZERO, source, s.title, seconds, size, measurer)
    }

    private fun string(p: JsonPull): String? = when (p.peek()) {
        JsonPull.Token.STRING -> p.nextString()
        else -> { p.skipValue(); null }
    }

    private fun number(p: JsonPull): Double? = when (p.peek()) {
        JsonPull.Token.NUMBER -> p.nextDouble()
        JsonPull.Token.STRING -> p.nextString().toDoubleOrNull()
        else -> { p.skipValue(); null }
    }
}
