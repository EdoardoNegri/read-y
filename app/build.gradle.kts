plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "io.github.edoardonegri.ready"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.edoardonegri.readY"
        minSdk = 24
        targetSdk = 37
        versionCode = 4
        versionName = "1.0"
    }

    // The app has English text only: the translations the libraries bring along are left out.
    androidResources {
        localeFilters += "en"
    }

    // Files the libraries bring along that nothing in the app reads: Kotlin's descriptions of its own
    // types (for kotlin-reflect, which is not used), the coroutines' debugger probes, the libraries'
    // version stamps and their licence texts.
    packaging {
        resources {
            excludes += listOf(
                "kotlin/**",
                "DebugProbesKt.bin",
                "META-INF/*.version",
                "META-INF/**/LICENSE.txt",
            )
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    // Installs the baseline profile (baselineprofile/) on phones that do not get it from Google Play.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
}