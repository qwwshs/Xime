package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for [XimeIndexParser]; fixtures are real xime-index samples. */
class XimeIndexParserTest {

    private val rootIndex = """
        index_version: 1
        updated_at: "2026-06-04"
        schemas:
          from: "./rimes/index.yaml"
        plugins:
          from: "./plugins/index.yaml"
        sources:
          - id: "xime-official"
            name: "Xime 官方源"
            url: "https://raw.githubusercontent.com/ximeiorg/xime-index/main/index.yaml"
            description: "Xime 官方维护的插件和方案市场"
    """.trimIndent()

    private val subIndex = """
        index_version: 1
        updated_at: "2026-06-04"
        schemas:
          - file: "./wubi86.yaml"
            version: "2.0.1"
          - file: "./cangjie.yaml"
            version: "master"
    """.trimIndent()

    private val wubi86Scheme = """
        id: "wubi86"
        name: "五笔86"
        author: "王永民"
        description: "五笔字形 86 版"
        type: "built-in"
        tags: ["五笔", "拼音"]
        dependencies: []
        appVersion: ">=2.3.0"
        currentVersion: "2.0.1"
        versions:
          - version: "2.0.1"
            date: "2024-01-01"
            downloadUrl: "https://github.com/kingzcheung/rime-wubi/archive/refs/tags/2.0.1.tar.gz"
            sha256: "ABC123"
          - version: "2.0.0"
            date: "2024-12-01"
            downloadUrl: "https://github.com/kingzcheung/rime-wubi/archive/refs/tags/2.0.0.tar.gz"
        someFutureUnknownField: "ignored"
    """.trimIndent()

    @Test
    fun `parseIndex maps root fields`() {
        val idx = XimeIndexParser.parseIndex(rootIndex)
        assertEquals("./rimes/index.yaml", idx.schemas?.from)
        assertEquals(1, idx.sources.size)
        assertEquals("xime-official", idx.sources[0].id)
    }

    @Test
    fun `parseSubIndex maps entries`() {
        val sub = XimeIndexParser.parseSubIndex(subIndex)
        assertEquals(2, sub.schemas.size)
        assertEquals("./wubi86.yaml", sub.schemas[0].file)
        assertEquals("2.0.1", sub.schemas[0].version)
    }

    @Test
    fun `parseScheme maps fields and tolerates unknown keys`() {
        val s = XimeIndexParser.parseScheme(wubi86Scheme)
        assertEquals("wubi86", s.id)
        assertEquals("五笔86", s.name)
        assertEquals("built-in", s.type)
        assertEquals(listOf("五笔", "拼音"), s.tags)
        assertEquals(">=2.3.0", s.appVersion)
        assertEquals("2.0.1", s.currentVersion)
        assertEquals(2, s.versions.size)
        assertTrue(s.versions[0].downloadUrl.endsWith("2.0.1.tar.gz"))
    }

    @Test
    fun `resolvedVersion picks currentVersion then falls back`() {
        val s = XimeIndexParser.parseScheme(wubi86Scheme)
        assertEquals("2.0.1", s.resolvedVersion()?.version)

        val noMatch = s.copy(currentVersion = "9.9.9")
        assertEquals("2.0.1", noMatch.resolvedVersion()?.version) // first

        val empty = s.copy(versions = emptyList())
        assertNull(empty.resolvedVersion())
    }

    @Test
    fun `resolveRepoPath resolves relative refs`() {
        assertEquals("rimes/index.yaml", XimeIndexParser.resolveRepoPath("index.yaml", "./rimes/index.yaml"))
        assertEquals("rimes/wubi86.yaml", XimeIndexParser.resolveRepoPath("rimes/index.yaml", "./wubi86.yaml"))
        assertEquals("wubi86.yaml", XimeIndexParser.resolveRepoPath("rimes/index.yaml", "../wubi86.yaml"))
        assertEquals("a/b.yaml", XimeIndexParser.resolveRepoPath("rimes/index.yaml", "/a/b.yaml"))
    }

    @Test
    fun `isCompatible treats beta core as the release version`() {
        // 关键回归点：2.3.0-beta5 不应被判定 < 2.3.0
        assertTrue(XimeIndexParser.isCompatible("2.3.0-beta5", ">=2.3.0"))
        assertTrue(XimeIndexParser.isCompatible("2.3.1", ">=2.3.0"))
        assertFalse(XimeIndexParser.isCompatible("2.2.0", ">=2.3.0"))
        assertTrue(XimeIndexParser.isCompatible("2.3.0-beta5", ""))         // 空约束
        assertTrue(XimeIndexParser.isCompatible("2.3.0-beta5", "weird"))    // fail-open
        assertFalse(XimeIndexParser.isCompatible("2.3.0", ">=9.9.9"))
    }

    @Test
    fun `minAppVersionLabel strips operator`() {
        assertEquals("2.3.0", XimeIndexParser.minAppVersionLabel(">=2.3.0"))
        assertEquals("9.9.9", XimeIndexParser.minAppVersionLabel(">=9.9.9"))
    }

    @Test
    fun `toItem computes compatibility`() {
        val s = XimeIndexParser.parseScheme(wubi86Scheme)
        val item = XimeIndexParser.toItem(s, "2.3.0-beta5")
        assertTrue(item.compatible)
        assertEquals("2.3.0", item.minAppVersion)

        val incompatible = XimeIndexParser.toItem(s.copy(appVersion = ">=9.9.9"), "2.3.0-beta5")
        assertFalse(incompatible.compatible)
        assertEquals("9.9.9", incompatible.minAppVersion)
    }
}
