# 阿里云盘（未接入）

包名 `com.alicloud.databox`，版本 6.21.0。

## 结论：两条接入路线都不符合本项目的登录形态，暂不接入

本项目所有 provider 的登录形态统一是「粘贴一个凭据」（账号密码或浏览器 Cookie / token）。
阿里云盘的两条路线都要求用户额外提供本项目拿不到的东西。

## 路线一：官方开放平台（openapi.aliyundrive.com）

要求 `client_id` / `client_secret`，需要在阿里云开发者平台建应用并申请网盘权限。
与百度 xpan 同理，个人项目拿不到。

实测（2026-11-22，无凭证）：

| 请求 | 结果 |
| --- | --- |
| `POST https://openapi.alipan.com/adrive/v1.0/openFile/list` | 401 `{"code":"AccessTokenInvalid","message":"access token is blank"}` |
| `POST https://openapi.aliyundrive.com/adrive/v1.0/openFile/list` | 同上 |
| `POST https://openapi.alipan.com/oauth/access_token`（假 refresh_token） | 401 `{"code":"RefreshTokenInvalid","message":"invalid refresh_token"}` |

## 路线二：App 私有接口（api.alipan.com）

要求 Bearer token，且请求要带 `X-Signature`，签名依赖 deviceID + ECDSA 私钥。

实测（2026-11-22，无凭证）：

| 请求 | 结果 |
| --- | --- |
| `POST https://api.alipan.com/v2/user/get`（假 Bearer） | 401 `{"code":"AccessTokenInvalid","message":"AccessToken is invalid. ErrValidateTokenFailed"}` |
| `POST https://api.alipan.com/v2/file/list`（假 Bearer） | 同上 |
| `POST https://api.alipan.com/adrive/v1.0/user/getSpaceInfo` | 404 `{"code":"NotFound","message":"uri(/v1.0/user/getSpaceInfo) not exist"}` |
| `POST https://auth.alipan.com/v2/account/token`（假 refresh_token） | 400 `{"code":"InvalidParameter.RefreshToken","message":""}` |

最后一条说明 `auth.alipan.com/v2/account/token` 端点真实存在（返回的是业务错误而不是 404），
但要能调它的前提是已经有一组合法的 refresh_token，而 refresh_token 只能由官方客户端
走完整的登录 + 设备注册流程签发。

## 为什么不自己实现签名

OpenList 的 `drivers/aliyundrive` 能跑，是因为它要求使用者额外配置：

- `deviceID`
- ECDSA 私钥（PEM）
- `userID`

签名算法在 `util.go` 的 `sign()`：

```
hash := sha256("5dde4e1bdf9e4966b387ba58f4b3fdc3:<deviceID>:<userID>:0")
sig  := ecc.SignBytes(privateKey, hash, ecc.RecID|ecc.LowerS)
```

这套设备凭据要靠一次真实的扫码/设备注册流程拿到，不是「粘贴一个 token」能解决的。
硬做等于要求用户在别处跑一遍 OpenList 再抄三个值过来，体验比直接用 OpenList 还差。

## APK 侧取证（存档）

解包到 `/tmp/aliyun_probe`，共 25 个 dex。

域名清单：

```
https://api.alipan.com/
https://auth.alipan.com/
https://bizapi.alipan.com/
https://member.alipan.com/
https://open.alipan.com/
https://user.alipan.com/
https://www.alipan.com/
https://stg.alipan.com/
https://stg-api.alipan.com/
https://stg-auth.alipan.com/
```

`classes17.dex` 里有 `v2/account/get`、`v2/account/rp`、`v2/account/token` 三个路径。

**dex 里没有可直接复用的 client_id 或签名密钥常量**：搜到的 32 位十六进制串
（如 `c3f14290a70440bb9f0cd9986dff26c8`）经核对都是第三方库的固定常量，不是 app key。

因此 APK 对接口取证的价值也很低：app key 只存在于服务端下发或 native 库的加密段里。

## 如果以后要接：OpenList 的端点清单（备查）

### drivers/aliyundrive（App 私有接口，需签名）

| 用途 | 方法 | 路径 |
| --- | --- | --- |
| 用户信息 | POST | `https://api.alipan.com/v2/user/get` |
| 列表 | POST | `/v2/file/list` |
| 下载地址 | POST | `/v2/file/get_download_url` |
| 改名 | POST | `/v3/file/update` |
| 建目录 | POST | `/adrive/v2/file/createWithFolders` |
| 删除 | POST | `/v2/recyclebin/trash` |
| 移动/复制 | POST | `/v3/batch` |
| 容量 | POST | `https://api.aliyundrive.com/adrive/v1/user/driveCapacityDetails` |

列表 body：`drive_id`、`parent_file_id`、`limit`、`marker`、`fields:"*"`、
`image_thumbnail_process`、`video_thumbnail_process`、`order_by`、`order_direction`。

批量操作 body 是 `requests:[{headers,method,id,body,url}]` + `resource:"file"`。

### drivers/aliyundrive_open（开放平台）

`https://openapi.alipan.com` + `/adrive/v1.0/openFile/list`、`/openFile/getDownloadUrl`、
`/openFile/create`、`/openFile/move`、`/openFile/copy`、`/openFile/update`、
`/openFile/recyclebin/trash`、`/openFile/delete`、`/user/getSpaceInfo`、
`/user/getDriveInfo`、`/oauth/access_token`。

响应字段：`items`、`next_marker`、`file_id`、`parent_file_id`、`name`、`size`、
`type`（`"folder"` 表示目录）、`content_hash`（SHA1）、`thumbnail`、`created_at`、`updated_at`。
