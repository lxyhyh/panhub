plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":provider:api"))
    implementation(project(":core:network"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
