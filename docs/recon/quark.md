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

OpenList 里没有任何 share 相关实现，APK 的 dex 里也没找到可读的分享路径。
本 provider 声明 `share = false`，不猜。

## 下载

`/file/download` 返回的链接要求同时带 Cookie、`Referer: https://pan.quark.cn/`、
以及官方 PC 客户端 UA，三者缺一服务端会拒绝。

## 已实现

`provider/quark` — 列表、容量、建目录、重命名、移动、删除、取下载地址、分片上传（含秒传）。
单测覆盖信封判定、错误映射、HTML 实体反转义、根目录常量，以及 OSS 待签串与
合并 XML 的逐字格式（`QuarkOssTest`）。
