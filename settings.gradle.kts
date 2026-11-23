pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "panhub"

include(":app")

// 跨平台契约层：只放模型、通用类型、网络与设计系统，不含任何具体网盘逻辑
include(":core:model")
include(":core:common")
include(":core:network")
include(":core:design")
include(":core:kernel")
include(":core:storage")
include(":core:session")
include(":core:platform")
include(":core:transfer")
include(":core:preview")

// provider 契约层 + 各网盘实现，每个实现一个模块，新增网盘只加模块不改 UI
include(":provider:api")
include(":provider:webdav")
include(":provider:pan123")
include(":provider:lanzou")
include(":provider:cloud189")
include(":provider:quark")
include(":provider:baidu")

// feature 层：每个页面一个模块，只依赖 core 的契约，互不依赖
include(":feature:accounts")
include(":feature:browser")
include(":feature:transfers")
include(":feature:shares")
include(":feature:shareimport")
include(":feature:settings")