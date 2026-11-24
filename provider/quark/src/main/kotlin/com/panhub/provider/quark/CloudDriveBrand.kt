package com.panhub.provider.quark

/**
 * 一套云盘接口的不同品牌。
 *
 * 夸克与 UC 是同一套 clouddrive 后端（端点、字段、错误码都一样），差别只有
 * 主机名、Referer、风控参数 pr 与 UA。把它们做成数据而不是两份实现：
 * 复制两千多行只为了换四个常量，改一个字段就要改两遍，迟早只改对一半。
 *
 * 事实来源：OpenList drivers/quark_uc/meta.go（两个 driver 共用一份实现），
 * 以及 UC 浏览器安装包里逐字节搜到的 204 条 /1/clouddrive 路径——
 * 本项目用到的端点（file/sort、file、file/rename、file/move、file/delete、
 * file/download、member、share、share/password、share/delete、task、config、
 * 分享的三个 sharepage 接口、上传的四个接口）在 UC 包里全部命中。
 */
data class CloudDriveBrand(
    /** 账号 id 的前缀，也是账号存档里的服务商标识。 */
    val id: String,
    val displayName: String,
    /** 根目录的 pdir_fid，两家都是 "0"。 */
    val rootId: String,
    /** 与 [id] 独立的接口基址。 */
    val baseUrl: String,
    /** 请求头 Referer：服务端按它判断请求来自哪个前端。 */
    val referer: String,
    /** 查询参数 pr：PC 客户端的品牌标识，缺了会被风控拦。 */
    val pr: String,
    /** 官方 PC 客户端 UA，服务端按它下发风控策略。 */
    val userAgent: String,
    /** 分享页的站点根，用来把「只有路径」或「只有 id」的分享补成完整链接。 */
    val shareHost: String,
    /** 登录提示语里那句人话，比如「浏览器登录 pan.quark.cn 后…」。 */
    val authHint: String,
    /**
     * 官方 App 内嵌 WebView 加载的 CAS 登录页（APK dex 里的原文地址）。
     * 夸克：uop.quark.cn/cas/login（classes7.dex）；UC：api.open.uc.cn/cas/login
     * （classes11.dex）。官方 App 登录完成后由 CookieManager 把 Cookie 交给原生层，
     * 我们的 WebView 登录页照做同一件事。
     */
    val webLoginUrl: String,
) {
    companion object {
        /** 夸克：drive.quark.cn，pr=ucpro。 */
        val Quark = CloudDriveBrand(
            id = "quark",
            displayName = "夸克网盘",
            rootId = "0",
            baseUrl = "https://drive.quark.cn/1/clouddrive",
            referer = "https://pan.quark.cn",
            pr = "ucpro",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 " +
                "Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch",
            shareHost = "https://pan.quark.cn",
            authHint = "浏览器登录 pan.quark.cn 后，把整段 Cookie 粘贴进来",
            webLoginUrl = "https://uop.quark.cn/cas/login?client_id=258&target_client_id=4" +
                "&display=mobile&browser_type=html5",
        )

        /** UC：pc-api.uc.cn，pr=UCBrowser。 */
        val Uc = CloudDriveBrand(
            id = "uc",
            displayName = "UC 网盘",
            rootId = "0",
            baseUrl = "https://pc-api.uc.cn/1/clouddrive",
            referer = "https://drive.uc.cn",
            pr = "UCBrowser",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) uc-cloud-drive/2.5.20 Chrome/100.0.4896.160 " +
                "Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch",
            shareHost = "https://drive.uc.cn",
            authHint = "浏览器登录 drive.uc.cn 后，把整段 Cookie 粘贴进来",
            webLoginUrl = "https://api.open.uc.cn/cas/login?client_id=72&target_client_id=4" +
                "&display=mobile&browser_type=html5",
        )
    }
}
