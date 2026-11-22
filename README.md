# PanHub

一个统一管理多家网盘文件与分享的 Android 客户端。

- **Kotlin + Jetpack Compose** 写界面与业务
- **Rust** 写共享内核（签名、校验、加解密等重活），通过 JNI 暴露窄接口
- **构建只在 GitHub Actions 上跑**，本地不编译

## 设计原则

1. **深模块化**：模块只通过窄接口交互。`:core:*` 是契约，`:provider:*` 是实现，
   `:feature:*` 是界面，`:app` 只做组装。新增一家网盘 = 新增一个 provider 模块
   + 一行注册，不动任何界面代码。
2. **能力声明而非类型判断**：每个 provider 声明 `ProviderCapabilities`，
   UI 据此隐藏按钮，杜绝 `if (provider == "xxx")` 这类耦合。
3. **错误归一化**：所有 provider 操作返回 `PanResult`，网络/鉴权/限流/不支持
   都映射成统一的 `PanError`，UI 只处理一种错误类型。
4. **UI 参考蓝云**：冷调蓝紫主色、浅冷灰背景、白色圆角卡片。

## 模块

| 模块 | 职责 |
|---|---|
| `:core:model` | 纯数据模型：RemoteNode / Quota / ShareLink / ProviderCapabilities |
| `:core:common` | PanResult / PanError / DispatcherProvider / Clock |
| `:core:network` | OkHttp 封装、Cookie 持久化，唯一的 HTTP 出口 |
| `:core:design` | 蓝云风格 Compose 主题与间距规范 |
| `:provider:api` | PanProvider 接口 + ProviderRegistry + ScopedPanProvider |
| `:provider:webdav` | WebDAV 客户端与坚果云实现 |
| `:app` | 组装、导航、依赖注入 |

## 构建

推送到 `main` 或开 PR 会自动触发 `.github/workflows/build.yml`，
产物在 Actions 的 artifact 里（`panhub-debug-apk`）。
