plugins {
  id("com.android.application") version "8.5.2"
}

android {
  namespace = "com.driveedge.app"
  compileSdk = 34

  buildFeatures {
    buildConfig = true
  }

  defaultConfig {
    applicationId = "com.driveedge.app"
    minSdk = 26
    targetSdk = 34
    versionCode = 1
    versionName = "0.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  flavorDimensions += "endpoint"
  productFlavors {
    create("simulator") {
      dimension = "endpoint"
    }
    create("hostlocal") {
      dimension = "endpoint"
    }
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
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

}

dependencies {
  implementation("androidx.core:core:1.13.1")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("com.google.android.material:material:1.12.0")
  implementation("androidx.activity:activity:1.9.1")
  implementation("androidx.lifecycle:lifecycle-service:2.8.4")
  implementation("io.github.crow-misia.libyuv:libyuv-android:0.43.2")
  implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
  implementation("com.google.mediapipe:tasks-vision:latest.release")
}
