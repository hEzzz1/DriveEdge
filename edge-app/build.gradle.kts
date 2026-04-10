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
    buildConfigField("String", "DRIVESERVER_DEVICE_TOKEN", "\"dev-device-token\"")
  }

  flavorDimensions += "endpoint"
  productFlavors {
    create("simulator") {
      dimension = "endpoint"
      buildConfigField("String", "DRIVESERVER_BASE_URL", "\"http://10.0.2.2:8080\"")
    }
    create("hostlocal") {
      dimension = "endpoint"
      buildConfigField("String", "DRIVESERVER_BASE_URL", "\"http://127.0.0.1:8080\"")
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
  implementation(project(":module-uploader"))
  implementation(project(":module-event-center"))
  implementation(project(":module-risk-engine"))

  implementation("androidx.core:core:1.13.1")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("com.google.android.material:material:1.12.0")
  implementation("androidx.activity:activity:1.9.1")

  implementation("androidx.lifecycle:lifecycle-service:2.8.4")

  implementation("androidx.camera:camera-core:1.4.0")
  implementation("androidx.camera:camera-camera2:1.4.0")
  implementation("androidx.camera:camera-lifecycle:1.4.0")
  implementation("androidx.camera:camera-view:1.4.0")
}
