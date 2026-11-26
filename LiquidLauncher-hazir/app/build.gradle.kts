plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.liquid.launcher"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.liquid.launcher"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        create("fixed") {
            storeFile = file("fixed.jks")
            storeType = "pkcs12"
            storePassword = "liquid123"
            keyAlias = "liquid"
            keyPassword = "liquid123"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
    }
}
