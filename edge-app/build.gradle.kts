import java.util.Properties

import org.gradle.api.Project

plugins {
  id("com.android.application") version "8.5.2"
}

fun Project.localProperty(name: String): String? {
  val file = rootProject.file("local.properties")
  if (!file.exists()) return null

  val properties = Properties()
  file.inputStream().use { input ->
    properties.load(input)
  }
  return properties.getProperty(name)
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
    buildConfigField("String", "EDGE_DEVICE_CODE", "\"DEV-EDGE-001\"")
    buildConfigField("String", "EDGE_ACTIVATION_CODE", "\"123456\"")
    buildConfigField("String", "EDGE_DEVICE_TOKEN", "\"dev-device-token\"")
    buildConfigField("String", "EDGE_ALGORITHM_VERSION", "\"local-fatigue-v1\"")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  flavorDimensions += "endpoint"
  productFlavors {
    val simulatorServerBaseUrl =
      providers.gradleProperty("edgeSimulatorServerBaseUrl")
        .orElse(providers.provider { localProperty("edgeSimulatorServerBaseUrl") })
        .orElse("http://10.0.2.2:8080")
        .get()
    val hostlocalServerBaseUrl =
      providers.gradleProperty("edgeHostlocalServerBaseUrl")
        .orElse(providers.provider { localProperty("edgeHostlocalServerBaseUrl") })
        .orElse("http://127.0.0.1:8080")
        .get()

    create("simulator") {
      dimension = "endpoint"
      buildConfigField("String", "EDGE_SERVER_BASE_URL", "\"$simulatorServerBaseUrl\"")
    }
    create("hostlocal") {
      dimension = "endpoint"
      buildConfigField("String", "EDGE_SERVER_BASE_URL", "\"$hostlocalServerBaseUrl\"")
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
  implementation("androidx.security:security-crypto:1.1.0-alpha06")
  implementation("androidx.work:work-runtime:2.9.1")
  implementation("io.github.crow-misia.libyuv:libyuv-android:0.43.2")
  implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
  implementation("com.google.mediapipe:tasks-vision:latest.release")
  implementation(project(":module-event-center"))
  implementation(project(":module-infer-yolo"))
  implementation(project(":module-risk-engine"))
  implementation(project(":module-storage"))
  implementation(project(":module-temporal-engine"))
  implementation(project(":module-uploader"))
  testImplementation(kotlin("test"))
  testImplementation("junit:junit:4.13.2")
}
