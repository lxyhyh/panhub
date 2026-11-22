package com.panhub.core.common

/** 可注入的时钟，避免测试里依赖真实时间。 */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        /** 默认实现。注意不能命名为 System，否则会遮蔽 java.lang.System。 */
        val Default: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
