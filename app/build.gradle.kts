plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "id.yomic.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.yomic.mobile"
        minSdk = 24
        targetSdk = 35
        versionCode = 210
        versionName = "2.1.0-mobile"

        vectorDrawables {
            useSupportLibrary = false
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
            buildConfigField(
                "String",
                "DEFAULT_EXTENSION_REPO",
                "\"https://cdn.jsdelivr.net/gh/ArisaAkiyama/extension-yomic@main/index.min.json\""
            )
            buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"github\"")
        }
        create("neutral") {
            dimension = "distribution"
            buildConfigField("String", "DEFAULT_EXTENSION_REPO", "\"\"")
            buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"neutral\"")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Allows installing release APK locally for lightweight size testing
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.generateKotlin", "true")
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Allow local JVM unit tests to run native QuickJS using the desktop JVM binary
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjs.get()}"))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)

    // Persistence & Serialization
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Networking, HTML & JS Engine
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.jsoup)
    implementation(libs.quickjs.kt)

    // Image Pipeline (Coil 3)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Background, Security & Custom Tabs
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.browser)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.sqlite.jdbc)
}
