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
    // 设置的读写是挂起函数，并且要被界面当 StateFlow 订阅。
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
