package com.panhub.feature.browser

/**
 * 按一下系统返回键时该发生什么。
 *
 * 浏览页里有三种「局部状态」比「离开这一页」更该先退掉：多选、筛选条、子目录。
 * 把它们写在返回键里，是因为安卓用户按返回键的默认期待就是「退回上一步」。
 * 直接 pop 回账号页，等于把这一页的三层状态一次性丢掉，再进来还得重新点一路。
 */
enum class BrowserBackAction {
    /** 退出多选。 */
    EXIT_SELECTION,

    /** 收起筛选条（顺带清掉关键字）。 */
    CLOSE_FILTER,

    /** 返回上一级目录。 */
    GO_UP,

    /** 没有可退的东西了，交给系统：离开浏览页。 */
    LEAVE,
}

/** 返回键的判定规则。纯函数，界面只负责照着做。 */
object BrowserBack {

    /**
     * 决定这一次返回键要做什么。
     *
     * 优先级从「最临时」到「最持久」：多选是一条看得见的选择，筛选条是一个摊开的
     * 输入框，子目录才是真正的位置。先退位置再退选择会让用户以为选中的东西还在。
     *
     * [filterVisible] 是筛选条有没有摊开，而不是关键字非空——关键字可能因为
     * 前面某次操作被清掉但条还开着，那时按返回也该先把条收起来。
     */
    fun action(
        selectionMode: Boolean,
        filterVisible: Boolean,
        canGoUp: Boolean,
    ): BrowserBackAction = when {
        selectionMode -> BrowserBackAction.EXIT_SELECTION
        filterVisible -> BrowserBackAction.CLOSE_FILTER
        canGoUp -> BrowserBackAction.GO_UP
        else -> BrowserBackAction.LEAVE
    }

    /** 有没有可退的东西。没有时连监听都不该挂，交回给系统的默认行为。 */
    fun handles(selectionMode: Boolean, filterVisible: Boolean, canGoUp: Boolean): Boolean =
        selectionMode || filterVisible || canGoUp
}
