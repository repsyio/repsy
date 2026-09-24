// Rendered by clients/gradle-plugin.ts (mustache) into the consumer project (the Kotlin DSL): the same
// settings as consumer-settings.template.gradle.
pluginManagement {
  repositories {
    maven {
      url = uri("{{{repoUrl}}}")
      isAllowInsecureProtocol = true
      {{#hasCredential}}
      credentials {
        username = providers.gradleProperty("repsyUsername").get()
        password = providers.gradleProperty("repsyPassword").get()
      }
      {{/hasCredential}}
    }
  }
  {{#legacy}}
  resolutionStrategy {
    eachPlugin {
      if (requested.id.id == "{{{pluginId}}}") {
        useModule("{{{groupId}}}:{{{artifactId}}}:{{{version}}}")
      }
    }
  }
  {{/legacy}}
}

rootProject.name = "{{{artifactId}}}-consumer"
