package com.panhub.feature.localfiles

import com.panhub.core.design.formatBytes
import com.panhub.core.model.SortSpec
import com.panhub.core.transfer.DownloadedEntry
import com.panhub.core.transfer.DownloadsSnapshot
import com.panhub.core.transfer.sortedFor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一个文件在列表里显示成两行：名字，和「大小 · 位置 · 时间」。 */
data class LocalFileRow(
    val entry: DownloadedEntry,
    val subtitle: String,
)

/**
 * 本地文件页里不依赖 Android 的那部分。
 *
 * 抽出来是为了能被 JVM 单测直接覆盖：副标题的拼法、空状态的措辞、
 * 清理提示里的数字，都是「看着对但其实错」的重灾区。
 */
object LocalFilesLogic {

    private const val TIME_PATTERN = "yyyy-MM-dd HH:mm"

    /**
     * 把盘点结果摊平成列表行。
     *
     * 子目录里的文件会带上「在 photos 里」这样的说明，
     * 否则整目录下载下来的一堆同名文件在列表里分不出谁是谁。
     */
    fun rows(snapshot: DownloadsSnapshot): List<LocalFileRow> =
        snapshot.entries.map { entry -> LocalFileRow(entry = entry, subtitle = subtitleOf(entry)) }

    /** 第二行：大小，加上所在目录（在根目录时省略），加上修改时间。 */
    fun subtitleOf(entry: DownloadedEntry): String {
        val parts = mutableListOf(formatBytes(entry.size))
        val folder = entry.folder
        if (folder.isNotEmpty()) parts += "在 " + folder + " 里"
        if (entry.modifiedAt > 0L) parts += timeOf(entry.modifiedAt)
        return parts.joinToString(" · ")
    }

    fun timeOf(millis: Long): String =
        SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).format(Date(millis))

    /**
     * 顶部的总量说明。
     *
     * 空目录不给「0 B」这种冷冰冰的数字，直接说清楚这里为什么是空的。
     */
    fun summaryOf(snapshot: DownloadsSnapshot): String {
        if (snapshot.isEmpty) return "还没有下载过文件"
        return snapshot.entries.size.toString() + " 个文件 · " + formatBytes(snapshot.totalBytes)
    }

    /** 空状态正文：说明文件是怎么来的，以及为什么不能指望系统帮忙清。 */
    fun emptyMessage(): String =
        "下载完成之后会出现在这里。清空传输列表不会动这些文件，" +
            "它们一直占着应用私有空间，直到你在这里删掉或者卸载 App。"

    /**
     * 删除前的确认问句。
     *
     * 只删一个时逐字带上文件名，避免用户点错还以为是别的东西；
     * 删一批时报个数与总量，逐个列名字会把确认框塞满到看不清按钮。
     */
    fun deleteQuestion(entries: List<DownloadedEntry>): String {
        if (entries.isEmpty()) return "没有选中任何文件。"
        if (entries.size == 1) {
            val entry = entries.first()
            return "「" + entry.name + "」会从本机删掉（" + formatBytes(entry.size) + "）。网盘上的原件不受影响。"
        }
        val total = entries.sumOf { it.size }
        return "本机上的 " + entries.size + " 个文件会一起删掉，共 " +
            formatBytes(total) + "。网盘上的原件不受影响。"
    }

    /** 删除确认框的标题。 */
    fun deleteTitle(count: Int): String =
        if (count <= 1) "删除这个文件？" else "删除这 " + count + " 个文件？"

    /** 清空全部之前的确认问句。 */
    fun clearQuestion(snapshot: DownloadsSnapshot): String =
        "本机上的 " + snapshot.entries.size + " 个文件会一起删掉，共 " +
            formatBytes(snapshot.totalBytes) + "。网盘上的原件不受影响。"

    /** 删除之后给用户看的一句话。单个报名字，一批报条数。 */
    fun deletedMessage(entries: List<DownloadedEntry>, removed: Int): String {
        if (entries.size == 1) {
            val entry = entries.first()
            return if (removed > 0) "已删除 " + entry.name else "没能删掉 " + entry.name
        }
        if (removed <= 0) return "一个都没能删掉"
        if (removed >= entries.size) return "已删除 " + removed + " 个文件"
        return "已删除 " + removed + "/" + entries.size + " 个文件"
    }

    /**
     * 全选/取消全选的判据。
     *
     * 只看看得见的那几项：筛着的时候把被筛掉的也一起选中，
     * 用户按一下「删除 3 项」，磁盘上却少了十几个文件——这条路径上最伤人的错。
     */
    fun allSelected(visible: List<LocalFileRow>, selected: Set<String>): Boolean =
        visible.isNotEmpty() && visible.all { it.entry.relativePath in selected }

    /** 点「全选」之后下一次的选择集。 */
    fun toggleAll(visible: List<LocalFileRow>, selected: Set<String>): Set<String> =
        if (allSelected(visible, selected)) {
            emptySet()
        } else {
            selected + visible.map { it.entry.relativePath }
        }

    /** 选中项的总大小，用于底栏那行字。 */
    fun selectedBytes(entries: List<DownloadedEntry>, selected: Set<String>): Long =
        entries.sumOf { entry -> if (entry.relativePath in selected) entry.size else 0L }

    /** 清空之后给用户看的一句话。 */
    fun clearedMessage(count: Int, freedBytes: Long): String =
        if (count <= 0) "本机没有需要清理的文件"
        else "已清理 " + count + " 个文件 · " + formatBytes(freedBytes)
}

/**
 * 在行上排序。
 *
 * 规则本身在 [com.panhub.core.transfer.sortedFor]：本机文件的排序和网盘列表共用
 * 同一套 [SortSpec]，但排序细节（文件夹是否在前、没有时间戳的排哪里）不同，
 * 所以各留一份，界面上看到的标签与方向仍然是同一套。
 */
fun List<LocalFileRow>.sortedRows(spec: SortSpec): List<LocalFileRow> {
    if (size < 2) return this
    // relativePath 在一个下载根目录里唯一，所以按它把排好的条目映射回行是安全的。
    val rowsByPath = associateBy { it.entry.relativePath }
    return map { it.entry }.sortedFor(spec).map { rowsByPath.getValue(it.relativePath) }
}

