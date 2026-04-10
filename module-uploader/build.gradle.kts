plugins {
  kotlin("jvm")
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

dependencies {
  implementation(project(":module-event-center"))
  implementation(project(":module-risk-engine"))
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
}
