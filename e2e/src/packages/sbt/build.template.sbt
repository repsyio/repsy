// Rendered by clients/sbt.ts (mustache) into a per-test work directory, next to project/build.properties.
// A tiny Scala library: sbt compiles the one source file, packs it with the marker resource
// clients/sbt.ts writes, and `publish` uploads the jar and the POM to a Maven repository (the Ivy
// publisher, publishMavenStyle) the way a real project does. The Repsy credential is a Credentials
// entry from the REPSY_E2E_USER/REPSY_E2E_PASS environment variables, or from the run's own
// ~/.sbt/.credentials file; the anonymous credential renders no credentials at all, so sbt sends
// no Authorization header.
ThisBuild / organization := "{{{groupId}}}"
ThisBuild / version := "{{{version}}}"
ThisBuild / scalaVersion := "{{{scalaVersion}}}"
ThisBuild / crossScalaVersions := Seq({{{crossScalaVersions}}})

lazy val root = (project in file("."))
  .settings(
    name := "{{{artifactBase}}}",
    publishMavenStyle := true,
    publishTo := Some(("Repsy" at "{{{repoUrl}}}").withAllowInsecureProtocol(true)),
    // What a release republish does depends on this switch; the catalog scenarios need the server's
    // own rule to decide, so the default is to let sbt send the file.
    publishConfiguration := publishConfiguration.value.withOverwrite({{{overwrite}}}),
    Compile / packageDoc / publishArtifact := {{{withDocs}}},
    Compile / packageSrc / publishArtifact := {{{withDocs}}},
{{#credentialsEnv}}
    credentials += Credentials("{{{realm}}}", "{{{host}}}", sys.env("REPSY_E2E_USER"), sys.env("REPSY_E2E_PASS")),
{{/credentialsEnv}}
{{#credentialsFile}}
    credentials += Credentials(Path.userHome / ".sbt" / ".credentials"),
{{/credentialsFile}}
  )
