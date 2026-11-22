package com.panhub.provider.baidu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaiduParsingTest {

    @Test
    fun joinPathFromRootHasSingleLeadingSlash() {
        assertEquals("/docs", BaiduProvider.joinPath("/", "docs"))
    }

    @Test
    fun joinPathKeepsNestedPath() {
        assertEquals("/a/b", BaiduProvider.joinPath("/a", "b"))
    }

    @Test
    fun joinPathToleratesTrailingSlashOnParent() {
        assertEquals("/a/b", BaiduProvider.joinPath("/a/", "b"))
    }

    @Test
    fun parentOfRootChildIsRoot() {
        assertEquals("/", BaiduProvider.parentOf("/docs"))
    }

    @Test
    fun parentOfNestedFile() {
        assertEquals("/a/b", BaiduProvider.parentOf("/a/b/c.txt"))
    }

    @Test
    fun nameOfExtractsLastSegment() {
        assertEquals("c.txt", BaiduProvider.nameOf("/a/b/c.txt"))
    }

    @Test
    fun rootPathIsSlash() {
        assertEquals("/", BaiduApi.ROOT_PATH)
    }

    @Test
    fun encodeKeepsAsciiSafe() {
        assertEquals("a-b_c.d~e", BaiduApi.encode("a-b_c.d~e"))
    }

    @Test
    fun encodeEscapesChineseAndSlash() {
        assertEquals("%E4%B8%AD%2F", BaiduApi.encode("中/"))
    }

    @Test
    fun encodeEscapesSpace() {
        assertEquals("%20", BaiduApi.encode(" "))
    }
}
