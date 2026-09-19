plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.andychang.clauderi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.andychang.clauderi"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Two cats, one code base, two installs. "lord" is Lord Claude; "second" is the cat designed
    // by an outside AI (docs/second-cat). Separate applicationId = separate storage, icon and
    // settings, so neither can see the other's archive. Pick the variant in Android Studio.
    flavorDimensions += "cat"
    productFlavors {
        create("lord") {
            dimension = "cat"
            resValue("string", "app_name", "Lord Claude !")
        }
        create("second") {
            dimension = "cat"
            applicationIdSuffix = ".second"
            versionNameSuffix = "-second"
            resValue("string", "app_name", "第二隻")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // Rule tests (app/src/test) run on the JVM: android.util.Log returns defaults instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging {
        resources {
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.anthropic.java)
    // Gmail capability: IMAP with an app password (javax.mail for Android)
    implementation(libs.android.mail)
    implementation(libs.android.activation)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.media)

    // The seven rule tests: docs/eval/README.md, first layer. No phone, no API key.
    testImplementation(libs.junit)
    testImplementation(libs.json)          // real org.json on the JVM; android.jar only has stubs
    testImplementation(libs.mockito.core)  // a Context whose filesDir is a temp folder
}
