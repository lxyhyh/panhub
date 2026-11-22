package com.panhub.provider.webdav

import com.panhub.core.model.NodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavXmlTest {

    private val sample = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/dav/</D:href>
    <D:propstat>
      <D:prop>
        <D:resourcetype><D:collection/></D:resourcetype>
        <D:quota-used-bytes>1024</D:quota-used-bytes>
        <D:quota-available-bytes>2048</D:quota-available-bytes>
      </D:prop>
    </D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/docs/</D:href>
    <D:propstat>
      <D:prop>
        <D:resourcetype><D:collection/></D:resourcetype>
        <D:getlastmodified>Wed, 15 Nov 2023 12:34:56 GMT</D:getlastmodified>
      </D:prop>
    </D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/%E6%8A%A5%E5%91%8A.pdf</D:href>
    <D:propstat>
      <D:prop>
        <D:resourcetype/>
        <D:getcontentlength>4096</D:getcontentlength>
        <D:getcontenttype>application/pdf</D:getcontenttype>
      </D:prop>
    </D:propstat>
  </D:response>
</D:multistatus>"""

    @Test
    fun `parse list skips self and decodes names`() {
        val nodes = WebDavXml.parseList(sample, "/dav")
        assertEquals(2, nodes.size)

        assertEquals("docs", nodes[0].name)
        assertEquals(NodeKind.DIR, nodes[0].kind)
        assertEquals("/dav/docs", nodes[0].providerPath)

        assertEquals("报告.pdf", nodes[1].name)
        assertEquals(NodeKind.FILE, nodes[1].kind)
        assertEquals(4096L, nodes[1].size)
        assertEquals("application/pdf", nodes[1].mimeType)
        assertTrue(nodes[1].modifiedAt == null)
    }

    @Test
    fun `parse quota sums used and available`() {
        val quota = WebDavXml.parseQuota(sample)
        assertEquals(1024L, quota?.used)
        assertEquals(3072L, quota?.total)
    }

    @Test
    fun `parses rfc1123 date`() {
        val ms = WebDavXml.parseDate("Wed, 15 Nov 2023 12:34:56 GMT")
        assertEquals(1700051696000L, ms)
    }
}
