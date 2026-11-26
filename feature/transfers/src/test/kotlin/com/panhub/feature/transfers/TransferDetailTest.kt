package com.panhub.feature.transfers

import com.panhub.core.model.AccountId
import com.panhub.core.transfer.TransferDirection
import com.panhub.core.transfer.TransferState
import com.panhub.core.transfer.TransferTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务详情里到底写哪几行。
 *
 * 这件事值得钉住：卡片上的两行小字是给「扫一眼」的，详情是用户点进来之后
 * 唯一能看清失败原因与文件去向的地方，少一行就等于让他去猜。
 */
class TransferDetailTest {

    private fun task(
        state: TransferState = TransferState.COMPLETED,
        direction: TransferDirection = TransferDirection.DOWNLOAD,
        bytes: Long = 1024L,
        totalBytes: Long? = 4096L,
        filePath: String? = null,
        error: String? = null,
        phase: String? = null,
        attempt: Int = 1,
        createdAt: Long = 0L,
        updatedAt: Long = 0L,
    ) = TransferTask(
        id = "t1",
        accountId = AccountId("a1"),
        nodeId = null,
        name = "报告.pdf",
        direction = direction,
        state = state,
        bytes = bytes,
        totalBytes = totalBytes,
        filePath = filePath,
        error = error,
        phase = phase,
        attempt = attempt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun labels(task: TransferTask, accountNote: String = "", speed: Long? = null) =
        TransferDetail.rows(task, accountNote, speed).associate { it.first to it.second }

    @Test
    fun theEssentialsAreAlwaysThere() {
        val rows = labels(task())
        assertEquals("已完成", rows["状态"])
        assertEquals("下载", rows["方式"])
        // 1024/4096 落在 1024 进制上，格式化出来是 1.0 KB 与 4.0 KB。
        assertEquals("1.0 KB / 4.0 KB（25%）", rows["已传"])
    }

    @Test
    fun orderIsWhatToWhereThenDetail() {
        // 第一行先说它现在是什么状态，最后才是要慢慢看的长文本。
        val keys = TransferDetail.rows(
            task(error = "连接被重置", filePath = "/tmp/x"),
            accountNote = "",
            speed = null,
        ).map { it.first }
        assertEquals(listOf("状态", "方式", "大小", "已传", "保存在", "错误"), keys)
    }

    @Test
    fun sizeIsOmittedWhenTheTotalIsUnknown() {
        val rows = labels(task(totalBytes = null))
        assertFalse(rows.containsKey("大小"))
        // 不知道总量就不该编百分比出来。
        assertEquals("1.0 KB", rows["已传"])
    }

    @Test
    fun zeroTotalIsTreatedAsUnknown() {
        val rows = labels(task(totalBytes = 0L))
        assertFalse(rows.containsKey("大小"))
        assertEquals("1.0 KB", rows["已传"])
    }

    @Test
    fun aDownloadedFileIsSavedSomewhere() {
        assertEquals(
            "/data/app/downloads/a.pdf",
            labels(task(filePath = "/data/app/downloads/a.pdf"))["保存在"],
        )
    }

    @Test
    fun anUploadPointsAtTheSourceFile() {
        // 上传时 filePath 是用户自己的源文件，说成「保存在」会让人以为被搬走了。
        val rows = labels(task(direction = TransferDirection.UPLOAD, filePath = "/sdcard/a.pdf"))
        assertEquals("/sdcard/a.pdf", rows["来源文件"])
        assertFalse(rows.containsKey("保存在"))
    }

    @Test
    fun aCloudCopyPointsAtTheScratchFile() {
        // 转存的临时文件中途随时会被清掉，叫「保存在」是骗人的。
        val rows = labels(
            task(direction = TransferDirection.CLOUD_COPY, filePath = "/cache/cloud-copy/a"),
        )
        assertEquals("/cache/cloud-copy/a", rows["中转文件"])
    }

    @Test
    fun noPathMeansNoPathRow() {
        val rows = labels(task(filePath = null))
        assertFalse(rows.containsKey("保存在"))
        assertFalse(rows.containsKey("来源文件"))
        assertFalse(labels(task(filePath = "  ")).containsKey("保存在"))
    }

    @Test
    fun theErrorOnlyShowsUpWhenThereIsOne() {
        assertEquals("连接被重置", labels(task(state = TransferState.FAILED, error = "连接被重置"))["错误"])
        assertFalse(labels(task()).containsKey("错误"))
        // 空串是「有字段没内容」，画一行空的只会让人怀疑是不是没加载完。
        assertFalse(labels(task(error = "")).containsKey("错误"))
    }

    @Test
    fun retriesAreCountedInTheStatusLine() {
        assertEquals("失败 · 第 3 次尝试", labels(task(state = TransferState.FAILED, attempt = 3))["状态"])
        // 第一次尝试是常态，写出来只是噪音。
        assertEquals("已完成", labels(task(attempt = 1))["状态"])
    }

    @Test
    fun speedIsOnlyThereWhenItWasMeasured() {
        val running = task(state = TransferState.RUNNING, bytes = 1024L, totalBytes = 3072L)
        assertFalse(labels(running, speed = null).containsKey("速度"))
        assertEquals("1.0 KB/s · 还剩 2 秒", labels(running, speed = 1024L)["速度"])
    }

    @Test
    fun finishedTasksHaveNoSpeedRow() {
        // 传完之后速度是历史数据，挂着只会让已经结束的一行显得还没完。
        assertFalse(labels(task(state = TransferState.COMPLETED), speed = 1024L).containsKey("速度"))
    }

    @Test
    fun theAccountComesFromTheSameWordsAsTheCard() {
        // 卡片上那一行带前导「 · 」，详情里要剥掉，不然会出现「 · 我的 123」。
        assertEquals("我的 123", labels(task(), accountNote = " · 我的 123")["账号"])
        assertFalse(labels(task(), accountNote = "").containsKey("账号"))
    }

    @Test
    fun aCrossAccountCopyKeepsBothEnds() {
        assertEquals(
            "我的 123 → 公司夸克",
            labels(task(), accountNote = " · 我的 123 → 公司夸克")["账号"],
        )
    }

    @Test
    fun thePhaseLineShowsOnlyWhenThereIsOne() {
        assertEquals("上传中", labels(task(phase = "上传中"))["阶段"])
        assertFalse(labels(task(phase = "  ")).containsKey("阶段"))
    }

    @Test
    fun timestampsAreSkippedWhenUnsetOrRepeated() {
        // 0 是「没给」，格式化出来会是 1970 年 1 月 1 日，比不画更糟。
        assertFalse(labels(task(createdAt = 0L, updatedAt = 0L)).containsKey("创建于"))

        val same = labels(task(createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L))
        assertTrue(same.containsKey("创建于"))
        // 刚建立的任务两个时间一样，重复画两行只会让人以为改过什么。
        assertFalse(same.containsKey("更新于"))

        val moved = labels(task(createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_060_000L))
        assertTrue(moved.containsKey("更新于"))
    }

    @Test
    fun folderTasksSayWhatTheyAre() {
        assertEquals("下载文件夹", TransferDetail.directionLabel(TransferDirection.DOWNLOAD_FOLDER))
        assertEquals("上传文件夹", TransferDetail.directionLabel(TransferDirection.UPLOAD_FOLDER))
        assertEquals("跨网盘转存", TransferDetail.directionLabel(TransferDirection.CLOUD_COPY))
        assertEquals("跨网盘转存文件夹", TransferDetail.directionLabel(TransferDirection.CLOUD_COPY_FOLDER))
    }

    @Test
    fun everyStateHasAReadableLabel() {
        assertEquals("排队中", TransferDetail.stateLabel(TransferState.QUEUED))
        assertEquals("传输中", TransferDetail.stateLabel(TransferState.RUNNING))
        assertEquals("已取消", TransferDetail.stateLabel(TransferState.CANCELED))
        assertEquals("已暂停", TransferDetail.stateLabel(TransferState.PAUSED))
    }

    @Test
    fun aNamelessTaskStillHasATitle() {
        assertEquals("传输任务", TransferDetail.title(task().copy(name = "")))
        assertEquals("报告.pdf", TransferDetail.title(task()))
    }

    @Test
    fun thePercentNeverGoesPastAHundred() {
        // 服务端报的总量比实际传的少时，别画出 120%。
        assertEquals("2.0 KB / 1.0 KB（100%）", labels(task(bytes = 2048L, totalBytes = 1024L))["已传"])
    }

    @Test
    fun longRowsAreTheOnesThatMustWrap() {
        // 错误与路径必须换行铺开：被截断的错误信息等于没显示。
        assertTrue(TransferDetail.isLong("错误"))
        assertTrue(TransferDetail.isLong("保存在"))
        assertTrue(TransferDetail.isLong("来源文件"))
        assertTrue(TransferDetail.isLong("中转文件"))
        assertFalse(TransferDetail.isLong("状态"))
        assertFalse(TransferDetail.isLong("已传"))
    }
}
