package com.panhub.feature.offline

import com.panhub.core.model.OfflineFile
import com.panhub.core.model.OfflineKind
import com.panhub.core.model.OfflineResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineLogicTest {

    private val files = listOf(
        OfflineFile("1", "a.mkv", 100L),
        OfflineFile("2", "b.srt", 5L),
    )
    private val resource = OfflineResource("9", "影片", OfflineKind.LINK, files)

    @Test
    fun submitNeedsAResourceAndASelection() {
        assertFalse(OfflineLogic.canSubmit(busy = false, resource = null, selected = setOf("1")))
        assertFalse(OfflineLogic.canSubmit(busy = false, resource = resource, selected = emptySet()))
        assertTrue(OfflineLogic.canSubmit(busy = false, resource = resource, selected = setOf("1")))
    }

    @Test
    fun busyBlocksSubmitEvenWithASelection() {
        // 提交中再点一次会建两个相同的任务，服务端不会去重。
        assertFalse(OfflineLogic.canSubmit(busy = true, resource = resource, selected = setOf("1")))
    }

    @Test
    fun everythingChosenSaysHowMany() {
        assertEquals(
            "已提交离线任务，影片 里的 2 项正在下载",
            OfflineLogic.submittedMessage(resource, setOf("1", "2")),
        )
    }

    @Test
    fun partialSelectionSaysHowManyWereChosen() {
        assertEquals("已提交离线任务，选了 1 项", OfflineLogic.submittedMessage(resource, setOf("1")))
    }

    @Test
    fun noSelectionStillHasAMessage() {
        // 理论上提交按钮是灰的，但消息不该出现空字符串。
        assertTrue(OfflineLogic.submittedMessage(resource, emptySet()).isNotBlank())
    }

    @Test
    fun resourceWithOnlyFilesCountsFiles() {
        assertEquals("2 个文件", OfflineLogic.describeResource(resource))
    }

    @Test
    fun resourceWithFoldersMentionsThem() {
        // BT 任务里经常夹着目录，只报「3 项」会让人以为都是文件。
        val mixed = OfflineResource(
            "9",
            "剧集",
            OfflineKind.BT,
            listOf(
                OfflineFile("1", "S01", 0L, isDir = true),
                OfflineFile("2", "a.mkv", 100L),
                OfflineFile("3", "b.srt", 5L),
            ),
        )
        assertEquals("3 项（含 1 个文件夹）", OfflineLogic.describeResource(mixed))
    }

    @Test
    fun allFoldersSaysFolders() {
        val only = OfflineResource(
            "9",
            "空目录",
            OfflineKind.BT,
            listOf(OfflineFile("1", "a", 0L, isDir = true)),
        )
        assertEquals("1 个文件夹", OfflineLogic.describeResource(only))
    }

    @Test
    fun emptyResourceHasNoFiles() {
        assertEquals("没有可以保存的文件", OfflineLogic.describeResource(resource.copy(files = emptyList())))
    }

    @Test
    fun toggleAddsAndRemoves() {
        assertEquals(setOf("1"), OfflineLogic.toggle(emptySet(), "1"))
        assertEquals(emptySet<String>(), OfflineLogic.toggle(setOf("1"), "1"))
    }

    @Test
    fun toggleAllSelectsThenClears() {
        assertEquals(setOf("1", "2"), OfflineLogic.toggleAll(emptySet(), files))
        assertEquals(emptySet<String>(), OfflineLogic.toggleAll(setOf("1", "2"), files))
        // 选了一部分时点「全选」是补齐而不是清空。
        assertEquals(setOf("1", "2"), OfflineLogic.toggleAll(setOf("1"), files))
    }

    @Test
    fun toggleAllOnEmptyListSelectsNothing() {
        assertEquals(emptySet<String>(), OfflineLogic.toggleAll(emptySet(), emptyList()))
    }

    @Test
    fun failureMessageFallsBackWhenBlank() {
        assertEquals("这个链接没能解析出内容", OfflineLogic.failureMessage(null))
        assertEquals("这个链接没能解析出内容", OfflineLogic.failureMessage("   "))
        assertEquals("链接已失效", OfflineLogic.failureMessage("链接已失效"))
    }
}
