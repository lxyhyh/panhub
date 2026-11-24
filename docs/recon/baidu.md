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

## 复制

与移动同一个端点，只有两处不同：

- `opera=copy`
- `ondup=newcopy`（移动是 `fail`）

`ondup` 的差别是有意的：移动撞名必须报错，否则等于悄悄改名；复制撞名时
出一个同名副本正是用户要的，让服务端自己加后缀。

`filelist` 的形状与移动完全一致：`[{"path": 源路径, "dest": 目标目录, "newname": 文件或目录名}]`。
目录也能复制，服务端会连子项一起复制。

**证据分级**：`opera=copy` 在 APKS 的 `base.apk` 里 `classes15.dex` 有明文一处，
`classes12/13.dex` 另有 `filemanager_ondup_newcopy` / `filemanager_ondup_overwrite` /
`filemanager_ondup_check_all` 三组埋点，说明客户端确实按 ondup 分支处理。
端点与移动共用，移动已在真实账号下用过，复制沿用同一形状。

## 分享的限制

`/share/set` 端点存在，但**未绑定手机号的账号会被拦截**，返回
`{"errno":2,"show_msg":"为保障您的账号安全，使用分享功能前需完成手机号绑定"}`。
本实现保留创建分享，并把服务端的原始提示原样返回给用户，不自行编造。

## 已实现

`provider/baidu` — 列表、容量、建目录、改名、移动、复制、删除、取下载地址、创建/取消分享、
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


## 打开别人的分享并转存（已实现）

走的是网页通道（`/share/*` + `/api/shorturlinfo`），**不需要 App 的 sign/timestamp/sekey 签名**。
四个端点，全部在 2026-11-23 用真实 HTTP 验证过：

    GET  /api/shorturlinfo?shorturl=1pJ0ok7T&root=1     -> {shareid, uk, errno}
    POST /share/verify?surl=pJ0ok7T                     -> {errno, randsk}
    GET  /share/list?shareid=&uk=&root=1&page=1&num=100 -> {errno, list[], title}
    POST /share/transfer?from=&shareid=&bdstoken=&ondup=newcopy

### surl 的两种写法（最容易踩）

- `/api/shorturlinfo` 要**带**首位 `1` 的完整 surl（`shorturl=1pJ0ok7T`），传 `pJ0ok7T` 一律 errno 2；
- `/share/verify` 要**去掉**首位 `1`（`surl=pJ0ok7T`），传完整 surl 一律 errno 105。

所以 `BaiduShareLink` 统一按「带首位 1」保存，需要短形态时现切。

### 提取码

`/share/verify` 必须带分享页的 Referer（`https://pan.baidu.com/s/<surl>`），
不带的话哪怕 Cookie 正常也一律 errno 2。成功后服务端下发 `randsk`，
要**原样**（保持 URL 编码形态，不解码）写进 Cookie `BDCLND`，后续列目录与转存都带上。

    errno 0   -> 通过，带 randsk
    errno -9  -> 提取码不对
    errno -12 -> 这条分享本来就没有密码

### 列目录

根目录只能发 `root=1`；`dir=/` 会 errno 2。子目录发 `dir=<绝对路径>`（URL 编码）。
分页 `page` 从 1 起、`num` 生效；`cur_total` 是**当前页条数**不是总数，
所以「还有没有下一页」只能按「这一页拿满了」判断。

条目字段类型**不稳定**：根目录返回字符串（`"fs_id":"257047345573339"`、`"isdir":"0"`），
子目录返回数字。DTO 因此把 list 收成 JsonElement 逐项手读。

### 转存

    POST /share/transfer?from=<uk>&shareid=<id>&bdstoken=<token>&ondup=newcopy[&sekey=<randsk>]
    form: fsidlist=<fs_id 的 JSON 数组>&path=<目标绝对路径>

- `bdstoken` 现取（`GET /api/gettemplatevariable?fields=["bdstoken"]`），不缓存；
- `ondup=newcopy` 遇到同名文件建副本，绝不覆盖用户已有文件；
- 响应 `info[]` 里每项带自己的 `errno`，非 0 表示那一项没存进去，
  所以返回值只报真正成功的条数，不全成功也不全失败。

### 匿名可读

`/share/list` 只要带任意一个 Cookie（哪怕 `FOO=bar`）就够，不需要登录态；
不带 Cookie 头会回 errno 9019 need verify。`/api/shorturlinfo` 连 Cookie 都不用。
只有最后的 `/share/transfer` 需要登录。

### errno 对照（实测）

    0   成功
    -6  未登录
    -9  需要提取码 / 提取码不对
    -12 该分享没有密码
    -21 分享已被取消（show_msg=来晚啦，该分享已被取消）
    2   参数错误 / 目录不存在
    12  目录不存在
    140 链接不正确（啊哦，链接出错了）
    9019 缺 Cookie


## 回收站（已实现）

### 证据链

- APK base.apk 的 `classes15.dex` 字符串池里有四个路径：`recycle/list`、
  `recycle/restore`、`recycle/delete`、`recycle/clear`（各 1 处，前后是
  `recycleBin` / `recycle_bin_restore_net_error` 等 UI 字符串）；
- 无凭证探测：`GET /api/recycle/list` → 200 `{"errno":-6,"list":[],...}`，
  `POST /api/recycle/delete` → 200 `{"errno":-6,...}`；对照不存在的路径
  `/api/definitelyNotRealXYZ` → 404 空体。errno 形状与 404 形状可区分，
  证明端点存在；
- 请求参数与 cssxsh/baidu-client（开源 Kotlin 客户端）一致：
  list 用 query `page`/`num`/`web=1`；restore/delete 用 form
  `fidlist=[fs_id 数组]` + query `async`；clear 不带 form。
  三方互证。

### 端点

    GET  /api/recycle/list?web=1&page=<页码>&num=<每页>&<固定query>
    POST /api/recycle/restore?async=1&web=1&<固定query>   form: fidlist=[fs_id...]
    POST /api/recycle/delete?async=1&web=1&<固定query>    form: fidlist=[fs_id...]
    POST /api/recycle/clear?async=1&web=1&<固定query>     （无 form）

固定 query 与其他 /api/* 接口相同（channel=chunlei&app_id=250528&web=1&clienttype=0），
Cookie 需要登录态（BDUSS+STOKEN）。

### 响应

- 列表：`{"errno":0,"list":[{fs_id, path, server_filename, size, isdir,
  server_mtime, server_ctime, leftTime, delete_type, ...}]}`。条目结构与
  /api/list 一致，多的字段：`leftTime` 是「还剩多少秒自动清理」（0 表示
  不自动清理），`delete_type` 是删除来源；
- 还原 / 彻底删除：`{"errno":0,"faillist":[...],"taskid":...}`。
  **errno == 0 不代表全部成功**，没成功的 fs_id 列在 `faillist` 里，
  必须自己检查；
- 清空：`{"errno":0,"taskid":...}`。

### 限制

- 网页接口**没有**「还原到指定目录」的端点，只能还原回原位置。
  原目录已被删掉的条目会进 faillist。App 里对 parentId != null 明确拒绝；
- 回收站条目用 `fs_id`（数字）寻址，不是路径——还原 / 删除时把 NodeId
  解析成数字，解析失败直接拒绝不发请求；
- 回收站分页：服务端不回总数，按「本页条数是否满 num」判断还有下一页。

### UI 行为

- 界面只在进页面时拉一次列表，「刷新」手动重拉；
- 「还原到指定目录」入口对百度不显示（契约方法会明确报不支持）；
- 清空前确认。
