plugins {
    id("com.android.application")
}

android {
    namespace = "com.portalmuse.gadget"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.portalmuse.gadget"
        minSdk = 29
        // Meta's "portal" skill recommends 29, the version of the Portal 10".
        targetSdk = 29
        versionCode = 31
        versionName = "1.0.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // The "Hey Muse" detector; run tools/prepare-wakeword.sh first.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            // The same key as the development build: updating keeps the pairing and settings. The app is never
            // published; what changes is that it is no longer debuggable.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // App installed by hand, never published on a store.
        disable += "ExpiredTargetSdkVersion"
    }

    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
}

dependencies {
    implementation(project(":core"))
}
