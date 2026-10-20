package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.TextItem
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.VideoItem
import com.xnotes.core.model.VideoLinkItem
import com.xnotes.core.model.VideoSource
import com.xnotes.core.model.deepCopy
import com.xnotes.core.model.formatTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VideoLinkCodecTest {

    private val measurer = FakeTextMeasurer()
    private val codec = DocumentCodec(FakeImageCodec(), measurer)

    private fun roundTrip(doc: Document): Document {
        val out = ByteArrayOutputStream()
        codec.write(doc, out)
        return codec.read(ByteArrayInputStream(out.toByteArray()), imageDir = Files.createTempDirectory("vl").toFile())
    }

    @Test fun videoLinksRoundTripWithEverySourceType() {
        val doc = Document()
        val page = Page(800.0, 1000.0)
        page.items.add(VideoLinkItem(Pt(10.0, 20.0), VideoSource.Alist("/movies/第1课 intro.mp4"), "第1课", 83.5, measurer = measurer).apply { locked = true })
        page.items.add(VideoLinkItem(Pt(30.0, 40.0), VideoSource.Url("https://e.x/a.m3u8"), "stream", 0.0, 20.0, measurer))
        page.items.add(VideoLinkItem(Pt(1.0, 2.0), VideoSource.Local("content://x/y"), "", 3600.0, measurer = measurer))
        doc.pages.add(page)

        val items = roundTrip(doc).pages[0].items
        assertEquals(3, items.size)
        val a = items[0] as VideoLinkItem
        assertTrue(a.source is VideoSource.Alist)
        assertEquals("/movies/第1课 intro.mp4", a.source.value)
        assertEquals("第1课", a.title)
        assertEquals(83.5, a.seconds, 1e-9)
        assertEquals(Pt(10.0, 20.0), a.pos)
        assertTrue(a.locked)
        val b = items[1] as VideoLinkItem
        assertTrue(b.source is VideoSource.Url)
        assertEquals(20.0, b.pointSize, 1e-9)
        assertTrue((items[2] as VideoLinkItem).source is VideoSource.Local)
    }

    /** A note with a link whose fields are garbage, or a link without a source, still opens. */
    @Test fun malformedLinksAreSkippedAndTheRestLoads() {
        val manifest = """
            {"format":"xnote","pages":[{"width":100,"height":100,"pdf_page":null,"items":[
              {"kind":"video_link","pos":[1,2],"video":"nope"},
              {"kind":"video_link","pos":[1,2],"video":{"title":"no source","t":"abc"}},
              {"kind":"video_link","pos":[5,6],"video":{"source_type":"martian","source":"https://a/b.mp4","t":-4,"extra":[1,2]}},
              {"kind":"text","pos":[0,0],"width":50,"text":"after","rgba":[0,0,0,255],"point_size":12}
            ]}]}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("manifest.json"))
            it.write(manifest.toByteArray())
            it.closeEntry()
        }
        val items = codec.read(ByteArrayInputStream(out.toByteArray()), imageDir = Files.createTempDirectory("vl").toFile()).pages[0].items
        assertEquals(2, items.size)
        val link = items[0] as VideoLinkItem
        assertTrue(link.source is VideoSource.Url) // unknown type reads as a URL
        assertEquals(0.0, link.seconds, 1e-9) // negative clamped
        assertTrue(items[1] is TextItem)
    }

    @Test fun deepCopyIsIndependent() {
        val a = VideoLinkItem(Pt(1.0, 1.0), VideoSource.Url("https://a"), "t", 5.0, measurer = measurer)
        val b = a.deepCopy(measurer) as VideoLinkItem
        assertNotSame(a, b)
        b.translate(10.0, 0.0)
        assertEquals(1.0, a.pos.x, 1e-9)
    }

    @Test fun timestampsFormat() {
        assertEquals("0:00", formatTimestamp(-1.0))
        assertEquals("1:23", formatTimestamp(83.9))
        assertEquals("1:00:05", formatTimestamp(3605.0))
    }

    @Test fun embeddedVideoRoundTripsBoxSourceAndResumePoint() {
        val doc = Document()
        val page = Page(800.0, 1000.0)
        page.items.add(VideoItem(Rect(10.0, 20.0, 320.0, 180.0), VideoSource.Alist("/v/a.mkv"), "a", 42.0, measurer))
        doc.pages.add(page)
        val v = roundTrip(doc).pages[0].items.single() as VideoItem
        assertEquals(Rect(10.0, 20.0, 320.0, 180.0), v.rect)
        assertEquals("/v/a.mkv", v.source.value)
        assertTrue(v.source is VideoSource.Alist)
        assertEquals(42.0, v.seconds(), 1e-9)
    }

    @Test fun embeddedVideoResizeKeepsItsAspect() {
        val v = VideoItem(Rect(0.0, 0.0, 160.0, 90.0), VideoSource.Url("https://a"), "", 0.0, measurer)
        v.applyTransform(Affine.scaleAbout(Pt(0.0, 0.0), 2.0, 1.0))
        assertEquals(320.0, v.rect.w, 1e-6)
        assertEquals(180.0, v.rect.h, 1e-6)
    }

    private fun VideoItem.seconds() = startSeconds
}
