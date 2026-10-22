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
        versionCode = 3
        versionName = "1.0"
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
    implementation(libs.material)
    // Installs the baseline profile (baselineprofile/) on phones that do not get it from Google Play.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
}