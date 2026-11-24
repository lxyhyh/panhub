package com.panhub.core.preview

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewZoomTest {

    @Test
    fun scaleNeverGoesBelowOriginal() {
        assertEquals(1f, PreviewZoom.clamp(0.2f));
    }

    @Test
    fun scaleNeverGoesPastTheLimit() {
        assertEquals(4f, PreviewZoom.clamp(99f));
    }

    @Test
    fun pinchingMultipliesTheCurrentScale() {
        assertEquals(2f, PreviewZoom.nextScale(1f, 2f));
    }

    @Test
    fun pinchingOutIsClampedAtTheLimit() {
        assertEquals(4f, PreviewZoom.nextScale(3f, 2f));
    }

    @Test
    fun aZeroFactorDoesNotWipeTheScale() {
        // 某些手机会给出 0 的比例；当成 0 倍会瞬间把图缩没，用户只能重开。
        assertEquals(2f, PreviewZoom.nextScale(2f, 0f));
    }

    @Test
    fun doubleTapZoomsInThenBack() {
        val zoomed = PreviewZoom.toggled(1f);
        assertEquals(2.5f, zoomed);
        assertEquals(1f, PreviewZoom.toggled(zoomed));
    }

    @Test
    fun originalSizeCannotBeDragged() {
        assertEquals(0f, PreviewZoom.maxTranslation(1f, 1000f));
    }

    @Test
    fun roomToDragGrowsWithTheZoom() {
        // 放大两倍，多出来的那一整份平均分在两侧，每侧半个屏幕。
        assertEquals(500f, PreviewZoom.maxTranslation(2f, 1000f));
    }

    @Test
    fun anUnknownExtentIsNotAnError() {
        assertEquals(0f, PreviewZoom.maxTranslation(2f, 0f));
    }
}
