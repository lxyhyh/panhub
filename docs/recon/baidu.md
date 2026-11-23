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

`provider/baidu` — 列表、容量、建目录、改名、移动、删除、取下载地址、创建/取消分享、
分片上传（含秒传）。
单测覆盖路径拼接、父目录提取、URL 编码、errno 映射（含 -6 登录失效、errno 2 提示透传、
非 JSON 响应必须判失败）。

## 上传（网页 Cookie 模式）

事实来源：`/root/workspace/_research/OpenList/drivers/baidu_netdisk/driver.go` 的
`Put` / `precreate` / `uploadSlice` / `create`，以及 `util.go` 的
`getSliceSize` / `requestForUploadUrl`；端点存在性另外用真实 HTTP 探测交叉验证。

### 四步

| 步骤 | 端点 | 关键参数 |
| --- | --- | --- |
| 预上传 | POST `pan.baidu.com/rest/2.0/xpan/file` | `method=precreate` `path` `size` `isdir=0` `autoinit=1` `rtype=3` `block_list=<JSON数组>` `content-md5` `slice-md5` |
| 换上传域名 | GET `d.pcs.baidu.com/rest/2.0/pcs/file` | `method=locateupload` `appid=250528` `path` `uploadid` `upload_version=2.0` |
| 传分片 | POST `<域名>/rest/2.0/pcs/superfile2` | `method=upload` `type=tmpfile` `path` `uploadid` `partseq=<序号>` `app_id=250528`；字节走 multipart 的 `file` 字段 |
| 收尾 | POST `pan.baidu.com/rest/2.0/xpan/file` | `method=create` `path` `size` `isdir=0` `rtype=3` `uploadid` `block_list=<JSON数组>` |

`precreate` 的 `return_type`：`1` 正常分片上传（响应带 `uploadid` 与
`block_list`），`2` 服务端已有同内容文件，`info` 里直接是新文件元数据，
一个字节都不用传（秒传）。

### 摘要规则（与天翼完全不同）

- `content-md5` = 整个文件的 MD5 小写十六进制。
- `slice-md5` = **前 256 KiB** 的 MD5，不是「所有分片 MD5 再哈希」。
- `block_list` = 每个分片 MD5 的小写十六进制，按顺序放进 JSON 数组。

### 分片大小

非会员固定 **4 MiB**；会员 16 MiB、超级会员 32 MiB（`getSliceSize`）。
本项目统一按 4 MiB 切，会员账号也能用，只是片数更多。
官方上限是 2048 片（文档写 1024，实测 2048）。

### 上传域名

`locateupload` **不需要认证**（传 method 与 upload_version 就够），返回
`servers[0].server`，可能是裸域名也可能是带 https 的地址，要统一补全。
拿不到时退回 `https://d.pcs.baidu.com`：实测 `d.` / `c.` / `pcs.baidu.com`
三台机器行为一致。

### 端点存在性（真实探测，2026-11-23，无凭证）

    GET  pan.baidu.com/api/definitelyNotRealXYZ                → 404 空体
    GET  pan.baidu.com/api/precreate?<固定query>               → 200 {"errno":2}
    POST pan.baidu.com/rest/2.0/xpan/file?method=precreate     → 200 {"errno":-6}
    POST d.pcs.baidu.com/rest/2.0/pcs/superfile2?...           → 403 {"error_code":31045,"error_msg":"user not exists"}
    POST 同上 + 假 Cookie(BDUSS/STOKEN)                        → 401 {"error_code":0,"error_msg":"unknow errno:check pass error"}
    GET  d.pcs.baidu.com/rest/2.0/pcs/file?method=locateupload → 200 {"error_code":0,"host":"c.pcs.baidu.com","expire":60,...}

`superfile2` 换成 `partoffset` 或去掉 `partseq`/`app_id`，返回的都是同一个
31045，说明**服务端在认证之前就拦下了，参数校验探测不出来**。所以
`partseq`（OpenList 用法）与 `partoffset`（百度 APK 字符串
`W%1s/rest/2.0/pcs/superfile2?method=upload&type=tmpfile&path=%2s&partoffset=%3$s&app_id=`
里的写法）到底哪个对，本实现**没有实测证据**，暂时按 OpenList 的 `partseq`。

