// Rendered by clients/gradle.ts (mustache) into a per-test work directory (the Kotlin DSL). The same
// consumer as consumer.template.gradle: only the Repsy repository, the dependency line the panel
// shows for a Gradle Kotlin DSL user, and fetchDependencies copying the resolved files into
// build/resolved.
plugins {
  `java-library`
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

dependencies {
  implementation("{{{coordinates}}}")
}

tasks.register<Copy>("fetchDependencies") {
  from(configurations.runtimeClasspath)
  into(layout.buildDirectory.dir("resolved"))
}
