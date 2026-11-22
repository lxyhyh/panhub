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
}
