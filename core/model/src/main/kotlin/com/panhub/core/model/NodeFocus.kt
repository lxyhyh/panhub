package com.panhub.core.model

/**
 * 打开某个网盘时的「定位请求」。
 *
 * 搜索结果可能来自任何一个账号的任何一个目录。点进去的时候光有文件 id 不够：
 * 网盘大多只有父子 id、没有路径，用户点开一个深层文件后按返回键，如果栈里
 * 只有他点中的那一层，就会一步跳回根目录，看起来像走错了地方。
 *
 * 所以这里把「它原来在哪个目录」也带上：[parentName] 只用来显示面包屑，
 * 拿不到时由界面退回一个中性说法。没有 [parentId] 表示它本来就在根目录。
 */
data class NodeFocus(
    val id: NodeId,
    val name: String,
    val dir: Boolean,
    val parentId: NodeId? = null,
    val parentName: String? = null,
)

/** 把列表里的一项转成定位请求。 */
fun RemoteNode.toFocus(): NodeFocus = NodeFocus(
    id = id,
    name = name,
    dir = isDir,
    parentId = parentId,
    parentName = providerPath?.takeIf { it.isNotBlank() },
)
