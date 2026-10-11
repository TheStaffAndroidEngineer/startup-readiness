plugins { alias(libs.plugins.androidTest); alias(libs.plugins.baselineProfile) }
android {
    namespace = "staff.android.startup.baselineprofile"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 28; targetSdk = 37; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    targetProjectPath = ":app"
    compileOptions { sourceCompatibility = JavaVersion.VERSION_26; targetCompatibility = JavaVersion.VERSION_26 }
}
kotlin { jvmToolchain(27); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26) } }
baselineProfile { useConnectedDevices = true }
dependencies { implementation(libs.benchmarkMacro); implementation(libs.uiautomator); implementation(libs.androidxTestJunit); implementation(libs.androidxTestRunner) }
