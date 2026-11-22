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
| \`:core:transfer\` | 上传/下载队列、整目录下载与跨网盘转存（串行执行、可取消、网络抖动自动退避重试） |
| \`:core:kernel\` | Rust 内核 + JNI 桥接（MD5/SHA1/SHA256/CRC32、分片 MD5） |
| \`:core:platform\` | 只依赖系统的能力：Android Keystore 加密 |
| \`:provider:api\` | PanProvider 接口 + ProviderRegistry + ScopedPanProvider + UploadSource |
| \`:provider:webdav\` | WebDAV 客户端与坚果云实现（含流式上传） |
| \`:provider:pan123\` | 123 云盘（含秒传与 16MiB 分片上传） |
| \`:provider:lanzou\` | 蓝奏云（含 acw_sc__v2 人机校验与 multipart 上传） |
| \`:provider:cloud189\` | 天翼云盘 |
| \`:provider:quark\` | 夸克网盘 |
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
| 夸克网盘 | 网页 Cookie | ✅ | ✅ | — | — |
| 百度网盘 | 网页 Cookie | ✅ | ✅ | — | ✅ 可列出与取消 |

跨网盘转存不需要服务商提供额外接口：源端取下载直链、目标端走上传，
任何同时支持下载与上传的两个账号之间都能转存。中间文件落应用缓存目录，
传完即删。

下载整个文件夹时会先排一个「整理文件列表」的任务，把目录逐层列完再放出
每个文件的下载任务，本地按原来的层级还原（同名文件夹不会被摊平成一个）。

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