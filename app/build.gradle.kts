plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.saltsman.levprayers"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.saltsman.levprayers"
        minSdk = 26
        targetSdk = 34
        // On GitHub each build gets a higher version, so it installs as an update.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "1.0.$run"
    }

    // Fixed key so every build (local or GitHub) can update the installed app
    // without uninstalling first.
    signingConfigs {
        getByName("debug") {
            storeFile = file("lev-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
        checkReleaseBuilds = false
    }
}
