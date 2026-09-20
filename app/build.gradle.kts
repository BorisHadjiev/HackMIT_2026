import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val appName: String = providers.gradleProperty("APP_NAME").getOrElse("StrokeSense")
val appId: String = providers.gradleProperty("APP_ID").getOrElse("com.hackmit.strokesense")

// Optional local-only Deepgram key from local.properties (gitignored). Never commit it.
val deepgramKey: String = runCatching {
    Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }.getProperty("DEEPGRAM_API_KEY").orEmpty()
}.getOrDefault("")

// Deepgram proxy base URL: local.properties override wins, else gradle.properties.
val deepgramProxyUrl: String = runCatching {
    Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }.getProperty("DEEPGRAM_PROXY_URL").orEmpty()
}.getOrDefault("").ifBlank {
    providers.gradleProperty("DEEPGRAM_PROXY_URL").getOrElse("")
}

// Backend gateway (voice, transcription proxy, care alerts). The shared demo token
// lives in the gitignored local.properties so it is never committed.
val gatewayBaseUrl: String = runCatching {
    Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }.getProperty("GATEWAY_BASE_URL").orEmpty()
}.getOrDefault("").ifBlank {
    providers.gradleProperty("GATEWAY_BASE_URL").getOrElse("")
}
val gatewayToken: String = runCatching {
    Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }.getProperty("GATEWAY_TOKEN").orEmpty()
}.getOrDefault("").ifBlank {
    providers.gradleProperty("GATEWAY_TOKEN").getOrElse("")
}

android {
    namespace = "com.hackmit.app"
    compileSdk = 35

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Single source of truth for the visible app name (see gradle.properties).
        resValue("string", "app_name", appName)
        buildConfigField("String", "DEEPGRAM_API_KEY", "\"$deepgramKey\"")
        buildConfigField("String", "DEEPGRAM_PROXY_URL", "\"$deepgramProxyUrl\"")
        buildConfigField("String", "GATEWAY_BASE_URL", "\"$gatewayBaseUrl\"")
        buildConfigField("String", "GATEWAY_TOKEN", "\"$gatewayToken\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.mediapipe.tasks.vision)

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.ui.tooling)
}
