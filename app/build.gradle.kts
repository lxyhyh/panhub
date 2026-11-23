plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.panhub.app"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.panhub.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        // 设置页要显示版本号；AGP 9 起默认不生成 BuildConfig。
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:design"))
    implementation(project(":core:session"))
    implementation(project(":core:network"))
    implementation(project(":core:platform"))
    implementation(project(":core:storage"))
    implementation(project(":provider:api"))
    implementation(project(":provider:webdav"))
    implementation(project(":provider:pan123"))
    implementation(project(":provider:lanzou"))
    implementation(project(":provider:cloud189"))
    implementation(project(":provider:quark"))
    implementation(project(":provider:baidu"))
    implementation(project(":feature:accounts"))
    implementation(project(":feature:browser"))
    implementation(project(":feature:transfers"))
    implementation(project(":feature:shares"))
    implementation(project(":feature:shareimport"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:search"))
    implementation(project(":feature:trash"))
    implementation(project(":feature:offline"))
    implementation(project(":feature:starred"))
    implementation(project(":core:transfer"))
    implementation(project(":core:kernel"))
    implementation(project(":core:preview"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
}