plugins {
  kotlin("jvm") version "2.0.21" apply false
}

allprojects {
  repositories {
    google()
    mavenCentral()
    maven(url = "https://maven.aliyun.com/repository/public")
  }
}
