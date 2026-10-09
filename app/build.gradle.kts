plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// CI supplies these only to the release build; never store signing credentials in the repo.
val releaseSigning = listOf("EMBYDOG_KEYSTORE_PATH", "EMBYDOG_KEYSTORE_PASSWORD", "EMBYDOG_KEY_ALIAS", "EMBYDOG_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotBlank) }
require(releaseSigning.values.all { it == null } || releaseSigning.values.all { it != null }) {
    "Release signing requires all four EMBYDOG_KEYSTORE_* / EMBYDOG_KEY_* environment variables"
}

android {
    namespace = "com.embydog.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.embydog.app"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("releaseVersionCode").orNull?.toInt()?.also {
            require(it in 1..2_100_000_000) { "releaseVersionCode is outside Android's supported range" }
        } ?: 4
        versionName = providers.gradleProperty("releaseVersionName").orNull ?: "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Optional single-architecture APK; normal builds retain all supported ABIs.
        providers.gradleProperty("targetAbi").orNull?.let { targetAbi ->
            require(targetAbi in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) {
                "Unsupported targetAbi: $targetAbi"
            }
            ndk {
                abiFilters += targetAbi
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    if (releaseSigning.values.all { it != null }) {
        signingConfigs.create("release") {
            storeFile = file(requireNotNull(releaseSigning["EMBYDOG_KEYSTORE_PATH"]))
            require(storeFile!!.isFile) { "Release keystore file does not exist" }
            storePassword = releaseSigning["EMBYDOG_KEYSTORE_PASSWORD"]
            keyAlias = releaseSigning["EMBYDOG_KEY_ALIAS"]
            keyPassword = releaseSigning["EMBYDOG_KEY_PASSWORD"]
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.libmpv)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
