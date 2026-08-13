plugins {
    id("com.android.application")
}

android {
    namespace = "sh.locus.goandroid"
    compileSdk = 36

    // Pinned so the C++ engine builds with the same NDK that produced the .aar.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "sh.locus.goandroid"
        // Must be >= the -androidapi passed to `gomobile bind` (24).
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // The .aar only contains arm64-v8a, because that is all we asked
            // `gomobile bind` for. Rebuild with -target=android to get the
            // other ABIs, and widen this filter to match.
            //
            // This also constrains the CMake build below — CMake would happily
            // produce all four ABIs, but there would be no matching Go library.
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    // The C++ engine. Unlike the Go side, Gradle DOES track these sources and
    // rebuilds libgreeter.so automatically on change — no equivalent of
    // build-aar.sh is needed.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The Go layer. Regenerate with ../build-aar.sh — do not edit by hand.
    implementation(files("libs/gocore.aar"))

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
