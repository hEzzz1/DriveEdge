pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

rootProject.name = "DriveEdge"

include(":edge-core")
include(":edge-app")
include(":module-infer-yolo")
include(":module-temporal-engine")
include(":module-risk-engine")
include(":module-event-center")
