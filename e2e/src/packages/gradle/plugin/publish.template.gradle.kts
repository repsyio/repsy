// Rendered by clients/gradle-plugin.ts (mustache) next to settings.gradle.kts (the Kotlin DSL): the
// same plugin project as publish.template.gradle.
plugins {
  `java-gradle-plugin`
  `maven-publish`
}

group = "{{{groupId}}}"
version = "{{{version}}}"

gradlePlugin {
  plugins {
    create("e2e") {
      id = "{{{pluginId}}}"
      implementationClass = "io.repsy.e2e.plugin.MarkerPlugin"
    }
  }
}

publishing {
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
