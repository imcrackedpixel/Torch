plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.imcrackedpixel.torchcontroller"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.imcrackedpixel.torchcontroller"
        minSdk = 26
        targetSdk = 32
        versionCode = 14
        versionName = "1.4.0"
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
