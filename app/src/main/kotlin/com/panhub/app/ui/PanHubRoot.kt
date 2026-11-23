package com.panhub.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.panhub.core.model.AccountId
import com.panhub.core.preview.PreviewLoader
import com.panhub.core.session.SessionManager
import com.panhub.core.transfer.TransferEngine
import com.panhub.feature.transfers.TransfersScreen
import com.panhub.feature.transfers.TransfersViewModel
import com.panhub.feature.accounts.AccountsScreen
import com.panhub.feature.accounts.AccountsViewModel
import com.panhub.feature.browser.BrowserScreen
import com.panhub.feature.browser.BrowserViewModel
import com.panhub.feature.shareimport.ShareImportScreen
import com.panhub.feature.shareimport.SharedLink
import com.panhub.feature.shareimport.ShareImportViewModel
import com.panhub.core.platform.SettingsRepository
import com.panhub.feature.settings.AppInfo
import com.panhub.feature.settings.SettingsScreen
import com.panhub.feature.settings.SettingsViewModel
import com.panhub.feature.search.SearchScreen
import com.panhub.feature.search.SearchViewModel
import com.panhub.feature.shares.SharesScreen
import com.panhub.feature.shares.SharesViewModel
import com.panhub.core.model.NodeFocus

/**
 * 导航图。
 *
 * 只有两条真实路由：账号列表、文件浏览。刻意不用多 tab 底部栏——
 * 蓝云的形态是"一层一层进"，回来后就是账号列表，视觉噪音最小。
 *
 * 这个文件是唯一知道"有哪些 feature"的地方：feature 模块之间互不依赖，
 * 也都不认识导航，加一个新页面只改这里。
 */
@Composable
fun PanHubRoot(
    session: SessionManager,
    transfers: TransferEngine,
    previews: PreviewLoader,
    settings: SettingsRepository,
    info: AppInfo,
    share: SharedLink? = null,
    onShareConsumed: () -> Unit = {},
) {
    val nav = rememberNavController()
    // 从系统分享菜单进来的链接先停在这里，等转存页把 ViewModel 建出来再清掉：
    // 直接塞进路由参数要先编码再解码，链接里带 ? 与 & 时很容易被拆坏。
    val pendingShare = remember { mutableStateOf<SharedLink?>(share) }
    // 全局搜索点中的那一项。和链接一样走内存传参而不是路由参数：
    // NodeFocus 里有文件 id 与目录名，编码进 URL 容易在特殊字符上出错。
    val pendingFocus = remember { mutableStateOf<NodeFocus?>(null) }

    LaunchedEffect(share) {
        val incoming = share ?: return@LaunchedEffect
        pendingShare.value = incoming
        nav.navigate(Routes.SHARE_IMPORT)
        onShareConsumed()
    }

    NavHost(navController = nav, startDestination = Routes.ACCOUNTS) {
        composable(Routes.ACCOUNTS) {
            val vm: AccountsViewModel = viewModel(factory = factoryOf { AccountsViewModel(session) })
            AccountsScreen(
                viewModel = vm,
                onOpenAccount = { accountId ->
                    nav.navigate(Routes.browser(accountId.value))
                },
                onOpenTransfers = { nav.navigate(Routes.TRANSFERS) },
                onOpenShares = { account -> nav.navigate(Routes.shares(account.value)) },
                onOpenShareImport = { nav.navigate(Routes.SHARE_IMPORT) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenSearch = { nav.navigate(Routes.SEARCH) },
            )
        }
        composable(
            route = Routes.BROWSER,
            arguments = listOf(navArgument(Routes.ARG_ACCOUNT) { type = NavType.StringType }),
        ) { entry ->
            val raw = entry.arguments?.getString(Routes.ARG_ACCOUNT).orEmpty()
            val vm: BrowserViewModel = viewModel(
                factory = factoryOf {
                    // 同一个 TransferEngine 同时是下载与上传入口：它自己排队，
                    // 这里不需要分清是哪一种，只把宽接口收窄成两个窄接口传进去。
                    BrowserViewModel(
                        session = session,
                        accountId = AccountId(raw),
                        launcher = transfers,
                        uploader = transfers,
                        copier = transfers,
                        previewer = previews,
                        focus = pendingFocus.value,
                    )
                },
            )
            LaunchedEffect(Unit) { pendingFocus.value = null }
            BrowserScreen(viewModel = vm, onBack = { nav.popBackStack() })
        }
        composable(
            route = Routes.SHARES,
            arguments = listOf(navArgument(Routes.ARG_ACCOUNT) { type = NavType.StringType }),
        ) { entry ->
            val raw = entry.arguments?.getString(Routes.ARG_ACCOUNT).orEmpty()
            val vm: SharesViewModel = viewModel(
                factory = factoryOf { SharesViewModel(session, AccountId(raw)) },
            )
            SharesScreen(viewModel = vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.SHARE_IMPORT) {
            val vm: ShareImportViewModel = viewModel(
                factory = factoryOf {
                    val shared = pendingShare.value
                    ShareImportViewModel(
                        session = session,
                        initialUrl = shared?.url.orEmpty(),
                        initialPasscode = shared?.passcode,
                    )
                },
            )
            LaunchedEffect(Unit) { pendingShare.value = null }
            ShareImportScreen(viewModel = vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.SEARCH) {
            val vm: SearchViewModel = viewModel(factory = factoryOf { SearchViewModel(session) })
            SearchScreen(
                viewModel = vm,
                onBack = { nav.popBackStack() },
                onOpenHit = { accountId, focus ->
                    pendingFocus.value = focus
                    nav.navigate(Routes.browser(accountId.value))
                },
            )
        }
        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(
                factory = factoryOf { SettingsViewModel(settings, info) },
            )
            SettingsScreen(viewModel = vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.TRANSFERS) {
            val vm: TransfersViewModel = viewModel(
                factory = factoryOf { TransfersViewModel(transfers) },
            )
            TransfersScreen(viewModel = vm, onBack = { nav.popBackStack() })
        }
    }
}

private object Routes {
    const val ACCOUNTS = "accounts"
    const val TRANSFERS = "transfers"
    const val SHARE_IMPORT = "share-import"
    const val SETTINGS = "settings"
    const val SEARCH = "search"
    const val ARG_ACCOUNT = "accountId"
    const val BROWSER = "browser/{$ARG_ACCOUNT}"
    const val SHARES = "shares/{$ARG_ACCOUNT}"

    /** 账号 id 里可能含 ':' 和 '/'，必须编码，否则会拆坏路由。 */
    fun browser(accountId: String): String =
        "browser/" + java.net.URLEncoder.encode(accountId, "UTF-8")

    fun shares(accountId: String): String =
        "shares/" + java.net.URLEncoder.encode(accountId, "UTF-8")
}

/**
 * 把 lambda 包成 ViewModelProvider.Factory。
 *
 * 用 viewModel(factory = ...) 而不是 ViewModelProvider 是因为
 * Navigation Compose 的每个 back stack entry 有独立的 ViewModelStore，
 * 返回再进入时状态自然重建，不需要手写作用域管理。
 */
private inline fun <reified VM : ViewModel> factoryOf(
    crossinline create: () -> VM,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}