package com.panhub.core.transfer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadedFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun task(
        state: TransferState = TransferState.COMPLETED,
        direction: TransferDirection = TransferDirection.DOWNLOAD,
        filePath: String? = null,
    ) = TransferTask(
        id = "t1",
        accountId = com.panhub.core.model.AccountId("acc"),
        nodeId = null,
        name = "a.bin",
        direction = direction,
        state = state,
        filePath = filePath,
    )

    @Test
    fun onlyDownloadedFilesAreDeletable() {
        val file = temp.newFile("a.bin").absolutePath
        assertTrue(DownloadedFiles.canDelete(task(filePath = file)))
    }

    @Test
    fun uploadTaskPointsAtTheUsersOwnFile() {
        // 上传任务的路径是用户自己挑的源文件，照着删等于毁掉别人的东西。
        val file = temp.newFile("a.bin").absolutePath
        assertFalse(DownloadedFiles.canDelete(task(direction = TransferDirection.UPLOAD, filePath = file)))
    }

    @Test
    fun cloudCopyIsNotADownloadedFile() {
        val file = temp.newFile("a.bin").absolutePath
        assertFalse(
            DownloadedFiles.canDelete(task(direction = TransferDirection.CLOUD_COPY, filePath = file)),
        )
    }

    @Test
    fun unfinishedDownloadIsNotDeletable() {
        val file = temp.newFile("a.bin").absolutePath
        assertFalse(DownloadedFiles.canDelete(task(state = TransferState.RUNNING, filePath = file)))
        assertFalse(DownloadedFiles.canDelete(task(state = TransferState.PAUSED, filePath = file)))
    }

    @Test
    fun folderTaskHasNoFileOfItsOwn() {
        // 整目录下载自己只搬列表，文件都在它的子任务上。
        assertFalse(DownloadedFiles.canDelete(task(direction = TransferDirection.DOWNLOAD_FOLDER)))
        assertFalse(DownloadedFiles.canDelete(task(filePath = "   ")))
    }

    @Test
    fun deleteRemovesTheFileFromDisk() {
        val file = temp.newFile("a.bin")
        assertTrue(DownloadedFiles.delete(task(filePath = file.absolutePath)))
        assertFalse(file.exists())
    }

    @Test
    fun deletingAnAlreadyMissingFileCountsAsDone() {
        // 调用方要的结果是「别再占地方了」，文件本来就不在也算达成。
        val gone = File(temp.root, "gone.bin")
        assertTrue(DownloadedFiles.delete(task(filePath = gone.absolutePath)))
    }

    @Test
    fun taskWithoutPathDeletesNothing() {
        assertFalse(DownloadedFiles.delete(task(filePath = null)))
    }

    @Test
    fun deletingOnlyTouchesThatOneFile() {
        val keep = temp.newFile("keep.bin")
        val drop = temp.newFile("drop.bin")
        assertTrue(DownloadedFiles.delete(task(filePath = drop.absolutePath)))
        assertTrue(keep.exists())
        assertEquals(1, temp.root.listFiles()?.size)
    }
}
