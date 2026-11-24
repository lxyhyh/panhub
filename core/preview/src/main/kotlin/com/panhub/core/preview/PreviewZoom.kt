package com.panhub.core.preview

/**
 * 图片预览的缩放规则。

 * 抽出来是因为缩放这件事有两个容易写错的地方：倍数必须夹在 [1, 4] 之内
 * （小于 1 相当于把图缩成一个小方块，大于 4 只会看到一片马赛克）；
 * 放大之后平移必须夹住，不然手指一甩图就跑到框外面，用户只能关掉重开。
 * 这两条都能用纯函数钉住，不必起一台设备去试。
 */
object PreviewZoom {

    /** 原图大小。再缩小没有意义：框里本来就装得下整张图。 */
    const val MIN_SCALE: Float = 1f

    /** 上限 4 倍。再往上只是在看像素块，还更容易把图甩出框。 */
    const val MAX_SCALE: Float = 4f

    /** 双击放大到这个倍数。比 2 倍更接近「看清细节」，又没到糊的程度。 */
    const val DOUBLE_TAP_SCALE: Float = 2.5f

    /** 把倍数夹进有效范围。 */
    fun clamp(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    /**
     * 捏合时的新倍数。
     *
     * [factor] 是这一帧捏合的相对比例，乘在当前倍数上才是手感对的做法：
     * 加一个固定步长会让「已经放大」和「刚开始放大」的响应速度完全不同。
     */
    fun nextScale(current: Float, factor: Float): Float {
        if (factor <= 0f) return clamp(current)
        return clamp(current * factor)
    }

    /** 双击：已经放大了就退回原大小，否则放大到 [DOUBLE_TAP_SCALE]。 */
    fun toggled(current: Float): Float =
        if (current > MIN_SCALE) MIN_SCALE else DOUBLE_TAP_SCALE

    /**
     * 某个方向上允许平移多远。
     *
     * 放大 n 倍后，多出来的部分平均分在两侧，所以上限是 (n-1)/2 个屏幕尺寸。
     * 原大小时返回 0 —— 这时候连拖都不该拖得动。
     */
    fun maxTranslation(scale: Float, extent: Float): Float {
        if (extent <= 0f) return 0f
        return (extent * (clamp(scale) - MIN_SCALE) / 2f).coerceAtLeast(0f)
    }
}
