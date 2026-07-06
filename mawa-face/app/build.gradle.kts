plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI signs with a persistent key (GitHub secrets) so installed builds can
// self-update in place. Local/keyless builds fall back to the default debug key.
val ciKeystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")?.takeIf { path ->
    val f = File(path)
    f.exists() && f.length() > 0
}

// Production points at the Vercel brain. Override for preview/local builds
// with MAWA_BRAIN_URL; an empty value disables cloud manifests completely.
val brainBaseUrl = System.getenv("MAWA_BRAIN_URL") ?: "https://mawa-brain.vercel.app"
val escapedBrainBaseUrl = brainBaseUrl.trimEnd('/').replace("\\", "\\\\").replace("\"", "\\\"")
val deviceToken = System.getenv("MAWA_DEVICE_TOKEN") ?: ""
val escapedDeviceToken = deviceToken.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.mawa.face"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mawa.face"
        // MediaPipe Audio Tasks (used to gate beat reactions on real music) require SDK 24+.
        // The wall target is Android 13, so we optimize for that actual deployment device.
        minSdk = 24
        targetSdk = 35
        // CI run number = monotonically increasing version; the on-device
        // updater compares this against the published version.txt
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "dev")
        buildConfigField("String", "BRAIN_BASE_URL", "\"$escapedBrainBaseUrl\"")
        buildConfigField("String", "DEVICE_TOKEN", "\"$escapedDeviceToken\"")
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

    // .tflite models must not be compressed or the Interpreter can't mmap them
    androidResources {
        noCompress += "tflite"
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

    // On-device face recognition (embeddings). Dormant until a model is added
    // at app/src/main/assets/mobilefacenet.tflite.
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("com.google.mediapipe:tasks-audio:0.10.29")
}
