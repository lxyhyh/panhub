package com.panhub.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.panhub.core.model.AccountId
import com.panhub.core.session.SessionManager
import com.panhub.core.transfer.TransferEngine
import com.panhub.feature.transfers.TransfersScreen
import com.panhub.feature.transfers.TransfersViewModel
import com.panhub.feature.accounts.AccountsScreen
import com.panhub.feature.accounts.AccountsViewModel
import com.panhub.feature.browser.BrowserScreen
import com.panhub.feature.browser.BrowserViewModel
import com.panhub.feature.shares.SharesScreen
import com.panhub.feature.shares.SharesViewModel

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
fun PanHubRoot(session: SessionManager, transfers: TransferEngine) {
    val nav = rememberNavController()

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
                    )
                },
            )
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