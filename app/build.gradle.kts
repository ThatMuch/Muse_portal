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
        // Le skill "portal" de Meta recommande 29, la version du Portal 10".
        targetSdk = 29
        versionCode = 31
        versionName = "1.0.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Détecteur « Hey Muse » ; lancer d'abord tools/preparer-reveil.sh.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            // La même clé que la version de développement : la mise à jour garde l'appairage et les
            // réglages. L'app n'est jamais publiée ; ce qui change est qu'elle n'est plus débogable.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // App installée à la main, jamais publiée sur un store.
        disable += "ExpiredTargetSdkVersion"
    }

    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
}

dependencies {
    implementation(project(":core"))
}
