# 夸克网盘

包名 `com.quark.clouddrive`，版本 10.21.5.144。

## 事实来源与可信度

夸克与 UC 是同一套云盘的两个品牌。APK 的 dex 里能搜到域名与路径，但请求参数、
签名、分页规则都在 dex 里没有可读的常量表，直接读 APK 收益很低。

本文件的端点全部来自 OpenList 的 `drivers/quark_uc`（同一套接口的另一份公开实现），
并用 APK 字符串做了交叉核对（`drive.quark.cn`、`/1/clouddrive`、`pan.quark.cn` 均在包内出现）。
参数与判据属于**推断**的部分会明确标注。

## 基础信息

| 项 | 值 |
| --- | --- |
| API 基址 | `https://drive.quark.cn/1/clouddrive` |
| Referer | `https://pan.quark.cn` |
| 公共 query | `pr=ucpro&fr=pc`（每个端点都要带） |
| 根目录 id | `0`（不是空串，也不是 -1） |
| 鉴权 | 网页会话 Cookie（`__pus` / `__puus` 等），无 access token 接口 |
| 响应信封 | `{status, code, message, data, metadata}`，成功 = `code == 0 && status < 400` |

## 端点

| 用途 | 方法 | 路径 | 关键参数 |
| --- | --- | --- | --- |
| 列目录 | GET | `/file/sort` | `pdir_fid` `_page` `_size` `_fetch_total=1` `fetch_all_file=1` `_sort=file_type:asc,file_name:asc` |
| 建目录 | POST | `/file` | `{dir_init_lock:false, dir_path:"", file_name, pdir_fid}` |
| 重命名 | POST | `/file/rename` | `{fid, file_name}` |
| 移动 | POST | `/file/move` | `{action_type:1, exclude_fids:[], filelist:[fid], to_pdir_fid}` |
| 删除 | POST | `/file/delete` | `{action_type:1, exclude_fids:[], filelist:[fid]}` |
| 下载地址 | POST | `/file/download` | `{fids:[fid]}` → `data[0].download_url` |
| 容量/会员 | GET | `/member` | `fetch_subscribe=false&_ch=home&fetch_identity=false` |
| 健康检查 | GET | `/config` | 无 |

| 上传票据 | POST | `/file/upload/pre` | `{ccp_hash_update:true, dir_name:"", file_name, format_type, l_created_at, l_updated_at, pdir_fid, size}` |
| 秒传校验 | POST | `/file/update/hash` | `{md5, sha1, task_id}` → `data.finish` |
| 分片签名 | POST | `/file/upload/auth` | `{auth_info, auth_meta, task_id}` → `data.auth_key` |
| 上传收尾 | POST | `/file/upload/finish` | `{obj_key, task_id}` |

## 上传（分片走阿里 OSS）

夸克本体不接收字节，只发票据与签名；真正的 PUT/POST 打在预签名地址上。四步：

1. **pre**：响应 `data` 里有 `task_id`、`bucket`、`obj_key`、`upload_id`、`upload_url`、
   `auth_info`、`fid`、`callback{callbackUrl, callbackBody}`；分片大小在 `metadata.part_size`
   （注意：这条接口的 metadata 装的是 part_size，不是分页信息）。
2. **hash**：`data.finish == true` 表示服务端已有同内容文件，秒传完成，一个字节都不用传。
3. **分片**：每片先 `/file/upload/auth` 换签名，再 PUT 到
   `https://<bucket>.<upload_url 去掉 scheme>/<obj_key>?partNumber=N&uploadId=...`，
   响应头里的 `Etag` 要逐个记下来。
4. **收尾**：先换一次 POST 签名，向同一个地址提交 `CompleteMultipartUpload`（XML，
   带 `Content-MD5` 与 `x-oss-callback`），再 `/file/upload/finish`。

待签串是 OSS V1 格式，换行不能少：

```
PUT\n\n<Content-Type>\n<Date>\nx-oss-date:<Date>\nx-oss-user-agent:<UA>\n/<bucket>/<objKey>?partNumber=N&uploadId=...
POST\n<Content-MD5>\napplication/xml\n<Date>\nx-oss-callback:<b64>\nx-oss-date:<Date>\nx-oss-user-agent:<UA>\n/<bucket>/<objKey>?uploadId=...
```

`upload_url` 的处理**不按参考实现写死的 `[7:]` 切**，改成剥掉任意 scheme
（http/https），免得上游换成 https 就拼出错地址。

## 字段

文件条目：`fid`、`file_name`（可能含 HTML 实体，需反转义）、`size`、`file`（**true 表示是文件**，判断目录要看 `!file`）、`category`（1=视频）、`created_at`、`updated_at`。

列表 `metadata`：`_size`、`_page`、`_count`、`_total`。翻页终止条件是 `page * size >= _total`。

容量 `data`：`use_capacity`、`total_capacity`、`member_type`。

## Cookie 轮换（易踩）

服务端会在 `Set-Cookie` 里轮换 `__puus`（有时还有 `__pus`）。不把新值合并回会话，
后续请求会很快被判失效。实现上把轮换过的键单独缓存，取用时合到原始 Cookie 串上。

## 分享

夸克的分享是**异步三步**，少一步都拿不到能发出去的链接：

1. **创建**：`POST /share`，body `{fid_list:[fid], url_type, expired_type, title?, passcode?}`，
   响应只有 `data.task_id`（`task_sync=true` 时 `data.task_resp.data` 里已带 `share_id`，可省掉第 2 步）。
2. **等任务**：`GET /task?task_id=&retry_index=N`，`data.status` 2 完成、3 失败（失败原因在
   `data.error_message`），其余继续轮询。完成时 `data.share_id` 才是真正的分享 id。
3. **换链接**：`POST /share/password`，body `{share_id}`，响应 `data` 里才有
   `share_url`、`pwd_id`、`passcode`、`expired_at`、`title`。

其它两个：

| 用途 | 方法 | 路径 | 关键参数 |
| --- | --- | --- | --- |
| 取消分享 | POST | `/share/delete` | `{share_ids:[share_id]}` |
| 我的分享 | GET | `/share/mypage/detail` | `_page` `_size` `_sort=created_at:desc` `_fetch_total=1` |

### 参数语义

| 字段 | 取值 |
| --- | --- |
| `url_type` | 1 = 公开（无提取码），2 = 带提取码。传了 `passcode` 就必须是 2 |
| `expired_type` | 1 = 永久，2 = 一天，3 = 一周，4 = 一月。**只有这四档**，没有任意天数 |
| `expired_at` | 毫秒时间戳；`>= 4102416000000`（2100 年）是服务端的「永久」哨兵，本地当成没有到期时间 |

档位只有四档，所以用户选 3 天只能落到「一周」档。界面要把实际到期的时刻显示出来，
不能照着用户填的天数写，否则会出现「显示 3 天、实际 7 天」的偏差
（`QuarkShareParsing.expiredTypeOf` 与 `daysOfExpiredType` 成对使用）。

### 端点存在性探测（2026-11-23，无凭证）

本机无夸克账号，用真实 HTTP 只能证明端点存在与否，不能证明参数校验：

    POST /share                        → 401 {"status":401,"code":31001,"message":"require login [guest]"}
    POST /share/password               → 401 同上
    POST /share/delete                 → 401 同上
    POST /share/update                 → 401 同上
    GET  /share/mypage/detail          → 401 同上
    GET  /task                         → 401 同上
    GET  /share                        → 405 Method Not Allowed（只接受 POST）
    GET  /share/update_list            → 405（只接受 POST）
    POST /share/create                 → 404（**这个路径不存在**，创建是 POST /share）
    GET  /definitelyNotRealXYZ         → 404（对照组，形状与上面 404 一致）
    GET  /share/sharepage/detail       → 400 {"code":14001,"message":"Bad Parameter: [guest missing pwd_id or stoken]"}

对照很关键：404 与 401/405 形状完全不同，所以 401/405 能证明端点存在。
`drive-pc.quark.cn` 与 `drive.quark.cn` 两个主机在分享端点上行为一致。

请求参数来自 qrypt 的 `pkg/drivers/quark/share.go` 与 QuarkPanTool 的 `quark.py`
（两者都用真实账号跑通，字段一致）。

## 下载

`/file/download` 返回的链接要求同时带 Cookie、`Referer: https://pan.quark.cn/`、
以及官方 PC 客户端 UA，三者缺一服务端会拒绝。

## 已实现

`provider/quark` — 列表、容量、建目录、重命名、移动、删除、取下载地址、分片上传（含秒传）、创建/取消/列出分享。
单测覆盖信封判定、错误映射、HTML 实体反转义、根目录常量，以及 OSS 待签串与
合并 XML 的逐字格式（`QuarkOssTest`）。
