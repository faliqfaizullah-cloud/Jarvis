plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The release workflow passes the version from the git tag (v1.8.0 -> 1.8.0) and an ever-increasing build number.
val appVersionName = (project.findProperty("appVersionName") as String?) ?: "1.8.0"
val appVersionCode = ((project.findProperty("appVersionCode") as String?) ?: "120").toInt()

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }
    // One fixed signing key for every build, so new versions install over the old one (no uninstall needed).
    signingConfigs {
        create("jarvis") {
            storeFile = file("jarvis.keystore")
            storePassword = "jarvis-release"
            keyAlias = "jarvis"
            keyPassword = "jarvis-release"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("jarvis")
        }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("androidx.core:core-ktx:1.12.0") }
