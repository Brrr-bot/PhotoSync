import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ── Read local.properties (never committed) ───────────────────────────────────
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(f.inputStream())
}
val githubToken: String = localProps.getProperty("github.token", "")
val githubRepo:  String = localProps.getProperty("github.repo",  "ngalogivn-ship-it/photosync")

// Read build number from shared file so both apps always have the same version
val buildNumberFile = rootProject.file("build_number.txt")
val appVersionCode = buildNumberFile.readText().trim().toInt()
val appVersionName = "1.0.$appVersionCode"

android {
    namespace = "com.photosync.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.photosync.client"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.nanohttpd)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.androidx.lifecycle.service)
}

// ── GitHub release — upload client APK to existing release ───────────────────
// The hub task creates the release and uploads version.json.
// This task only uploads the client APK asset to the same release tag.

tasks.register("uploadApkToGitHub") {
    group = "photosync"
    description = "Upload client debug APK to the existing GitHub release"

    doLast {
        if (githubToken.isBlank()) {
            println("⚠️  github.token not set in local.properties — skipping upload")
            return@doLast
        }

        val vName = appVersionName
        val tagName = "v$vName"
        val apkFile = file("build/outputs/apk/debug/clientapp-debug.apk")

        if (!apkFile.exists()) {
            println("⚠️  Client APK not found at ${apkFile.path} — did the build succeed?")
            return@doLast
        }

        println("📦  Uploading client APK to release $tagName...")

        fun githubApi(method: String, path: String, body: String? = null): Pair<Int, String> {
            val conn = URL("https://api.github.com/repos/$githubRepo$path")
                .openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "token $githubToken")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15_000
            conn.readTimeout    = 30_000
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.bufferedWriter().use { it.write(body) }
            }
            val code = conn.responseCode
            val resp = runCatching {
                (if (code < 400) conn.inputStream else conn.errorStream)
                    .bufferedReader().readText()
            }.getOrDefault("")
            conn.disconnect()
            return Pair(code, resp)
        }

        // Find the existing release for this tag
        val (listCode, listBody) = githubApi("GET", "/releases/tags/$tagName")
        if (listCode != 200) {
            println("⚠️  Release $tagName not found (HTTP $listCode) — hub task may not have run yet")
            return@doLast
        }

        val uploadUrl = Regex(""""upload_url"\s*:\s*"([^"]+)"""")
            .find(listBody)?.groupValues?.get(1)
            ?.replace("{?name,label}", "") ?: run {
            println("❌  Could not parse upload_url from response")
            return@doLast
        }

        // Delete existing client APK asset if present (idempotent re-runs)
        val existingAssetId = Regex(""""id"\s*:\s*(\d+)[^}]*"name"\s*:\s*"photosync-client\.apk"""")
            .find(listBody)?.groupValues?.get(1)
        if (existingAssetId != null) {
            githubApi("DELETE", "/releases/assets/$existingAssetId")
        }

        // Upload client APK
        val uploadConn = URL("$uploadUrl?name=photosync-client.apk")
            .openConnection() as HttpURLConnection
        uploadConn.requestMethod = "POST"
        uploadConn.setRequestProperty("Authorization", "token $githubToken")
        uploadConn.setRequestProperty("Content-Type", "application/vnd.android.package-archive")
        uploadConn.connectTimeout = 15_000
        uploadConn.readTimeout    = 120_000
        uploadConn.doOutput = true
        apkFile.inputStream().use { it.copyTo(uploadConn.outputStream) }
        val uploadCode = uploadConn.responseCode
        uploadConn.disconnect()

        if (uploadCode !in 200..201) {
            println("❌  Client APK upload failed (HTTP $uploadCode)")
            return@doLast
        }
        println("✅  Client APK uploaded successfully to $tagName")
    }
}

// Hook: run upload automatically after every debug build
afterEvaluate {
    tasks.named("assembleDebug") {
        finalizedBy("uploadApkToGitHub")
    }
}
