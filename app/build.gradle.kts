plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.imcrackedpixel.torchcontroller"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.imcrackedpixel.torchcontroller.fresh"
        minSdk = 26
        targetSdk = 32
        versionCode = 21
        versionName = "1.8.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin {
    jvmToolchain(17)
}
dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:object-detection:17.0.2")
}
