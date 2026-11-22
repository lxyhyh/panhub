# PanHub

一个统一管理多家网盘文件与分享的 Android 客户端。

- **Kotlin + Jetpack Compose** 写界面与业务
- **Rust** 写共享内核（摘要、分片校验等重活），通过 JNI 暴露窄接口
- **构建只在 GitHub Actions 上跑**，本地不编译

## 设计原则

1. **深模块化**：模块只通过窄接口交互。\`:core:*\` 是契约，\`:provider:*\` 是实现，
   \`:feature:*\` 是界面，\`:app\` 只做组装。新增一家网盘 = 新增一个 provider 模块
   + 一行注册，不动任何界面代码。
2. **能力声明而非类型判断**：每个 provider 声明 \`ProviderCapabilities\` 与 \`AuthMethod\`，
   UI 据此隐藏按钮、决定登录表单形态，杜绝 \`if (provider == "xxx")\` 这类耦合。
3. **错误归一化**：所有 provider 操作返回 \`PanResult\`，网络/鉴权/限流/不支持
   都映射成统一的 \`PanError\`，UI 只处理一种错误类型。
4. **UI 参考蓝云**：冷调蓝紫主色、浅冷灰背景、白色圆角卡片。

## 模块

| 模块 | 职责 |
|---|---|
| \`:core:model\` | 纯数据模型：RemoteNode / Quota / ShareLink / ProviderCapabilities / AuthMethod |
| \`:core:common\` | PanResult / PanError / DispatcherProvider / Clock / FileDigest |
| \`:core:network\` | OkHttp 封装、Cookie 存储、流式请求体，唯一的 HTTP 出口 |
| \`:core:design\` | 蓝云风格 Compose 主题、间距规范与公共组件 |
| \`:core:storage\` | 账号持久化（JSON + AES-GCM 加密） |
| \`:core:session\` | 会话管理：登录、恢复、跨 provider 的账号表 |
| \`:core:transfer\` | 上传/下载队列、整目录上传与下载、跨网盘转存与整目录复制（串行执行、可取消、网络抖动自动退避重试） |
| \`:core:kernel\` | Rust 内核 + JNI 桥接（MD5/SHA1/SHA256/CRC32、分片 MD5） |
| \`:core:platform\` | 只依赖系统的能力：Android Keystore 加密 |
| \`:provider:api\` | PanProvider 接口 + ProviderRegistry + ScopedPanProvider + UploadSource |
| \`:provider:webdav\` | WebDAV 客户端与坚果云实现（含流式上传） |
| \`:provider:pan123\` | 123 云盘（含秒传与 16MiB 分片上传） |
| \`:provider:lanzou\` | 蓝奏云（含 acw_sc__v2 人机校验与 multipart 上传） |
| \`:provider:cloud189\` | 天翼云盘 |
| \`:provider:quark\` | 夸克网盘（含 OSS 预签名分片上传与秒传） |
| \`:provider:baidu\` | 百度网盘 |
| \`:feature:accounts\` | 账号列表与登录 |
| \`:feature:browser\` | 文件浏览与操作 |
| \`:feature:transfers\` | 传输队列界面 |
| \`:feature:shares\` | 我的分享：按账号列出已创建的分享链接 |
| \`:app\` | 组装、导航、依赖注入 |

## 已接入的网盘

| 网盘 | 登录方式 | 浏览 | 下载 | 上传 | 分享 |
|---|---|---|---|---|---|
| 坚果云 | 邮箱 + 应用密码 | ✅ | ✅ | ✅ | — |
| 123 云盘 | 账密 / 令牌 | ✅ | ✅ | ✅ | ✅ 可列出与取消 |
| 蓝奏云 | 网页 Cookie | ✅ | ✅ | ✅ | 只读（文件天生带分享页） |
| 天翼云盘 | 网页 Cookie | ✅ | ✅ | — | — |
| 夸克网盘 | 网页 Cookie | ✅ | ✅ | ✅ 分片 + 秒传 | — |
| 百度网盘 | 网页 Cookie | ✅ | ✅ | — | ✅ 可列出与取消 |

跨网盘转存不需要服务商提供额外接口：源端取下载直链、目标端走上传，
任何同时支持下载与上传的两个账号之间都能转存。中间文件落应用缓存目录，
传完即删。

复制整个文件夹时，先在目标账号里按原层级把空目录逐层建出来，再把每个文件
当成一次普通的转存排进队列；目标账号必须支持新建文件夹。已经在目标端建好的
目录不会因为中途失败被回滚——目标端没有通用的批量删除，硬清理反而有风险。

下载整个文件夹时会先排一个「整理文件列表」的任务，把目录逐层列完再放出
每个文件的下载任务，本地按原来的层级还原（同名文件夹不会被摊平成一个）。

夸克网盘的上传走阿里 OSS：先向夸克要一张上传票据，把 MD5 与 SHA-1 报过去
（命中已有文件就直接秒传），再逐片换 OSS 签名往预签名地址 PUT，最后先合并
分片、再通知夸克索引。签名串多一个换行都会被判不匹配，所以那部分单独放在
`QuarkOss` 里并有逐字的单测。

上传整个文件夹是同一套思路反过来：先在网盘里按本地层级把目录建出来，
再把每个文件当成一次普通上传排进队列。空文件传不上去，会在整理阶段跳过
并在进度里写明跳过了几个；整棵树都是空文件时会说清楚是「空文件夹（跳过
N 个空文件）」，而不是假装什么都没发生。

传输遇到断网、限流、服务端 5xx 会自动重试（默认 3 次，1 秒起翻倍退避），
退避期间任务让出队列，后面的文件可以先传。鉴权过期、文件不存在这类
重试无意义的错误直接失败，让人早点看到真正的原因。

阿里云盘按设计不接入：两条接口路线分别要开放平台密钥和设备签名，
与本项目「粘贴凭据即可用」的登录形态不兼容，详见 \`docs/recon/aliyun.md\`。

接口事实都记录在 \`docs/recon/\`，每份文档只写**实际观察到的东西**，
推断的部分会明确标注。

## 构建

推送到 \`main\` 或开 PR 会自动触发 \`.github/workflows/build.yml\`：

- \`test\`：13 个纯 JVM 模块的单测
- \`kernel\`：Rust 单测、clippy、交叉编译出 \`jniLibs\`
- \`android\`：等前两个 job 通过后，跑需要 Android 平台的单测（\`:app\`、\`:feature:shares\`），再编译 debug APK

产物在 Actions 的 artifact 里（\`panhub-debug-apk\`）。