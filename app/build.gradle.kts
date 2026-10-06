plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.gms.google.services)
}

android {
    namespace = "com.keziah.spiritualtracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.keziah.spiritualtracker"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.auth)
    implementation(libs.cardview)
    
    // RecyclerView and WorkManager dependencies
    implementation(libs.recyclerview)
    implementation(libs.work.runtime)
    // Fix for "cannot access ListenableFuture" error
    implementation(libs.guava.listenablefuture)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    
    implementation("com.cloudinary:cloudinary-android:2.3.1")
    implementation("com.google.firebase:firebase-messaging:23.4.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.guava:guava:31.1-android")
}
