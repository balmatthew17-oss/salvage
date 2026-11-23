plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Each CI build gets a higher version code so the phone accepts it as an update.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.salvage.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.salvage.app"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // The same key signs every build, so new versions install over the old one.
    signingConfigs {
        create("salvage") {
            storeFile = file("salvage.jks")
            storePassword = "salvage-key-01"
            keyAlias = "salvage"
            keyPassword = "salvage-key-01"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("salvage")
        }
        debug {
            signingConfig = signingConfigs.getByName("salvage")
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
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
