package com.panhub.provider.lanzou

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

class LanzouParsingTest {

    @Test fun plainByteCountIsPassedThrough() {
        assertEquals(1024L, LanzouParsing.parseSize("1024"))
    }

    @Test fun sizeWithThousandsSeparatorsIsParsed() {
        assertEquals(1024L, LanzouParsing.parseSize("1,024"))
    }

    @Test fun sizeWithUnitIsParsed() {
        assertEquals((1.5 * 1024 * 1024).toLong(), LanzouParsing.parseSize("1.5 M"))
    }

    @Test fun sizeUnitsAreCaseInsensitive() {
        assertEquals(2L * 1024, LanzouParsing.parseSize("2 k"))
    }

    @Test fun sizeWithTrailingBIsAccepted() {
        assertEquals(3L * 1024 * 1024, LanzouParsing.parseSize("3 MB"))
    }

    @Test fun unparsableSizeFallsBackToZero() {
        // 单个坏条目不能让整页解析失败，所以这里返回 0 而不是抛异常
        assertEquals(0L, LanzouParsing.parseSize("未知"))
        assertEquals(0L, LanzouParsing.parseSize(""))
        assertEquals(0L, LanzouParsing.parseSize(null))
    }

    @Test fun dateOnlyIsMidnightInChina() {
        val expected = java.time.LocalDate.parse("2024-01-02")
            .atStartOfDay().toInstant(ZoneOffset.ofHours(8)).toEpochMilli()
        assertEquals(expected, LanzouParsing.parseTime("2024-01-02"))
    }

    @Test fun dateTimeWithSecondsIsParsed() {
        val expected = java.time.LocalDateTime.parse("2024-01-02T03:04:05")
            .toInstant(ZoneOffset.ofHours(8)).toEpochMilli()
        assertEquals(expected, LanzouParsing.parseTime("2024-01-02 03:04:05"))
    }

    @Test fun dateTimeWithMinutesIsParsed() {
        val expected = java.time.LocalDateTime.parse("2024-01-02T03:04")
            .toInstant(ZoneOffset.ofHours(8)).toEpochMilli()
        assertEquals(expected, LanzouParsing.parseTime("2024-01-02 03:04"))
    }

    @Test fun unparsableTimeIsNull() {
        assertNull(LanzouParsing.parseTime("昨天"))
        assertNull(LanzouParsing.parseTime(null))
    }

    @Test fun spacesBecomeUnderscores() {
        assertEquals("my_folder", LanzouParsing.sanitizeFolderName("my folder"))
    }

    @Test fun illegalCharactersAreDropped() {
        // 服务端拒绝这批符号且不说明原因，所以客户端先按同一规则清洗
        assertEquals("abcde", LanzouParsing.sanitizeFolderName("a/b:c?d*e"))
    }

    @Test fun uidIsExtractedFromCookie() {
        assertEquals("123456", LanzouParsing.uidFromCookie("ylogin=123456; phpdisk_info=abc"))
    }

    @Test fun missingUidIsNull() {
        assertNull(LanzouParsing.uidFromCookie("phpdisk_info=abc"))
    }
}
