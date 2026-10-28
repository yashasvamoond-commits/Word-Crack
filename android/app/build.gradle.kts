plugins {
    id("com.android.application")
}

// CI passes the exact WordCraft version (from upstream Cargo.toml) and a monotonically increasing
// run number. Local builds without those properties still produce an installable APK.
val wordcraftVersionName = providers.gradleProperty("wordcraftVersionName").getOrElse("0.11.0-dev")
val ciRunNumber = providers.gradleProperty("ciRunNumber").orNull?.toIntOrNull() ?: 1
val wordcraftCommit = providers.gradleProperty("wordcraftCommit").getOrElse("unknown")

android {
    namespace = "ai.storyteller.wordcraft"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.storyteller.wordcraft"
        minSdk = 26
        targetSdk = 35
        versionCode = ciRunNumber
        versionName = wordcraftVersionName

        // The WASM blob must stay uncompressed inside the APK so Android WebView can stream it.
        androidResources {
            noCompress += "wasm"
        }
        // AGP 8.x leaves BuildConfig generation opt-in; we add a custom field below.
        buildFeatures {
            buildConfig = true
        }
        buildConfigField("String", "WORDCRAFT_COMMIT", "\"$wordcraftCommit\"")
    }

    buildTypes {
        release {
            // Sideloaded distribution: sign release builds with the auto-generated debug key so
            // the APK is installable out of the box. For Play Store distribution, create a real
            // keystore and wire it through environment variables / a signingConfigs block.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    // WebViewAssetLoader serves the bundled site over a virtual HTTPS origin, so the WASM
    // module's ES-module scripts and fetch() behave exactly like a hosted page.
    implementation("androidx.webkit:webkit:1.12.1")

    // Since Kotlin 1.8 the jdk7/jdk8 stdlib artifacts are empty stubs whose classes live in the
    // unified kotlin-stdlib. Some older androidx transitive dependencies still request the 1.6.x
    // split jars, which overlap kotlin-stdlib 1.8.22 and fail checkReleaseDuplicateClasses. Align
    // every stdlib artifact to the same version.
    constraints {
        implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.22")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.22")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.22")
    }
}
