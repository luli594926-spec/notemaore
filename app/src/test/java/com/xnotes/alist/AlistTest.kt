package com.xnotes.alist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AlistTest {

    @Test fun pathsNormalizeJoinAndSplit() {
        assertEquals("/", AlistPaths.normalize(""))
        assertEquals("/a/b", AlistPaths.normalize("a//b/"))
        assertEquals("/a/b/c.mp4", AlistPaths.join("/a/b/", "c.mp4"))
        assertEquals("/a", AlistPaths.parent("/a/b"))
        assertEquals("/", AlistPaths.parent("/a"))
        assertEquals("/", AlistPaths.parent("/"))
        assertEquals(listOf("/" to "/", "a" to "/a", "b c" to "/a/b c"), AlistPaths.breadcrumbs("/a/b c"))
    }

    @Test fun videoFilter() {
        for (n in listOf("a.mp4", "A.MKV", "x.webm", "s.m3u8", "m.mov", "f.flv")) assertTrue(n, AlistPaths.isVideo(n))
        for (n in listOf("a.txt", "mp4", "x.mp4.part", "")) assertFalse(n, AlistPaths.isVideo(n))
    }

    @Test fun downloadUrlEncodesEachSegmentAndTheSign() {
        assertEquals(
            "http://nas:5244/d/%E7%94%B5%E5%BD%B1/a%20b%2B1.mp4?sign=ab%3D%3A0",
            AlistPaths.downloadUrl("nas:5244/", "/电影/a b+1.mp4", "ab=:0"),
        )
        assertEquals("https://x.y/alist/d/a.mp4", AlistPaths.downloadUrl("https://x.y/alist", "a.mp4", ""))
    }

    @Test fun listingParsesEntriesAndNullContent() {
        val body = """{"code":200,"message":"success","data":{"content":[
            {"name":"lec 1.mp4","size":10,"is_dir":false,"modified":"2024-01-01T00:00:00Z","sign":"s1"},
            {"name":"sub","size":0,"is_dir":true,"modified":"","sign":null},
            {"name":"notes.pdf","size":3,"is_dir":false}
        ],"total":3}}"""
        val l = AlistJson.listing("/v", body)
        assertEquals(3, l.entries.size)
        assertEquals("/v/lec 1.mp4", l.entries[0].path)
        assertTrue(l.entries[0].isVideo)
        assertTrue(l.entries[1].isDir)
        assertNull(l.entries[1].sign)
        assertFalse(l.entries[2].isVideo)
        assertEquals(0, AlistJson.listing("/", """{"code":200,"data":{"content":null,"total":0}}""").entries.size)
    }

    @Test fun fileAndErrors() {
        val f = AlistJson.file("""{"code":200,"data":{"name":"a.mp4","is_dir":false,"raw_url":"https://cdn/a?x=1","sign":"zz","size":5}}""")
        assertEquals("https://cdn/a?x=1", f.rawUrl)
        assertEquals("zz", f.sign)
        val e = assertThrows(AlistException::class.java) { AlistJson.file("""{"code":401,"message":"token is expired","data":null}""") }
        assertEquals(401, e.code)
        assertThrows(AlistException::class.java) { AlistJson.token("<html>") }
        assertEquals("tk", AlistJson.token("""{"code":200,"data":{"token":"tk"}}"""))
    }
}
