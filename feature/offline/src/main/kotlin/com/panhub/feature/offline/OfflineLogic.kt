package com.panhub.feature.offline

import com.panhub.core.model.OfflineFile
import com.panhub.core.model.OfflineResource
import com.panhub.core.model.OfflineTaskView

/**
 * 离线下载界面的纯逻辑。
 *
 * 抽出来是为了能在没有 Compose、没有网络的情况下钉住几条容易写错的规则：
 * 什么时候允许提交、提交后该说什么、文件夹要不要单独区分。
 */
object OfflineLogic {

    /** 提交按钮的可用性：解析完、选了东西、且没在忙。 */
    fun canSubmit(busy: Boolean, resource: OfflineResource?, selected: Set<String>): Boolean {
        if (busy) return false
        if (resource == null) return false
        return selected.isNotEmpty()
    }

    /**
     * 提交成功后的说明。
     *
     * 整个资源一起存和只挑几个文件，用户的预期不同，话也得不一样。
     */
    fun submittedMessage(resource: OfflineResource, selected: Set<String>): String {
        val total = resource.files.size
        return when {
            selected.isEmpty() -> "已提交离线任务，" + resource.name + " 正在下载"
            selected.size >= total -> "已提交离线任务，" + resource.name + " 里的 " + total + " 项正在下载"
            else -> "已提交离线任务，选了 " + selected.size + " 项"
        }
    }

    /** 资源里的文件数说明：链接任务里全是文件，BT 任务里可能夹着目录。 */
    fun describeResource(resource: OfflineResource): String {
        val files = resource.files
        if (files.isEmpty()) return "没有可以保存的文件"
        val folders = files.count { it.isDir }
        return when {
            folders == 0 -> files.size.toString() + " 个文件"
            folders == files.size -> files.size.toString() + " 个文件夹"
            else -> files.size.toString() + " 项（含 " + folders + " 个文件夹）"
        }
    }

    /**
     * 单选切换。
     *
     * 「全选」和「一项都不选」都是合法的中间状态，
     * 但提交按钮在空选择时必须是灰的——所以这里不做任何自动补选。
     */
    fun toggle(selected: Set<String>, id: String): Set<String> =
        if (id in selected) selected - id else selected + id

    /** 全选 / 取消全选：当前已全选就清空，否则补齐。 */
    fun toggleAll(selected: Set<String>, files: List<OfflineFile>): Set<String> =
        if (files.isNotEmpty() && selected.containsAll(files.map { it.id })) {
            emptySet()
        } else {
            files.map { it.id }.toSet()
        }

    /** 解析失败时给用户的提示：空消息要有兜底，否则界面会出现一块空白。 */
    fun failureMessage(message: String?): String =
        message?.takeIf { it.isNotBlank() } ?: "这个链接没能解析出内容"

    /**
     * 任务状态的中文说法。
 *
     * 123 的 status：0 下载中、1 暂停？（文档没有写死）、2 完成、3 失败。
     * OpenList 只区分「下载中/完成/失败」，数字语义以它的 GetOfflineTask
     * 检查逻辑为准：非 2 一律按「进行中」处理，只有确定完成才显示完成。
     */
    fun taskStatus(task: OfflineTaskView): String = when {
        task.status == STATUS_DONE -> "已完成"
        task.progress >= 100f -> "已完成"
        else -> "下载中"
    }

    /** 一行任务说明：进度 + 速度。速度为 0（已完成或刚排队）就不重复说。 */
    fun taskSubtitle(task: OfflineTaskView, formatBytes: (Long) -> String): String {
        val parts = mutableListOf<String>()
        parts.add(task.progress.toInt().toString() + "%")
        if (task.speed > 0L && task.status != STATUS_DONE) {
            parts.add(formatBytes(task.speed) + "/s")
        }
        return parts.joinToString(" · ")
    }

    /**
     * 还有没有在服务端跑着的任务。
     *
     * 判据是「没完成」而不是「等于下载中」：我们只确认了 0 与 2 两个取值，
     * 其余数字（暂停、失败、排队）都还没取证到，把它们当成「还在跑」
     * 至少不会让一条其实还在下的任务从界面上消失。
     */
    fun hasRunning(tasks: List<OfflineTaskView>): Boolean = tasks.any { !isFinished(it) }

    /** 一条任务是不是已经有结果了：完成（或进度打满）才算。 */
    fun isFinished(task: OfflineTaskView): Boolean =
        task.status == STATUS_DONE || task.progress >= 100f

    internal const val STATUS_DONE = 2
}
