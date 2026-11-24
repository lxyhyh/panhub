package com.panhub.feature.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.panhub.core.design.EmptyState
import com.panhub.core.design.LoadingState
import com.panhub.core.design.PanCard
import com.panhub.core.design.QuotaBar
import com.panhub.core.design.Radius
import com.panhub.core.design.Spacing
import com.panhub.core.model.AccountId
import com.panhub.core.model.AccountNames
import com.panhub.core.model.AuthMethod
import com.panhub.core.model.ProviderDescriptor
import com.panhub.core.model.Quota
import com.panhub.core.session.AccountSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 账号列表页。蓝云风格：浅灰底 + 白卡片 + 大留白。
 *
 * 这一页只做三件事：看有哪些账号、加一个、管一个。
 * 点卡片进文件浏览由 [onOpenAccount] 交给导航层，本页不认识文件浏览。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    viewModel: AccountsViewModel,
    onOpenAccount: (AccountId) -> Unit,
    onOpenTransfers: () -> Unit = {},
    onOpenShares: (AccountId) -> Unit = {},
    onOpenShareImport: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenLocalFiles: () -> Unit = {},
    onOpenTrash: (AccountId) -> Unit = {},
    onOpenStarred: (AccountId) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val reauth by viewModel.reauth.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingProvider by remember { mutableStateOf<ProviderDescriptor?>(null) }
    var pendingDelete by remember { mutableStateOf<AccountSummary?>(null) }
    var pendingAlias by remember { mutableStateOf<AccountSummary?>(null) }

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("账号") },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Default.Search, contentDescription = "搜索全部网盘")
                    }
                    IconButton(onClick = onOpenShareImport) {
                        Icon(Icons.Default.FileDownload, contentDescription = "打开分享转存")
                    }
                    IconButton(onClick = onOpenLocalFiles) {
                        Icon(Icons.Default.Folder, contentDescription = "本机文件")
                    }
                    IconButton(onClick = onOpenTransfers) {
                        Icon(Icons.Default.SwapVert, contentDescription = "传输列表")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { pendingProvider = PENDING_ADD },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加账号")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            when {
                state.loading -> LoadingState()
                state.accounts.isEmpty() -> EmptyState(
                    icon = Icons.Default.CloudQueue,
                    title = "还没有账号",
                    message = "点右下角的加号，接入第一个网盘",
                )
                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        top = Spacing.sm,
                        bottom = Spacing.xxl + Spacing.xxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    // 接了第二个网盘之后才出现：只有一个账号时，卡片上的容量条
                    // 已经说得很清楚，再在上面加一行总账只是把同一件事说两遍。
                    state.quotaSummary?.let { summary ->
                        item(key = "quota-summary") { QuotaSummaryCard(summary) }
                    }
                    items(state.accounts, key = { it.id.value }) { account ->
                        AccountCard(
                            account = account,
                            quota = state.quotas[account.id.value],
                            provider = state.providerOf(account.providerId),
                            onClick = { onOpenAccount(account.id) },
                            onVerify = { viewModel.verify(account.id) },
                            onReauth = { viewModel.startReauth(account) },
                            onDelete = { pendingDelete = account },
                            onRename = { pendingAlias = account },
                            onOpenShares = { onOpenShares(account.id) },
                            onOpenTrash = { onOpenTrash(account.id) },
                            onOpenStarred = { onOpenStarred(account.id) },
                        )
                    }
                }
            }
        }
    }

    pendingProvider?.let { provider ->
        if (provider == PENDING_ADD) {
            ProviderPickerDialog(
                providers = state.providers,
                onDismiss = { pendingProvider = null },
                onPick = { picked ->
                    pendingProvider = null
                    viewModel.startLogin(picked.id)
                },
            )
        }
    }

    form.providerId?.let { providerId ->
        val descriptor = state.providerOf(providerId)
        if (descriptor?.authMethod == AuthMethod.WEBVIEW) {
            // 与官方 App 一致：内嵌 WebView 开官方登录页，Cookie 齐了自动提交。
            // Cookie 抓回来当 Token 用，走同一条验证链路，provider 层无感知。
            LoginWebViewDialog(
                descriptor = descriptor,
                requiredCookies = webLoginCookiesOf(providerId.value),
                busy = state.busy,
                onCookies = { cookie ->
                    viewModel.setToken(cookie)
                    viewModel.setMethod(AuthMethod.TOKEN)
                    viewModel.submit()
                },
                onDismiss = viewModel::cancelLogin,
            )
        } else {
            LoginDialog(
                descriptor = descriptor,
                form = form,
                busy = state.busy,
                onMethodChange = viewModel::setMethod,
                onUsernameChange = viewModel::setUsername,
                onPasswordChange = viewModel::setPassword,
                onTokenChange = viewModel::setToken,
                onSubmit = viewModel::submit,
                onDismiss = viewModel::cancelLogin,
            )
        }
    }

    pendingDelete?.let { account ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("移除账号") },
            text = { Text("确定移除 ${account.label} 吗？本地保存的凭据会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.logout(account.id)
                    pendingDelete = null
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    pendingAlias?.let { account ->
        AliasDialog(
            account = account,
            onDismiss = { pendingAlias = null },
            onConfirm = { value ->
                viewModel.rename(account.id, value)
                pendingAlias = null
            },
        )
    }

    // 「更新登录」对话框：与登录表单共用一套输入，但只换凭据不动账号。
    reauth?.let { target ->
        ReauthDialog(
            target = target,
            descriptor = state.providerOf(target.account.providerId),
            busy = state.busy,
            onMethodChange = viewModel::setReauthMethod,
            onUsernameChange = viewModel::setReauthUsername,
            onPasswordChange = viewModel::setReauthPassword,
            onTokenChange = viewModel::setReauthToken,
            onSubmit = viewModel::submitReauth,
            onDismiss = viewModel::cancelReauth,
        )
    }
}

/**
 * 用哨兵对象表示"正在选网盘"。
 *
 * 用 ProviderDescriptor 本身做状态就只能表示"已选中某个网盘"，
 * 没法表示"弹出选择框但还没选"。
 */
private val PENDING_ADD = ProviderDescriptor(
    id = com.panhub.core.model.ProviderId("__picker__"),
    displayName = "",
    capabilities = com.panhub.core.model.ProviderCapabilities(),
)

@Composable
private fun AccountCard(
    account: AccountSummary,
    quota: Quota?,
    provider: ProviderDescriptor?,
    onClick: () -> Unit,
    onVerify: () -> Unit,
    onReauth: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onOpenShares: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenStarred: () -> Unit,
) {
    PanCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = account.providerName.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(
                modifier = Modifier.weight(1f).padding(start = Spacing.md),
            ) {
                Text(
                    text = account.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = buildString {
                        // 起了备注名时，真名要跟一行跟上：备注是给人认的，
                        // 但同一家网盘有两个账号时，只有备注分不清谁是谁。
                        if (account.alias != null) {
                            append(account.displayName)
                            append(" · ")
                        }
                        append(account.providerName)
                        append(" · ")
                        append(lastUsedText(account.lastUsedAt))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onVerify) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "检查登录状态",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onReauth) {
                Icon(
                    Icons.Default.Login,
                    contentDescription = "更新登录",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRename) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "改备注名",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "移除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 查到容量就画一条，查不到（不支持、凭据过期、还在路上）就不画。
        // 原来这里写的是「支持容量查询」，那句话对用户没有任何用处。
        if (quota != null) {
            QuotaBar(
                used = quota.used,
                total = quota.total,
                modifier = Modifier.padding(top = Spacing.md),
            )
        }
        // 只有声明了分享列表的服务商才给入口：点了必然失败的入口比没有入口更糟。
        if (account.capabilities.shareList) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm)
                    .clickable(onClick = onOpenShares),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "我的分享",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = Spacing.xs),
                )
            }
        }
        // 同上：只有声明了回收站的服务商才给入口。
        // 这个网盘删掉的东西本来就没地方找，给个点进去必然空的入口只会让人困惑。
        if (account.capabilities.trash) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm)
                    .clickable(onClick = onOpenTrash),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "回收站",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = Spacing.xs),
                )
            }
        }
        // 收藏夹同理：不支持收藏的网盘点进去只会是空的。
        if (account.capabilities.star) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm)
                    .clickable(onClick = onOpenStarred),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "收藏夹",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = Spacing.xs),
                )
            }
        }
    }
}

/**
 * 所有账号的容量总账。
 *
 * 不做汇总条图：各家的「总量」含义并不统一（有的含回收站、有的算上家庭空间），
 * 把它们的比例叠在一起画会暗示一种并不存在的可比性。只报数字。
 */
@Composable
private fun QuotaSummaryCard(summary: AccountsLogic.QuotaSummary) {
    PanCard {
        Column {
            Text(
                text = "全部网盘",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = AccountsLogic.summaryLine(summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPickerDialog(
    providers: List<ProviderDescriptor>,
    onDismiss: () -> Unit,
    onPick: (ProviderDescriptor) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择网盘") },
        text = {
            Column {
                if (providers.isEmpty()) {
                    Text("没有可用的网盘", style = MaterialTheme.typography.bodyMedium)
                }
                providers.forEach { provider ->
                    TextButton(
                        onClick = { onPick(provider) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = provider.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            provider.authHint?.let { hint ->
                                Text(
                                    text = hint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReauthDialog(
    target: AccountsViewModel.ReauthTarget,
    descriptor: ProviderDescriptor?,
    busy: Boolean,
    onMethodChange: (AuthMethod) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val form = target.form
    val allowBoth = descriptor?.authMethod == AuthMethod.BOTH
    val useToken = form.method == AuthMethod.TOKEN

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("更新 ${target.account.label} 的登录") },
        text = {
            Column {
                Text(
                    text = "凭据过期就换一份新的，账号本身不会变。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Spacing.md),
                )
                descriptor?.authHint?.let { hint ->
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Spacing.md),
                    )
                }
                if (allowBoth) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.md),
                    ) {
                        SegmentedButton(
                            selected = !useToken,
                            onClick = { onMethodChange(AuthMethod.PASSWORD) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("账号密码") }
                        SegmentedButton(
                            selected = useToken,
                            onClick = { onMethodChange(AuthMethod.TOKEN) },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("令牌") }
                    }
                }
                if (useToken) {
                    OutlinedTextField(
                        value = form.token,
                        onValueChange = onTokenChange,
                        label = { Text("Access Token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = form.username,
                        onValueChange = onUsernameChange,
                        label = { Text(if (descriptor?.id?.value == "nutstore") "邮箱" else "账号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Spacing.md))
                    OutlinedTextField(
                        value = form.password,
                        onValueChange = onPasswordChange,
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSubmit,
                enabled = form.canSubmit && !busy,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("更新")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

@Composable
private fun LoginDialog(
    descriptor: ProviderDescriptor?,
    form: LoginFormState,
    busy: Boolean,
    onMethodChange: (AuthMethod) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val allowBoth = descriptor?.authMethod == AuthMethod.BOTH
    val useToken = form.method == AuthMethod.TOKEN

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("登录 ${descriptor?.displayName ?: ""}") },
        text = {
            Column {
                descriptor?.authHint?.let { hint ->
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Spacing.md),
                    )
                }
                if (allowBoth) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.md),
                    ) {
                        SegmentedButton(
                            selected = !useToken,
                            onClick = { onMethodChange(AuthMethod.PASSWORD) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("账号密码") }
                        SegmentedButton(
                            selected = useToken,
                            onClick = { onMethodChange(AuthMethod.TOKEN) },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("令牌") }
                    }
                }
                if (useToken) {
                    OutlinedTextField(
                        value = form.token,
                        onValueChange = onTokenChange,
                        label = { Text("Access Token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = form.username,
                        onValueChange = onUsernameChange,
                        label = { Text(if (descriptor?.id?.value == "nutstore") "邮箱" else "账号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Spacing.md))
                    OutlinedTextField(
                        value = form.password,
                        onValueChange = onPasswordChange,
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSubmit,
                enabled = form.canSubmit && !busy,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("登录")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

/**
 * 改备注名的对话框。
 *
 * 输入边改边归一化显示的是「提交后会存成什么」——用户敲一串空格时
 * 会立刻看到结果为空，而不是点确定之后才发现备注没生效。
 */
@Composable
private fun AliasDialog(
    account: AccountSummary,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(account.alias ?: "") }
    val preview = AccountNames.normalize(text)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("改备注名") },
        text = {
            Column {
                Text(
                    text = "当前账号：\${account.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Spacing.md),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("备注名") },
                    placeholder = { Text(account.displayName) },
                    singleLine = true,
                    supportingText = {
                        Text(
                            if (preview == null) {
                                "留空就是不使用备注名"
                            } else {
                                "最长 \${AccountNames.MAX_LENGTH} 个字"
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun lastUsedText(timestamp: Long): String {
    if (timestamp <= 0L) return "从未使用"
    val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return formatter.format(Date(timestamp))
}