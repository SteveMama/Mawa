plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI signs with a persistent key (GitHub secrets) so installed builds can
// self-update in place. Local/keyless builds fall back to the default debug key.
val ciKeystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")
    ?.takeIf { java.io.File(it).let { f -> f.exists() && f.length() > 0 } }

android {
    namespace = "com.mawa.face"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mawa.face"
        // minSdk 21 (Android 5.0) is CameraX's floor — covers every OnePlus ever made
        minSdk = 21
        targetSdk = 35
        // CI run number = monotonically increasing version; the on-device
        // updater compares this against the published version.txt
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "dev")
    }

    signingConfigs {
        if (ciKeystorePath != null) {
            create("ci") {
                storeFile = file(ciKeystorePath)
                storeType = "PKCS12"
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "mawa"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (ciKeystorePath != null) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    // Bundled ML Kit face detection — model ships in the APK, fully on-device
    implementation("com.google.mlkit:face-detection:16.1.7")
}
