package com.panhub.feature.accounts

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.ViewGroup
import com.panhub.core.model.ProviderDescriptor

/**
 * 内嵌 WebView 登录页。
 *
 * 夸克/UC/百度的官方 App 登录方式就是内嵌 WebView 开自家的 CAS/H5 登录页，
 * 登录完成后 CookieManager 把 Cookie 交给原生层（dex 里 CookieManager +
 * WebViewClient 佐证）。这里照做同一件事：加载 [descriptor] 声明的官方地址，
 * 登录成功（目标 Cookie 齐了）时把整段 Cookie 交给回调，由调用方走既有的
 * Token 登录链路验证并保存——provider 层一行都不用改。
 *
 * [requiredCookies] 是判定登录成功的 Cookie 键：夸克/UC 是 kps/ppu/puus，
 * 百度是 BDUSS。齐了才算登录，避免拿半截 Cookie 去验证。
 *
 * Cookie 检查两个时机都做：每页加载完（onPageFinished）+ 系统级 Cookie
 * 变更回调（registerCookieChangeCallback）——JS 写的 Cookie 不一定伴随页面跳转。
 */
@Composable
internal fun LoginWebViewDialog(
    descriptor: ProviderDescriptor,
    requiredCookies: List<String>,
    busy: Boolean,
    onCookies: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // 只交一次：CookieManager 的回调可能连发多次。
    val delivered = remember { mutableStateOf(false) }
    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                // 0.55 屏高由调用处约束；这里给一个下限，避免 0 高度。
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    DisposableEffect(descriptor.id) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        val check = Runnable {
            if (delivered.value) return@Runnable
            val cookie = cm.getCookie(descriptor.webLoginUrl ?: return@Runnable) ?: return@Runnable
            val names = cookie.split(";").mapNotNull {
                it.trim().substringBefore("=").takeIf { k -> k.isNotEmpty() }
            }
            val hit = requiredCookies.isNotEmpty() && requiredCookies.all { it in names }
            if (hit) {
                delivered.value = true
                onCookies(cookie)
            }
        }
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString = null
        // 官方 CAS 在多域之间 302；让 WebView 自己跳，只在 Cookie 齐时收。
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

            override fun onPageFinished(view: WebView, url: String) {
                cm.setAcceptThirdPartyCookies(view, true)
                cm.flush()
                check.run()
            }
        }
        // CookieChangeCallback 在部分编译环境不可解析（SDK 变体差异），
        // 用轻量轮询兜底：CAS 多域 302 的最后一跳会触发 onPageFinished，
        // JS 写 Cookie 的场景由轮询补上。
        val poller = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        poller.scheduleWithFixedDelay(check, 500, 500, java.util.concurrent.TimeUnit.MILLISECONDS)
        webView.loadUrl(descriptor.webLoginUrl.orEmpty())
        onDispose {
            poller.shutdownNow()
            webView.stopLoading()
            webView.destroy()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("登录 " + descriptor.displayName) },
        text = {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxWidth().height(480.dp),
            )
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
/**
 * 判定「登录成功」需要的 Cookie 键，按官方 App 实际写入的键：
 * - 夸克/UC：CAS 登录完成后 Cookie 里有 kps/ppu（登录取证：夸克 PC 与 App 共用）；
 *   __puus 是服务端轮换的风控键，初始不一定有，不作为判定条件。
 * - 百度：BDUSS 是身份主键，STOKEN 是辅助键；官方 App 只认 BDUSS。
 * - 其余（123/坚果云/蓝奏/天翼）不用 WebView 登录，返回空表。
 */
internal fun webLoginCookiesOf(providerId: String): List<String> = when (providerId) {
    "quark", "uc" -> listOf("kps", "ppu")
    "baidu" -> listOf("BDUSS")
    else -> emptyList()
}
