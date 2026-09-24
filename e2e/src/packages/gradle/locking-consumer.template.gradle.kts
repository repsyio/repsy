// Rendered by clients/gradle-consumer.ts (mustache) into a consumer project that lives for a whole
// test (the Kotlin DSL): the same consumer as locking-consumer.template.gradle.
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

{{#locking}}
dependencyLocking {
  lockAllConfigurations()
  {{#strict}}
  lockMode.set(LockMode.STRICT)
  {{/strict}}
}

{{/locking}}
dependencies {
  {{#dependencies}}
  implementation("{{{.}}}")
  {{/dependencies}}
}

tasks.register<Sync>("fetchDependencies") {
  from(configurations.runtimeClasspath)
  into(layout.buildDirectory.dir("resolved"))
}
