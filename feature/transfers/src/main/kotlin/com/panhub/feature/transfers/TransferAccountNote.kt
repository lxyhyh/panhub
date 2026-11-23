package com.panhub.feature.transfers

import com.panhub.core.transfer.TransferTask

/**
 * 传输卡片上「这是哪个账号的任务」那一行。
 *
 * 只有多账号时才显示：只有一个账号时每张卡片都重复同一个名字，
 * 那是纯噪声，用户本来就没有别的可能。
 *
 * 跨网盘转存要显示两头——「A → B」才是这件事的全貌，
 * 只说 A 会让人以为文件是下到 A 里去了。
 */
object TransferAccountNote {

    /** 账号已被删除、或者保存的 id 对不上时的占位。宁可显示「未知」也不显示空白。 */
    const val UNKNOWN = "未知账号"

    fun note(task: TransferTask, labels: Map<String, String>): String {
        // 少于两个账号时无从混淆，不显示。
        if (labels.size < 2) return ""
        val source = labelOf(task.accountId.value, labels)
        val targetId = task.targetAccountId ?: return " · " + source
        return " · " + source + " → " + labelOf(targetId.value, labels)
    }

    private fun labelOf(accountId: String, labels: Map<String, String>): String =
        labels[accountId]?.takeIf { it.isNotBlank() } ?: UNKNOWN
}
