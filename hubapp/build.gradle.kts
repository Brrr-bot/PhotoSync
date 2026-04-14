import java.net.HttpURLConnection
import java.net.URL
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

android {
    namespace = "com.photosync.hub"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.photosync.hub"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.service)
}

// ── GitHub release + APK upload ───────────────────────────────────────────────

/**
 * Runs automatically after assembleDebug.
 * - Reads current versionCode/versionName from android.defaultConfig
 * - Creates a GitHub release tagged v{versionName}
 * - Uploads the debug APK as photosync-hub.apk
 * - Updates version.json in the repo so tablets pick up the update
 *
 * Token is read from local.properties (never committed to git).
 * Set github.token and github.repo in local.properties.
 */
tasks.register("uploadApkToGitHub") {
    group = "photosync"
    description = "Upload debug APK to GitHub Releases and update version.json"

    doLast {
        if (githubToken.isBlank()) {
            println("⚠️  github.token not set in local.properties — skipping upload")
            return@doLast
        }

        val vCode = android.defaultConfig.versionCode ?: 1
        val vName = android.defaultConfig.versionName ?: "1.0"
        val tagName = "v$vName"
        val apkFile = file("build/outputs/apk/debug/hubapp-debug.apk")

        if (!apkFile.exists()) {
            println("⚠️  APK not found at ${apkFile.path} — did the build succeed?")
            return@doLast
        }

        println("📦  Uploading $tagName (versionCode=$vCode) to GitHub...")

        // ── 1. Delete existing release/tag with same name (idempotent re-runs) ──
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

        // Delete existing release for this tag if present
        val (listCode, listBody) = githubApi("GET", "/releases/tags/$tagName")
        if (listCode == 200) {
            val releaseId = Regex(""""id"\s*:\s*(\d+)""").find(listBody)?.groupValues?.get(1)
            if (releaseId != null) {
                githubApi("DELETE", "/releases/$releaseId")
                println("   Deleted existing release $tagName")
            }
        }
        // Delete the tag ref so we can re-create cleanly
        githubApi("DELETE", "/git/refs/tags/$tagName")

        // ── 2. Create the release ─────────────────────────────────────────────
        val releaseBody = """{"tag_name":"$tagName","name":"PhotoSync Hub $vName","body":"Automated build — versionCode $vCode","draft":false,"prerelease":false}"""
        val (createCode, createResp) = githubApi("POST", "/releases", releaseBody)
        if (createCode !in 200..201) {
            println("❌  Failed to create release (HTTP $createCode): $createResp")
            return@doLast
        }

        val uploadUrl = Regex(""""upload_url"\s*:\s*"([^"]+)"""")
            .find(createResp)?.groupValues?.get(1)
            ?.replace("{?name,label}", "") ?: run {
            println("❌  Could not parse upload_url from response")
            return@doLast
        }

        // ── 3. Upload APK ─────────────────────────────────────────────────────
        val uploadConn = URL("$uploadUrl?name=photosync-hub.apk")
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
            println("❌  APK upload failed (HTTP $uploadCode)")
            return@doLast
        }
        println("✅  APK uploaded successfully")

        // ── 4. Update version.json via Contents API (base64) ─────────────────
        val versionJson = """{"versionCode":$vCode,"versionName":"$vName","apkUrl":"https://github.com/$githubRepo/releases/download/$tagName/photosync-hub.apk"}"""
        val encoded = java.util.Base64.getEncoder().encodeToString(versionJson.toByteArray())

        // Get current SHA of version.json (needed for update)
        val (shaCode, shaResp) = githubApi("GET", "/contents/version.json")
        val sha = if (shaCode == 200)
            Regex(""""sha"\s*:\s*"([^"]+)"""").find(shaResp)?.groupValues?.get(1) else null

        val updateBody = buildString {
            append("""{"message":"Release $tagName — versionCode $vCode","content":"$encoded"""")
            if (sha != null) append(""","sha":"$sha"""")
            append("}")
        }
        val (updateCode, _) = githubApi("PUT", "/contents/version.json", updateBody)
        if (updateCode in 200..201) {
            println("✅  version.json updated on GitHub")
        } else {
            println("⚠️  version.json update failed (HTTP $updateCode)")
        }

        println("🚀  Release $tagName published — tablets will update within 24h")
    }
}

// Hook: run upload automatically after every debug build
afterEvaluate {
    tasks.named("assembleDebug") {
        finalizedBy("uploadApkToGitHub")
    }
}
