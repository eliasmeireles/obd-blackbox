import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Server that receives telemetry; empty builds an app that never uploads. See README.md.
val serverApiUrl = providers.gradleProperty("obdBlackBox.apiUrl")
    .orElse(providers.environmentVariable("OBD_BLACKBOX_API_URL"))
    .orElse("")
    .map { it.trim().trimEnd('/') }

android {
    namespace = "io.github.eliasmeireles.obdblackbox"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.eliasmeireles.obdblackbox"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        resValue("string", "server_api_url", serverApiUrl.get().replace("&", "&amp;").replace("<", "&lt;").replace("\"", "\\\"").replace("'", "\\'"))
        // Plain HTTP only when the build itself points to an http:// server (a LAN test server, for example).
        manifestPlaceholders["cleartextAllowed"] = serverApiUrl.get().startsWith("http://", ignoreCase = true).toString()
    }

    sourceSets.getByName("test").resources.srcDir("src/main/res")

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        resValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
    testImplementation(libs.okhttp.mockwebserver)
}
