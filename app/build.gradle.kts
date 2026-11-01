plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.smartruralbus.driver"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.smartruralbus.driver"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            // 10.0.2.2 is how the Android emulator reaches "localhost" of your PC.
            // On a real phone, type your PC's address (e.g. 192.168.1.20:8000) on the login screen.
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"http://10.0.2.2:8000/\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Replace with your deployed HTTPS address before building a release.
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://your-server.example/\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Phone GPS
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Talking to Django
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Offline buffer: GPS points saved on the phone until the server confirms them
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")

    // Encrypted storage for the login tokens
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
