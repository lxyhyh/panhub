# 百度网盘

包名 `com.baidu.netdisk`，版本 13.11.15（split APKS）。

## 事实来源与可信度

**APK 对接口取证价值很低**：网盘主体由大量 dex + native 库构成，业务参数散落在各种
生成的代码里，没有可读的常量表。直接抓包也不可行（需要真机登录）。

本文件记录的是**实测过的端点**：用 python3 对 `pan.baidu.com` 直接发请求，
观察返回码判断端点是否存在、以及未登录时的错误形状。每条都标注了实测结果。

## 为什么不接官方开放平台

官方 xpan 开放平台（`pan.baidu.com/rest/2.0/xpan/*`）要求 `client_id` / `client_secret`，
需要在百度开发者平台建应用并审核，且个人项目通常拿不到网盘权限。
本项目改用**网页版接口 + Cookie**（含 `BDUSS`、`STOKEN`），与网页版行为一致。

实测（2026-11-22，无凭证）：

| 请求 | 结果 |
| --- | --- |
| `GET /rest/2.0/xpan/file?method=list&access_token=x&dir=/` | 200 `{"errno":-6}` |
| `GET /rest/2.0/xpan/multimedia?method=listall&access_token=x&dir=/` | 400 `{"errmsg":"Invalid Bduss","errno":-6}` |
| `GET /rest/2.0/xpan/nas?method=uinfo&access_token=x` | 200 `{"errno":20017,"show_msg":"nas token无效"}` |
| `GET /oauth/2.0/token?...&client_id=y` | 401 `{"error":"invalid_client"}` |
| `GET /api/list?dir=%2F...` | 200 `{"errno":-6}` |
| `GET /api/quota?...` | 400 `{"errmsg":"用户未登录","errno":-6}` |
| `GET /share/set?period=0&pwd=ab12&eflag=0&schannel=4...` | 200 `{"errno":2,"show_msg":"为保障您的账号安全，使用分享功能前需完成手机号绑定"}` |
| `GET /share/record?page=1&perpage=20...` | 200 `{"errno":-6,"count":0,"list":[]...}` |
| `GET /api/definitelyNotRealXYZ`（对照） | **404 空体** |

对照实验很关键：不存在的路径返回 **404 + 空体**，与业务错误码明显不同，
所以「返回 errno」确实能证明端点存在。`/share/set` 与 `/share/record` 都返回业务错误
而不是 404，说明分享端点存在。

## 端点

| 用途 | 方法 | 路径 | 关键参数 |
| --- | --- | --- | --- |
| 列目录 | GET | `/api/list` | `dir` `order=name` `desc=0` `page` `num` |
| 容量 | GET | `/api/quota` | `checkfree=1` `checkexpire=1` |
| 下载链接 | GET | `/api/filemetas` | `target=["路径"]` `dlink=1` → `list[0].dlink` |
| 下载链接（开放） | GET | `/rest/2.0/xpan/multimedia` | `method=filemetas` `fsids=[id]` `dlink=1` |
| 建目录 | POST | `/api/create` | `method=create` `a=commit` `path` `isdir=1` `block_list=[]` |
| 改名/移动/复制/删除 | POST | `/rest/2.0/xpan/file` | `method=filemanager` `opera=rename\|move\|copy\|delete` `async=0` `filelist=<JSON>` `ondup=fail` |
| 创建分享 | POST | `/share/set` | `period` `pwd` `eflag=0` `schannel=4` `fid_list=<JSON>` |
| 取消分享 | POST | `/share/cancel` | `shareid` |
| 用户信息 | GET | `/rest/2.0/xpan/nas` | `method=uinfo` |

所有请求都带固定 query：`channel=chunlei&app_id=250528&web=1&clienttype=0`。

## 为什么节点 id 就是路径

百度网盘是**路径寻址**的：改名的 `filelist` 要 `{"path", "newname"}`，
移动要 `{"path", "dest", "newname"}`，删除要 `["path"]`，分享要 `fid_list` 之外的路径。
`fs_id` 只在列表响应里出现，且无法从 fs_id 反查父目录路径。

所以本实现直接把绝对路径（如 `/我的文档/报告.pdf`）当 `NodeId`。代价是移动后 id 变化，
但这正是百度的语义——刷新后拿到新路径就是对的。

## 文件条目字段

`fs_id`、`path`、`server_filename`、`size`、`isdir`（1=目录）、`server_mtime`、`server_ctime`（秒）、`category`（1=视频）、`thumbs.url3`（缩略图）。

注意：百度返回的 `md5` 不可信，OpenList 明确不使用它。

## 下载

`/api/filemetas` 拿到的 `dlink` 必须带 `User-Agent: pan.baidu.com` 才能下载，
否则 403。官方流程是先 `HEAD` 一次拿 `Location` 再下载。

## 分享的限制

`/share/set` 端点存在，但**未绑定手机号的账号会被拦截**，返回
`{"errno":2,"show_msg":"为保障您的账号安全，使用分享功能前需完成手机号绑定"}`。
本实现保留创建分享，并把服务端的原始提示原样返回给用户，不自行编造。

## 已实现

`provider/baidu` — 列表、容量、建目录、改名、移动、删除、取下载地址、创建/取消分享。
单测覆盖路径拼接、父目录提取、URL 编码、errno 映射（含 -6 登录失效、errno 2 提示透传、
非 JSON 响应必须判失败）。
