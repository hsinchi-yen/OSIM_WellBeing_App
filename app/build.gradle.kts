import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// One sofa's serial is personal, so it stays out of git: put sofa.mainboard.serial in the untracked
// local.properties to prefer that module before the first connection. Without it nothing is wrong:
// the app takes the only sofa module nearby, or lists them.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val mainboardSerial: String = localProperties.getProperty("sofa.mainboard.serial", "")

android {
    namespace = "com.example.osimsofa"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.osimsofa"
        minSdk = 27
        targetSdk = 35
        versionCode = 16
        versionName = "0.5.0"
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "DEFAULT_MAINBOARD_SERIAL", "\"$mainboardSerial\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true   // BuildConfig.DEBUG gates the raw hex writer (test builds only)
    }
    composeOptions {
        // Matches Kotlin 1.9.24
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-process:2.8.3")   // "app minimised" = no Activity visible
    implementation("androidx.core:core-ktx:1.13.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.ibm.icu:icu4j:74.2")   // same Han-Latin transliterator as android.icu
}
