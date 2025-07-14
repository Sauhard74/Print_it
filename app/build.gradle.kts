plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    kotlin("kapt")
}

android {
    namespace = "com.example.printer"
    compileSdk = 35

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE.md",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/NOTICE"
            )
        }
    }

    defaultConfig {
        applicationId = "com.example.printer"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        // Room schema export
        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf("room.schemaLocation" to "$projectDir/schemas")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "ENABLE_LOGGING", "true")
            buildConfigField("boolean", "ENABLE_PERFORMANCE_MONITORING", "true")
        }
        
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "ENABLE_LOGGING", "false")
            buildConfigField("boolean", "ENABLE_PERFORMANCE_MONITORING", "false")
        }
        
        create("staging") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            buildConfigField("boolean", "ENABLE_LOGGING", "true")
        }
    }
    
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = false
        checkReleaseBuilds = true
        disable += setOf("MissingTranslation", "ExtraTranslation")
        warningsAsErrors = false
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/DEPENDENCIES"
            )
            pickFirsts += setOf(
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties"
            )
        }
    }
    
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        animationsDisabled = true
    }
}

// Force consistent BouncyCastle versions to avoid duplicate classes
configurations.all {
    resolutionStrategy {
        force("org.bouncycastle:bcprov-jdk18on:1.77")
        force("org.bouncycastle:bcpkix-jdk18on:1.77")
        force("org.bouncycastle:bcutil-jdk18on:1.77")
        
        eachDependency {
            if (requested.group == "org.bouncycastle") {
                if (requested.name.contains("jdk15to18")) {
                    useTarget("${requested.group}:${requested.name.replace("jdk15to18", "jdk18on")}:1.77")
                }
            }
        }
    }
    
    // Exclude commons-logging in favor of jcl-over-slf4j bridge
    exclude(group = "commons-logging", module = "commons-logging")
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.appcompat)
    implementation(libs.material)
    
    // Compose BOM and UI
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    
    // Networking - Ktor for IPP server
    implementation("io.ktor:ktor-server-core:2.3.10")
    implementation("io.ktor:ktor-server-netty:2.3.10")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.10")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.10")
    implementation("io.ktor:ktor-server-cors:2.3.10")
    implementation("io.ktor:ktor-server-compression:2.3.10")
    implementation("io.ktor:ktor-server-call-logging:2.3.10")
    implementation("io.ktor:ktor-server-auth:2.3.10")
    
    // Ktor client for network printer queries
    implementation("io.ktor:ktor-client-core:2.3.10")
    implementation("io.ktor:ktor-client-android:2.3.10")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.10")
    implementation("io.ktor:ktor-client-logging:2.3.10")
    
    // IPP Protocol
    implementation(libs.jipp)
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    
    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    
    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.datastore:datastore-core:1.0.0")
    
    // Room database (for job storage and caching)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    
    // Work Manager for background tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // File handling and document processing
    implementation("com.squareup.okio:okio:3.8.0")
    implementation("org.apache.tika:tika-core:2.9.1")
    implementation("org.apache.tika:tika-parsers-standard-package:2.9.1")
    
    // PDF processing
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    
    // JSON processing
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Date/Time handling
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    
    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")
    
    // Performance monitoring
    implementation("androidx.benchmark:benchmark-macro-junit4:1.2.3")
    
    // Cloud storage (optional)
    implementation("com.google.cloud:google-cloud-storage:2.33.0")
    
    // Testing dependencies
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("com.google.truth:truth:1.4.0")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.work:work-testing:2.9.0")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    
    // Android Testing
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation("androidx.navigation:navigation-testing:2.7.6")
    androidTestImplementation("androidx.work:work-testing:2.9.0")
    androidTestImplementation("io.mockk:mockk-android:1.13.8")
    
    // Debug tools
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.12")
}