// The WebRTC voice activity detector, vendored from android-vad (see README.md) and built
// from source with a pinned NDK, so there's no prebuilt .so and no JitPack.
plugins {
    alias(libs.plugins.android.library)
}

android {
    // Upstream's package, so the vendored Kotlin and the JNI symbol names stay unchanged.
    namespace = "com.konovalov.vad.webrtc"
    compileSdk = 37
    // Keep in step with the "ndk;…" package in .github/workflows/ci.yml and release.yml.
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 34
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}
