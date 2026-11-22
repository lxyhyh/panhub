# 蓝奏云 / 蓝云

## 取证来源

- 安装包：`蓝云 - v1.3.4.8.apk`（包名 `com.tooyoung.lanzou`）。
  解包后确认它由 AndroLua 构建：`assets/*.lua` 是界面脚本，但**全部加密**，
  无论是 `grep -rE "https?://"` 还是 `strings` 都取不到任何 URL 或域名。
  **结论：这个 APK 对接口取证没有价值。**
- 因此接口事实改用开源客户端 **Tgaisen/LanCloud**（Apache-2.0，Flutter/Dart）的源码：
  `lib/core/api/lanzou_client.dart`、`lib/core/account_login.dart`、
  `lib/core/cookie_auth.dart`、`docs/ARCHITECTURE.md`。
- 下文所有「已确认」条目都直接来自上述 Dart 源码里硬编码的常量与请求体。

## 已确认

### 端点

| 用途 | 方法与地址 |
|---|---|
| 全部增删改查 | `POST https://pc.woozooo.com/doupload.php` |
| 上传 | `https://pc.woozooo.com/html5up.php` |
| 直链换取（旧版） | `https://pc.woozooo.com/ajaxm.php` |
| 直链换取（新版） | 分享页域名 + `/ajaxfile.php` |
| 登录页 / 工作台 | `https://pc.woozooo.com/account.php` / `/mydisk.php` |

### `doupload.php` 的 task 编号

动作靠表单字段 `task` 区分，没有 REST 路径：

| task | 动作 | 主要字段 |
|---|---|---|
| 5 | 文件列表 | `folder_id`、`pg` |
| 47 | 文件夹列表 | query 带 `uid`；body `folder_id` |
| 2 | 新建文件夹 | `parent_id`、`folder_name`、`folder_description` |
| 4 | 改文件夹 | `folder_id`、`folder_name`、`folder_description` |
| 3 | 删文件夹 | `folder_id` |
| 6 | 删文件 | `file_id` |
| 20 | 移动文件 | `file_id`、`folder_id` |
| 12 | 文件信息 | `file_id`（返 `text`=名称、`info`=简介） |
| 46 | 改文件名 | `file_id`、`file_name`、`type=2`，**仅会员** |
| 22 | 文件分享信息 | `file_id` |
| 18 | 文件夹分享信息 | `folder_id`，额外 `pg=''` |
| 23 / 16 | 设文件 / 文件夹密码 | `shows`、`shownames` |

### 响应形状

- 成功判据恒为 `zt == "1"`，**没有错误码**，失败时 `inf` 里可能有一句中文。
- 文件条目：`id`（**字符串**，`-1` 表示被屏蔽）、`name_all` / `name`、
  `size`（形如 `"1.2 M"`）、`time`（`2024-01-02` 或带时分秒）、`downs`、`onof`。
- 文件夹条目：`fol_id` / `id`、`name`、`folder_des`。
- **「还有下一页」的判据是 `info`（本页条数）不为 0**，不是「本页是否满页」。

### 关键常量

- **根目录 id 是 `-1`**，不是 0 也不是空串。
- 登录态是 Cookie：`ylogin=<数字 uid>` + `phpdisk_info=...`。
- 请求间隔 `requestInterval = 300ms`；连接超时 20s、读取超时 60s。
- 网页版用桌面 UA 时才返回带简介表格的分享页模板。

### 直链解析

蓝奏云**没有「创建分享」接口**：每个文件天生就有分享页，`task 22` 拿到的
`is_newd` 与 `f_id` 拼起来就是分享地址；`onof == "1"` 时 `pwd` 是提取码。

换直链有三条路径并存：

1. 分享页 → 内嵌 iframe → `ajaxfile.php`，从页面 `data: {...}` 块取 `sign` / `kd` / `ves`；
2. 新版带密码：`var isngis='<sign>'` 作 sign、`var kdns=` 作 kd；
3. 旧版回退：HTML 里 `'sign':<值>,`，值可能是 JS 变量名，要再查 `var X=...;` 还原。

直链落地：`url` 以 `http` 开头直接用；以 `?` 开头要拼 `$dom/file/` 并先发
`Range: bytes=0-0` 预热；否则 `GET $dom/file/<url>` 不跟随重定向，取 `Location` 头。

### 人机校验 `acw_sc__v2`

服务端在触碰风控时返回一段只含 `arg1='...'` 的 JS 页面。算法：

1. 取 `arg1` 的十六进制字符串，按 40 位置换表重排（不足 40 位尾部留空位，不能丢字符）；
2. 与固定密钥 `3000176000856006061501533003690027800375` 逐字节十六进制异或；
3. 结果写进该域名的 `acw_sc__v2` Cookie，带 Cookie 重试原请求。

已用固定向量验证（见 `LanzouChallengeTest`）：
arg1 = `0123456789ABCDEF0123456789ABCDEF01234567` → `d2c7186598ab1a508a4f6064e4fa746323ab17c6`。

## 推断（尚未用真实账号复核）

- 文件夹列表只取第 1 页：`task 47` 没有页码参数的迹象，按「一次给全」处理。
- `task 46` 的重命名失败一律归因为「需要会员」，也可能撞上命名规则限制。
- 未实现上传：`html5up.php` 需要 `task=1` 的 multipart 表单，字段较多，留待后续单独一轮。

## 与其它网盘的差异（摊平进 `ProviderCapabilities`）

| 能力 | 值 | 原因 |
|---|---|---|
| quota | `false` | 没有容量接口 |
| trash | `false` | 删除不可逆 |
| shareExpiry | `false` | 分享页长期有效 |
| share（创建 / 取消） | 只读语义 | 文件天生带分享页，无法创建或取消 |
| rename | 会员限定 | 免费账号恒失败 |
