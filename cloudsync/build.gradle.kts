plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// versionCode/versionName injected by CI (-PversionCode=...), else a dev default.
val appVersionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1000
val appVersionName = (findProperty("versionName") as String?) ?: "1.0.$appVersionCode"

android {
    namespace = "com.photosync.cloudsync"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.photosync.cloudsync"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        getByName("debug") {
            (findProperty("debugKeystore") as String?)?.let { ks ->
                storeFile = file(ks)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.nanohttpd)
}
