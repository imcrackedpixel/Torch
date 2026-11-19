plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.imcrackedpixel.torchlightbot"
 compileSdk = 35
 defaultConfig { applicationId = "com.imcrackedpixel.torchlightbot"; minSdk = 26; targetSdk = 32; versionCode = 2; versionName = "0.2.0" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }