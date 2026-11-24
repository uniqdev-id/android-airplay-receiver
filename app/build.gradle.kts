plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.uniq.airplay"
    // compileSdk 35: required by androidx.core:core-ktx:1.15.0.
    // targetSdk stays 34 per SPEC.md (runtime behavior).
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.uniq.airplay"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Primary target: MiTV-AXSO0 (Xiaomi TV, Android 9, 32-bit only).
        // arm64-v8a kept for modern TVs; x86_64 omitted to keep builds lean
        // (add here when testing on an emulator).
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_static")
                // Phase 3: point at the vendored UxPlay lib/, OpenSSL and libplist.
            }
        }
    }

    externalNativeBuild {
        cmake {
            version = "3.22.1"
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        debug {
            isJniDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // Deliberately minimal: no AppCompat/Leanback UI libraries for two
    // plain Activities on a TV (see SPEC.md deviation note).
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Phase 3: OpenSSL 1.1.1q is NOT pulled from Google's NDK thirdparty
    // prefab AAR — the shared libs + headers are vendored into
    // app/src/main/cpp/third_party/openssl (hermetic, matches UxPlay's
    // build pattern) and the .so files are packaged from jniLibs.
}
