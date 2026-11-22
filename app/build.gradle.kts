import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi") version "2.0.0-alpha05.1"
}

android {
    namespace = "com.raman.photogate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.raman.photogate"
        minSdk = 30
        targetSdk = 36 // Google Play requires the latest Android version for new apps
        versionCode = 2
        versionName = "1.0"
    }

    // Two keys:
    //  - "fixed" (photogate.jks, committed, public password): sideload builds only, so they install over each other.
    //  - "upload": your private Google Play upload key, from keystore.properties (never committed) or the
    //    PG_UPLOAD_* environment variables (CI secrets). Release builds use it when it is configured.
    val upload = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        System.getenv("PG_UPLOAD_STORE_FILE")?.let { put("storeFile", it) }
        System.getenv("PG_UPLOAD_STORE_PASSWORD")?.let { put("storePassword", it) }
        System.getenv("PG_UPLOAD_KEY_ALIAS")?.let { put("keyAlias", it) }
        System.getenv("PG_UPLOAD_KEY_PASSWORD")?.let { put("keyPassword", it) }
    }
    val hasUploadKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { upload.getProperty(it) != null }

    signingConfigs {
        create("fixed") {
            storeFile = file("photogate.jks")
            storePassword = "photogate"
            keyAlias = "photogate"
            keyPassword = "photogate"
        }
        if (hasUploadKey) {
            create("upload") {
                storeFile = rootProject.file(upload.getProperty("storeFile"))
                storePassword = upload.getProperty("storePassword")
                keyAlias = upload.getProperty("keyAlias")
                keyPassword = upload.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            signingConfig = signingConfigs.getByName(if (hasUploadKey) "upload" else "fixed")
            // R8: smaller download (about 3 MB instead of 19 MB)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
