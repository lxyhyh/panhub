package com.panhub.provider.baidu

import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanResult
import com.panhub.core.network.HttpEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaiduApiCheckTest {

    private val api = BaiduApi(HttpEngine())

    @Test
    fun errnoZeroIsSuccess() {
        val result = api.checkErrno("""{"errno":0,"list":[]}""")
        assertTrue(result is PanResult.Success)
    }

    @Test
    fun errnoMinusSixIsAuthFailure() {
        val result = api.checkErrno("""{"errno":-6,"request_id":"1"}""")
        assertTrue(result is PanResult.Failure)
        val error = (result as PanResult.Failure).error
        assertEquals(ErrorKind.AUTH, error.kind)
    }

    @Test
    fun showMessageIsPreferred() {
        val result = api.checkErrno(
            """{"errno":2,"show_msg":"为保障您的账号安全，使用分享功能前需完成手机号绑定"}""",
        )
        val error = (result as PanResult.Failure).error
        assertEquals("为保障您的账号安全，使用分享功能前需完成手机号绑定", error.message)
    }

    @Test
    fun missingErrnoIsTreatedAsSuccess() {
        val result = api.checkErrno("""{"list":[]}""")
        assertTrue(result is PanResult.Success)
    }

    @Test
    fun nonJsonIsFailure() {
        val result = api.checkErrno("<html></html>")
        assertTrue(result is PanResult.Failure)
    }
}
