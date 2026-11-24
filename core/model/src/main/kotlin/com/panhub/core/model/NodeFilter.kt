package com.panhub.core.model

/**
 * 目录内按名字就地过滤。
 *
 * 与服务商的搜索是两件事：搜索要联网、跨目录、还会被限流；这里只筛当前
 * 已经加载出来的这一批，输入即生效、一个请求都不发。目录动辄几百项时，
 * 靠滚动找文件名是最费劲的一步。
 */
object NodeFilter {

    /**
     * 这一项是否命中关键字。
     *
     * 空关键字一律算命中：调用方不必先判空，少一处判断就少一处漏判。
     * 比较时忽略大小写——网盘里 `File` 与 `file` 混着存很常见，让用户
     * 自己去试哪种大小写匹配得上没有意义。
     */
    fun matches(name: String, query: String): Boolean {
        val keyword = query.trim()
        if (keyword.isEmpty()) return true
        return name.contains(keyword, ignoreCase = true)
    }

    /** 节点版的 [matches]，取它的名字去比。 */
    fun matches(node: RemoteNode, query: String): Boolean = matches(node.name, query)

    /**
     * 过滤一批节点。
     *
     * 只能收窄，不能改变顺序：排序是用户显式选的，过滤不该顺手把它重排一遍。
     */
    fun apply(nodes: List<RemoteNode>, query: String): List<RemoteNode> {
        if (query.isBlank()) return nodes
        return nodes.filter { matches(it, query) }
    }

    /**
     * 按名字过滤任意列表。
     *
     * 本机下载记录不是 [RemoteNode]（只有相对路径、大小和时间），但「输入即筛、
     * 忽略大小写、空词全留」这些规则必须和网盘列表一模一样 —— 同一个手势在两页
     * 表现不同会让人觉得是 bug。规则只留这一份，被筛的东西靠 [nameOf] 取名字。
     */
    fun <T> applyBy(items: List<T>, query: String, nameOf: (T) -> String): List<T> {
        if (query.isBlank()) return items
        return items.filter { matches(nameOf(it), query) }
    }

    /** 有没有在过滤。界面据此决定要不要显示「筛选中」这类说明。 */
    fun active(query: String): Boolean = query.isNotBlank()

    /**
     * 过滤之后一条都不剩时的说明。
     *
     * 把用户输入的词原样带回来：他可能刚打完字就忘了自己打了什么，
     * 也可能想确认是不是多打了空格。
     */
    fun emptyMessage(query: String): String {
        val keyword = query.trim()
        if (keyword.isEmpty()) return "这里还没有文件"
        return "没有名字包含「" + keyword + "」的项目"
    }

    /**
     * 输入框下面那行小字。
     *
     * 只有真的筛掉了一些东西才报数：全都命中时写「10/10」只是噪音，
     * 用户看着还以为是哪里出了错。
     */
    fun summary(shown: Int, total: Int, query: String): String? {
        if (!active(query)) return null
        if (shown >= total) return null
        return "筛选中 " + shown + "/" + total + " 项"
    }
}