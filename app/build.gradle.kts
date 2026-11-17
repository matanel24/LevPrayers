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

    // Release signing key comes from GitHub Secrets (see .github/workflows/build.yml) and is
    // never stored in the repo. Without it (e.g. a local build) the release is signed with the
    // machine's own debug key, which can't update the published app.
    val ksPath: String? = System.getenv("LEV_KEYSTORE_PATH")
    val ksPass: String? = System.getenv("LEV_KEYSTORE_PASSWORD")
    val hasReleaseKey = !ksPath.isNullOrEmpty() && !ksPass.isNullOrEmpty()

    signingConfigs {
        if (hasReleaseKey) {
            create("lev") {
                storeFile = file(ksPath!!)
                storePassword = ksPass
                keyAlias = "lev"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "lev" else "debug")
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
