plugins {
  kotlin("jvm")
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

dependencies {
  implementation(project(":module-infer-yolo"))
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
}
