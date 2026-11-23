package com.panhub.app.share

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分享菜单进来的东西是不是一段文字。
 *
 * 这里只测纯字符串的那两个判断：真正的 Intent 在本地单测里是个空壳，
 * 拿不到 extras，能钉住的只有「收不收」这条规则。
 */
class SharedIntentTest {

    @Test
    fun plainTextShareIsAccepted() {
        assertTrue(SharedIntent.isTextShare(Intent.ACTION_SEND, "text/plain"))
    }

    @Test
    fun htmlTextShareIsAcceptedToo() {
        // 有些浏览器分享网页时给的是 text/html，链接照样在里面。
        assertTrue(SharedIntent.isTextShare(Intent.ACTION_SEND, "text/html"))
    }

    @Test
    fun imageShareIsIgnored() {
        // 图片分享过来我们没本事直接存进网盘，收下只会让用户白点一次。
        assertFalse(SharedIntent.isTextShare(Intent.ACTION_SEND, "image/jpeg"))
    }

    @Test
    fun viewActionIsNotAShare() {
        // ACTION_VIEW 是「用 PanHub 打开这个」，和分享是两回事，不做成同一入口。
        assertFalse(SharedIntent.isTextShare(Intent.ACTION_VIEW, "text/plain"))
    }

    @Test
    fun missingMimeTypeIsIgnored() {
        assertFalse(SharedIntent.isTextShare(Intent.ACTION_SEND, null))
    }
}
