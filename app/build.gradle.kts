plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.chessoverlay"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.chessoverlay"
        minSdk = 26
        // 28 on purpose: from API 29 on, an app may not execute files it
        // wrote itself, and loading your own engine binaries (Komodo, lc0,
        // Open Exchange engine apps) depends on that, as in DroidFish.
        // Such an app can be sideloaded but not published on Google Play.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
        // Phones are arm64; emulator tests in CI add x86_64 (-PextraAbi=x86_64).
        ndk {
            abiFilters += listOf("arm64-v8a")
            (project.findProperty("extraAbi") as String?)?.let { abiFilters += it }
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Test output files (the overlay rendering) are collected into
        // build/outputs/connected_android_test_additional_output.
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded builds: sign with the debug key unless a real one is configured.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    // On-device tests reuse the unit tests' images.
    sourceSets["androidTest"].assets.srcDirs("src/test/resources")
    // The bundled models are copied out once; storing them uncompressed makes that a plain copy.
    androidResources { noCompress += "onnx" }
    packaging {
        // Engines ship as lib*.so so that Android installs them as
        // executable files in nativeLibraryDir; they must stay extracted.
        jniLibs { useLegacyPackaging = true }
    }
    lint {
        // targetSdk 28 is deliberate (see above).
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi")
        abortOnError = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.services:storage:1.5.0")
    androidTestUtil("androidx.test.services:test-services:1.5.0")
    testImplementation("org.json:json:20240303")
    // JVM unit tests run the recognizer on the desktop ONNX Runtime.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
}

// The Android ONNX Runtime has the same classes as the desktop one; keep it
// off the JVM unit-test classpath.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}
