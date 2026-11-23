# 天翼云盘（cloud189）接口取证

包名 `com.cn21.ecloud`，版本 11.1.2，APK 大小 387,095,539 字节。

## 事实来源

APK 的 `classes.dex` 只有 10,136 字节，是壳；业务代码全在
`lib/arm64-v8a/libapp.so`（18,138,032 字节，Flutter AOT）。
`strings -n 6` 抽出 43,662 行，能在里面找到端点、参数名、
Dart 源码路径。

**Flutter 的 base URL 是运行时拼的**，所以 libapp.so 里只有
`/file/listFiles.action` 这样的相对路径，没有完整 URL。

## 端点（以 OpenList `drivers/189` 为准，已核对）

OpenList 是开源实现，路径和参数都可核实，比在字符串里猜可靠。
关键文件：`/root/workspace/_research/OpenList/drivers/189/util.go`。

| 用途 | 端点 | 方法 |
| --- | --- | --- |
| 列目录 | `https://cloud.189.cn/api/open/file/listFiles.action` | GET |
| 建目录 | `https://cloud.189.cn/api/open/file/createFolder.action` | POST form |
| 文件改名 | `https://cloud.189.cn/api/open/file/renameFile.action` | POST form |
| 文件夹改名 | `https://cloud.189.cn/api/open/file/renameFolder.action` | POST form |
| 移动/复制/删除 | `https://cloud.189.cn/api/open/batch/createBatchTask.action` | POST form |
| 取下载地址 | `https://cloud.189.cn/api/portal/getFileInfo.action` | GET |
| 容量 | `https://cloud.189.cn/api/portal/getUserSizeInfo.action` | GET |
| 用户信息 | `https://cloud.189.cn/v2/getUserBriefInfo.action` | GET |
| 登录入口 | `https://cloud.189.cn/api/portal/loginUrl.action` | GET |

## 参数

列目录（GET query）：

    pageSize=60
    pageNum=1
    mediaType=0
    folderId=-11
    iconOption=5
    orderBy=lastOpTime
    descending=true

个人云根目录固定是 **`-11`**（OpenList 默认根，官方客户端也用它）。

建目录（form）：`parentFolderId`、`folderName`。

改名：文件用 `fileId` + `destFileName`，文件夹用 `folderId` +
`destFolderName`，两个端点字段名不一样。

批量任务（form）：`type`（MOVE / COPY / DELETE）、`targetFolderId`、
`taskInfos`（JSON 字符串，元素是 `{"fileId","fileName","isFolder"}`，
`isFolder` 是 1/0）。

## 响应结构（关键差异）

天翼云盘的列表把**文件夹和文件分成两个数组**，不是用 `isFolder` 混排：

    {
      "res_code": 0,
      "fileListAO": {
        "count": 2,
        "folderList": [{"id": 11, "name": "docs", "lastOpTime": "2024-01-02 12:34:56"}],
        "fileList": [{"id": 22, "name": "a.txt", "size": 1024,
                      "lastOpTime": "2024-01-03 01:02:03",
                      "icon": {"smallUrl": "https://..."}}]
      }
    }

容量：

    {"res_code": 0,
     "cloudCapacityInfo": {"usedSize": 100, "totalSize": 1000, "freeSize": 900},
     "familyCapacityInfo": {...}}

## 鉴权

天翼云盘的 open 接口没有可直传的 access token，必须带
`cloud.189.cn` 域的网页会话 Cookie（`COOKIE_LOGIN_USER` 等）。
所以本项目的登录方式是"粘贴浏览器 Cookie"。

`api.cloud.189.cn` 是另一套需要 **HMAC-SHA1 签名**的接口，
未签名会返回：

    {"errorCode":"InvalidArgument","errorMsg":"date/signature is null"}

签名算法（`drivers/189pc/help.go`）：

    data = "SessionKey=<key>&Operate=<GET|POST>&RequestURI=<path>&Date=<GMT>"
    if params: data += "&params=<AES-ECB(params, secret[0..16])>"
    Signature = UPPER(HEX(HMAC-SHA1(data, sessionSecret)))
    头：Date / SessionKey / X-Request-ID / Signature

## 端点探测的教训（重要）

直接 GET 一个**不存在**的路径，返回和真实端点一模一样：

    {"errorCode":"InvalidSessionKey",
     "errorMsg":"cookieUserSession is null or invalid, cookieUserSession=null"}

所以「返回 InvalidSessionKey」**不能**当作「端点存在」的证据。
端点必须靠开源实现或真机抓包确认。本项目据此只实现有事实来源的端点，
分享能力干脆声明为不支持。

## 时间格式

`"yyyy-MM-dd HH:mm:ss"`，无时区，按东八区解释；部分接口只回
`"yyyy-MM-dd"`，按当天零点处理。

注意：`LocalDateTime.parse` 解析不了纯日期，必须用 `LocalDate`。

## 上传（走另一套加密通道）

上传不在 `cloud.189.cn`，而在 `https://upload.cloud.189.cn`，每个请求都要现算签名，
和 open 接口的 Cookie 直传完全是两回事。事实来源：
`/root/workspace/_research/OpenList/drivers/189/util.go` 的
`newUpload` / `uploadRequest`，以及 `help.go` 里的 `RsaEncode` / `AesEncrypt`。

### 前置

1. `GET https://cloud.189.cn/v2/getUserBriefInfo.action` 取 `sessionKey`。
   **未登录时它返回 200 + `{}`**，所以判断登录失效要看「拿不到 sessionKey」，
   不能看状态码。
2. `GET https://cloud.189.cn/api/security/generateRsaKey.action` 取
   `pubKey` / `pkId` / `expire`（毫秒时间戳）。没过期就能一直复用。

### 每个请求的构造

    secret  = 随机小写十六进制串，长度 16 + int(16 * rand)，即 16..31
    params  = HEX(AES-128-ECB(PKCS7("k=v&k=v..."), secret[0..16]))
    date    = 毫秒时间戳
    Signature = HMAC-SHA1("SessionKey=<k>&Operate=GET&RequestURI=<uri>" +
                          "&Date=<date>&params=<params>", secret)
    EncryptionText = Base64(RSA-PKCS1(secret, pubKey))

    GET https://upload.cloud.189.cn<uri>?params=<params>
    头：accept: application/json;charset=UTF-8
        SessionKey / Signature / X-Request-Date / X-Request-ID / EncryptionText / PkId

`X-Request-ID` 是 `xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx` 形态（版本位 4，变体位 8..b）。
签名串里 `Operate` 恒为 `GET`，即使业务动作是上传也一样。

### 三个端点

| 用途 | 端点 | 表单字段 |
| --- | --- | --- |
| 发起上传 | `/person/initMultiUpload` | `parentFolderId`、`fileName`（URL 编码）、`fileSize`、`sliceSize=10485760`、`fileMd5`、`sliceMd5` |
| 换分片地址 | `/person/getMultiUploadUrls` | `partInfo=<序号>-<该片MD5的Base64>`、`uploadFileId` |
| 合并分片 | `/person/commitMultiUploadFile` | `uploadFileId`、`fileMd5`、`sliceMd5`、`lazyCheck=1`、`opertype=3` |

响应判据是 `code == "SUCCESS"`，**不是 open 接口的 `res_code`**。
`initMultiUpload` 里 `data.fileDataExists == 1` 表示服务端已有同内容文件，
可以直接 commit，一个字节都不用传（秒传）。

`getMultiUploadUrls` 的响应：

    {"code":"SUCCESS",
     "uploadUrls":{"partNumber_1":{"requestURL":"https://hb02.upload.cloud.189.cn/...",
                                   "requestHeader":"Content-Type%3D...%26Content-MD5%3D..."}}}

`requestHeader` 是整体做过一次 URL 编码的 `k=v&k=v` 串：先百分号解码，
再按 `&` 切，最后按**第一个** `=` 拆名字与值（Base64 里的 `=` 不能被当分隔符）。
解码时**不能**把 `+` 当空格，否则 Base64 会被改坏。

### 摘要规则

- `fileMd5` = 整个文件 MD5 的小写十六进制。
- `sliceMd5`：文件大于 10 MiB 时，把每个分片 MD5 的**大写**十六进制用 `\n`
  连起来再取一次 MD5（小写）；否则直接等于 `fileMd5`。
- 分片 MD5 只在「当前分片正好写满」时记录，最后不满一片的尾巴在循环结束后补记；
  空文件也补一个空摘要，保持「至少一片」。

### 端点存在性（真实探测，2026-11-23，无凭证）

    GET https://upload.cloud.189.cn/person/initMultiUpload?params=00
      → 403 {"code":"InvalidSessionKey","msg":"sessionKey=null,invalid sessionKey,..."}
    GET https://upload.cloud.189.cn/person/definitelyNotReal?params=00
      → 404 {"timestamp":"...","status":404,"error":"Not Found","path":"/person/definitelyNotReal"}

这两条形状不同，所以上传通道里「403 + InvalidSessionKey」**可以**证明端点存在
——和 open 接口那条「任意路径都返回 InvalidSessionKey」的教训正好相反。

