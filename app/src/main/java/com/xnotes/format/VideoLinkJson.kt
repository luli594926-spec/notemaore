package com.xnotes.format

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.VideoLinkItem
import com.xnotes.core.model.VideoSource
import com.xnotes.core.pal.TextMeasurer

/**
 * The `.xnote` manifest form of a [VideoLinkItem] (feat-video-alist). One page item:
 *
 * ```json
 * { "kind": "video_link", "pos": [x, y],
 *   "video": { "source_type": "alist", "source": "/movies/a.mp4", "title": "a", "t": 83.5, "size": 14 },
 *   "locked": true }
 * ```
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
