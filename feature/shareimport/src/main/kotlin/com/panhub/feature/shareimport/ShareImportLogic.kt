package com.panhub.feature.shareimport

import com.panhub.core.model.NodeId
import com.panhub.core.model.ShareEntry
import com.panhub.core.session.AccountSummary

/**
 * 「打开别人的分享」里那些能单独验证的纯逻辑。
 *
 * 抽出来的理由很实际：这一页的复杂度集中在状态流转（进目录、返回、多选、换账号），
 * 而这些流转全都不需要网络也不需要 Compose。留在 ViewModel 里就只能靠人肉点，
 * 抽成纯函数后每条规则都有一个用例钉住。
 */
object ShareImportLogic {

    /**
     * 哪些账号能用来打开别人的分享。
     *
     * 按最近使用倒序：用户刚登录过的账号最可能是他要转存过去的那一个。
     * 能力判断只看 shareImport，不看 share —— 一个是「发出去」，一个是「收进来」，
     * 服务商支持其中一个不代表支持另一个。
     */
    fun eligibleAccounts(all: List<AccountSummary>): List<AccountSummary> =
        all.filter { it.capabilities.shareImport }.sortedByDescending { it.lastUsedAt }

    /** 返回上一级目录。已经在根目录时保持原样，不会变成负数越界。 */
    fun parentStack(stack: List<ShareEntry>): List<ShareEntry> =
        if (stack.isEmpty()) stack else stack.dropLast(1)

    /** 当前所在目录；null 表示分享根目录。 */
    fun currentFolder(stack: List<ShareEntry>): NodeId? = stack.lastOrNull()?.id

    /**
     * 按选择顺序挑出要转存的条目。
     *
     * 用列表里的原始顺序而不是选择顺序：服务端返回的 top_fids 与请求顺序一一对应，
     * 顺序一变，落点名字就对错了。
     */
    fun selectedEntries(entries: List<ShareEntry>, selected: Set<NodeId>): List<ShareEntry> =
        entries.filter { it.id in selected }

    /** 标题栏上的一行：一个写名字，多个写数量。 */
    fun subjectOf(entries: List<ShareEntry>): String = when (entries.size) {
        0 -> ""
        1 -> entries.first().name
        else -> entries.size.toString() + " 个项目"
    }

    /**
     * 转存结果的一句话。
     *
     * 部分成功必须说清楚：服务端可能只搬过去一部分，
     * 一句「已转存」会让用户回头在自己的网盘里找不到东西。
     */
    fun savedMessage(saved: Int, requested: Int, folderName: String): String = when {
        requested <= 0 -> "没有选中任何内容"
        saved >= requested -> "已转存 " + requested + " 项到「" + folderName + "」"
        saved <= 0 -> "转存失败，服务端没有保存任何一项"
        else -> "已转存 " + saved + "/" + requested + " 项到「" + folderName + "」"
    }

    /** 全选/取消全选的判据：已经全选中了就该取消。 */
    fun allSelected(entries: List<ShareEntry>, selected: Set<NodeId>): Boolean =
        entries.isNotEmpty() && entries.all { it.id in selected }

    /** 点「全选」时下一次的选择集。 */
    fun toggleAll(entries: List<ShareEntry>, selected: Set<NodeId>): Set<NodeId> =
        if (allSelected(entries, selected)) emptySet() else entries.map { it.id }.toSet()
}
