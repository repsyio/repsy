// Rendered by clients/gradle.ts (mustache) into a per-test work directory, next to
// settings.gradle.kts (the Kotlin DSL). The same project as publish.template.gradle: a tiny,
// source-free library published with maven-publish (jar, sources jar, POM, Gradle module metadata).
// The Repsy credential comes from the repsyUsername/repsyPassword project properties; the anonymous
// credential renders no credentials block at all.
plugins {
  `java-library`
  `maven-publish`
}

group = "{{{groupId}}}"
version = "{{{version}}}"

java {
  withSourcesJar()
}

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      from(components["java"])
    }
  }
  repositories {
    maven {
      url = uri("{{{repoUrl}}}")
      isAllowInsecureProtocol = true
      {{#hasCredential}}
      credentials {
        username = findProperty("repsyUsername") as String?
        password = findProperty("repsyPassword") as String?
      }
      {{/hasCredential}}
    }
  }
}
