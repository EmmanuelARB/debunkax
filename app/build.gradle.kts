import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing reads a gitignored keystore.properties (storeFile, storePassword, keyAlias, keyPassword).
val releaseKeystore = rootProject.file("keystore.properties")

// Developer API keys for debug builds only, from the gitignored local.properties
// (debunkax.debug.openRouterKey). Never committed, never in release.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun debugKey(name: String): String = localProps.getProperty("debunkax.debug.$name").orEmpty().trim()

android {
    namespace = "app.debunkax"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.debunkax"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore.exists()) {
            val props = Properties().apply { releaseKeystore.inputStream().use(::load) }
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseKeystore.exists()) signingConfig = signingConfigs.getByName("release")
            // Hard-coded empty: release builds can never carry a developer key.
            buildConfigField("String", "DEV_OPENROUTER_KEY", "\"\"")
            // Every supported phone (Android 12+, ~6 GB RAM) is 64-bit ARM; other ABIs only bloat the APK.
            ndk {
                abiFilters += "arm64-v8a"
                // `-PemulatorAbi` adds x86_64 to smoke-test the minified build on an emulator.
                if (project.hasProperty("emulatorAbi")) abiFilters += "x86_64"
            }
        }
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "DEV_OPENROUTER_KEY", "\"${debugKey("openRouterKey")}\"")
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.tink.android)
    implementation(libs.mlkit.genai.speech.recognition)
    implementation(libs.sherpa.onnx)
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    // Lets the Verdict bench use the developer keys without exporting them to the shell.
    systemProperty("debunkax.openRouterKey", debugKey("openRouterKey"))
    systemProperty("debunkax.bench", project.hasProperty("bench").toString())
    systemProperty("debunkax.benchCache", project.findProperty("benchCache")?.toString().orEmpty())
    systemProperty("debunkax.benchProvider", project.findProperty("benchProvider")?.toString().orEmpty())
    systemProperty("debunkax.benchModels", project.findProperty("benchModels")?.toString().orEmpty())
    systemProperty("debunkax.benchReasoningEffort", project.findProperty("benchReasoningEffort")?.toString().orEmpty())
}

// Speaker recognition (ADR 0003) needs its 40 MB model, kept out of git: a release must not ship without it.
val speakerModel = file("src/main/assets/speakers/titanet_small.onnx")
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        check(speakerModel.exists()) { "Missing $speakerModel: run spikes/speakers/export_prints.py" }
    }
}
