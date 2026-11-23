package com.panhub.provider.quark

import com.panhub.core.common.PanResult
import com.panhub.core.network.HttpEngine
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同一套 clouddrive 接口的两份品牌配置。
 *
 * 这里钉的是「别把 UC 悄悄当成夸克」：主机名、Referer、pr、UA 任何一个漏换，
 * 请求都会被发到夸克去，或者被 UC 的风控当成未知客户端拦下来，
 * 而症状只是「登录上了但一直失败」，从错误信息里根本看不出来。
 */
class CloudDriveBrandTest {

    @Test
    fun theTwoBrandsUseDifferentHosts() {
        assertNotEquals(CloudDriveBrand.Quark.baseUrl, CloudDriveBrand.Uc.baseUrl)
        assertEquals("https://drive.quark.cn/1/clouddrive", CloudDriveBrand.Quark.baseUrl)
        assertEquals("https://pc-api.uc.cn/1/clouddrive", CloudDriveBrand.Uc.baseUrl)
    }

    @Test
    fun theRiskControlParametersMatchTheBrand() {
        // pr 是服务端识别客户端的依据，UC 用 UCBrowser、夸克用 ucpro，
        // 用错的话请求能发出去但会被当成异常客户端。
        assertEquals("ucpro", CloudDriveBrand.Quark.pr)
        assertEquals("UCBrowser", CloudDriveBrand.Uc.pr)
    }

    @Test
    fun refererPointsAtTheBrandOwnWebDrive() {
        assertEquals("https://pan.quark.cn", CloudDriveBrand.Quark.referer)
        assertEquals("https://drive.uc.cn", CloudDriveBrand.Uc.referer)
    }

    @Test
    fun theUserAgentNamesTheBrandOwnClient() {
        assertTrue(CloudDriveBrand.Quark.userAgent.contains("quark-cloud-drive"))
        assertTrue(CloudDriveBrand.Uc.userAgent.contains("uc-cloud-drive"))
    }

    @Test
    fun accountIdsCannotCollideBetweenBrands() {
        // 两家都靠 Cookie 的 hashCode 认账号，前缀不同才不会串号。
        assertNotEquals(CloudDriveBrand.Quark.id, CloudDriveBrand.Uc.id)
        assertEquals("quark", CloudDriveBrand.Quark.id)
        assertEquals("uc", CloudDriveBrand.Uc.id)
    }

    @Test
    fun bothBrandsShareTheSameRootFolderId() {
        // 同一套后端，根目录都是 "0"；写死成别的值会让新建/移动全落到根上。
        assertEquals("0", CloudDriveBrand.Quark.rootId)
        assertEquals("0", CloudDriveBrand.Uc.rootId)
    }

    @Test
    fun displayNameAndHintMentionTheBrandOwnSite() {
        assertEquals("夸克网盘", CloudDriveBrand.Quark.displayName)
        assertEquals("UC 网盘", CloudDriveBrand.Uc.displayName)
        assertTrue(CloudDriveBrand.Uc.authHint.contains("drive.uc.cn"))
        assertTrue(CloudDriveBrand.Quark.authHint.contains("pan.quark.cn"))
    }

    @Test
    fun shareLinksAreBuiltOnTheBrandOwnHost() {
        assertEquals(
            "https://drive.uc.cn/s/abc",
            QuarkShareParsing.normalizeUrl("abc", CloudDriveBrand.Uc.shareHost),
        )
        assertEquals(
            "https://pan.quark.cn/s/abc",
            QuarkShareParsing.normalizeUrl("abc", CloudDriveBrand.Quark.shareHost),
        )
    }

    @Test
    fun theApiSendsTheBrandOwnHeadersAndParameter() {
        val seen = mutableListOf<Triple<String, String?, String?>>()
        val server = Interceptor { chain ->
            val request = chain.request()
            seen += Triple(
                request.url.encodedPath,
                request.header("Referer"),
                request.url.queryParameter("pr"),
            )
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"status":200,"code":0}""".toResponseBody("application/json".toMediaType()))
                .build()
        }
        val api = QuarkApi(
            HttpEngine(okhttp3.OkHttpClient.Builder().addInterceptor(server).build()),
            CloudDriveBrand.Uc,
        )
        val result = runBlockingTest { api.get(QuarkApi.PATH_MEMBER, "cookie") }
        assertTrue(result is PanResult.Success)
        assertEquals(1, seen.size)
        assertEquals("https://drive.uc.cn", seen[0].second)
        assertEquals("UCBrowser", seen[0].third)
    }

    private fun <T> runBlockingTest(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }
}
