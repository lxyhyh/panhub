plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.panhub.core.platform"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:storage"))
    api(project(":core:common"))
    // FileProvider 在 androidx.core 里，交给别的应用打开文件要用它换 content:// 。
    implementation(libs.androidx.core.ktx)
    // 设置的读写是挂起函数，并且要被界面当 StateFlow 订阅。
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
