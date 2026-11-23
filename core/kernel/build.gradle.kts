plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.panhub.core.kernel"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    // Rust 产物放在 src/main/jniLibs/<abi>/libpanhub_kernel.so，
    // 由 .github/workflows/build.yml 里的 cargo-ndk 步骤生成。
    sourceSets["main"].jniLibs.srcDir("src/main/jniLibs")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:common"))
    // FileHasher 用 kotlinx.serialization 解析内核返回的一行 JSON。
    // 只解析字符串，不需要 serialization 编译器插件。
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
