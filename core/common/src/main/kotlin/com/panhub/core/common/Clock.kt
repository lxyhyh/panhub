package com.panhub.core.common

/** 可注入的时钟，避免测试里依赖真实时间。 */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System: Clock = Clock { System.currentTimeMillis() }
    }
}
