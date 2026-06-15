import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.meta.portal.security"
    // Portal targets old AOSP (targetSdk 29), but we compile against whatever
    // platform is installed locally. 34+ is fine; only targetSdk affects runtime.
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.meta.portal.security"
        // Portal devices run older AOSP. minSdk 28, target 29 per Portal guidance.
        minSdk = 28
        targetSdk = 29
        versionCode = 2
        versionName = "0.1.1"
    }

    // Release signing. If a release keystore is configured (via local.properties
    // keys release.storeFile/storePassword/keyAlias/keyPassword, or the matching
    // RELEASE_STORE_FILE/… env vars), use it. Otherwise fall back to the debug
    // key so `assembleRelease` still produces an installable APK for sideloading
    // onto the Portal — never block a release build on a missing keystore.
    val releaseProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun releaseProp(key: String, env: String): String? =
        (project.findProperty(key) as String?)
            ?: releaseProps.getProperty(key)
            ?: System.getenv(env)
    val releaseStorePath = releaseProp("release.storeFile", "RELEASE_STORE_FILE")
    val hasReleaseKeystore = releaseStorePath != null && rootProject.file(releaseStorePath).exists()

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath!!)
                storePassword = releaseProp("release.storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = releaseProp("release.keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = releaseProp("release.keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    lint {
        // targetSdk 29 is intentional (Portal runs old AOSP) — don't fail the
        // release build's lintVital check on the expected "expired target SDK".
        disable += "ExpiredTargetSdkVersion"
    }

    buildTypes {
        debug {
            // Local convenience: pre-fill the signaling server so a fresh debug
            // install is ready to Arm without retyping the URL. Set it in the
            // gitignored local.properties (portal.serverUrl=wss://your-host) or
            // pass -PportalServerUrl=… — never committed. Defaults to empty.
            val localProps = Properties().apply {
                val f = rootProject.file("local.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            val devServer = (project.findProperty("portalServerUrl") as String?)
                ?: localProps.getProperty("portal.serverUrl")
                ?: ""
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"$devServer\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"\"")
            // Use the release keystore if configured, else the debug key so the
            // APK is always installable (sideload-only app, no Play Store).
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.webrtc)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.zxing.core)
}
