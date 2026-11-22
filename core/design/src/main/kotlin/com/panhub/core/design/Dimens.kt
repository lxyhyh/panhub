package com.panhub.core.design

import androidx.compose.ui.unit.dp

/** 统一的间距与圆角，禁止在业务代码里写魔法数字。 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object Radius {
    val card = 12.dp
    val sheet = 20.dp
    val pill = 999.dp
}

object Elevation {
    val card = 1.dp
    val sheet = 8.dp
}
