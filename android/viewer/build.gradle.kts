plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.companionshare.viewer"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.companionshare.viewer"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "0.2.1"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
}
dependencies { implementation(project(":common")) }
