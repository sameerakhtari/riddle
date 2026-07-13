plugins {
    id("com.android.application")
}

android {
    namespace = "com.sameerakhtari.riddle"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sameerakhtari.riddle"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.5.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }
}

dependencies {
    // LiteRT-LM inference, resilient background downloads, and Files integration.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("androidx.documentfile:documentfile:1.1.0")
}
