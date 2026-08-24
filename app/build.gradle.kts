plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.convert.psdwebp"
    compileSdk = 35

    // Same keystore for debug + release → new APKs always install over older ones
    signingConfigs {
        create("shared") {
            val ks = rootProject.file("app/psdwebp.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "convert"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        applicationId = "com.convert.psdwebp"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

        ndk {
            // Start with arm64 only to speed builds; add others later if needed
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            // Chaquopy has prebuilt wheels for these
            install("Pillow")
            install("numpy")
            // psd-tools pulls scikit-image/aggdraw which need meson (not available).
            // Install without deps, then only the pure/prebuilt deps we need.
            options("--no-deps")
            install("psd-tools")
            // pure-python deps of psd-tools
            install("attrs")
            install("packaging")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
