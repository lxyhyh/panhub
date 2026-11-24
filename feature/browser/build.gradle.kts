plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.panhub.feature.browser"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:design"))
    api(project(":core:session"))
    api(project(":core:transfer"))
    api(project(":core:preview"))

    implementation(libs.androidx.activity.compose)
    // 缩略图：列表项直接用服务商返回的缩略图 URL，加载失败退回类型图标。
    implementation(libs.coil.compose)
    // 选整个文件夹要遍历 document tree，File 读不到 content:// 下的子项。
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
