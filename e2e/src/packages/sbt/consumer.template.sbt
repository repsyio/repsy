// Rendered by clients/sbt.ts (mustache) into a per-test work directory. A project that declares only
// the Repsy repository and the dependency line the panel shows (`"group" %% "artifact" % "version"`),
// whose `fetchDependencies` task copies the resolved files into target/resolved. Nothing else can
// supply the library: the resolvers are Repsy alone (no local Ivy or Maven repository, no Maven
// Central), and neither the Scala library nor the Scala compiler is resolved (autoScalaLibrary and
// managedScalaInstance are off), so the run needs nothing from the network but the Repsy repository.
ThisBuild / scalaVersion := "{{{scalaVersion}}}"

lazy val fetchDependencies = taskKey[Unit]("Copies the resolved dependency files into target/resolved")

lazy val root = (project in file("."))
  .settings(
    name := "consumer",
    externalResolvers := Seq(("Repsy" at "{{{repoUrl}}}").withAllowInsecureProtocol(true)),
    autoScalaLibrary := false,
    managedScalaInstance := false,
    libraryDependencies += ("{{{groupId}}}" %% "{{{artifactBase}}}" % "{{{version}}}").intransitive(),
{{#credentialsEnv}}
    credentials += Credentials("{{{realm}}}", "{{{host}}}", sys.env("E2E_SBT_USER"), sys.env("E2E_SBT_PASS")),
{{/credentialsEnv}}
{{#credentialsFile}}
    credentials += Credentials(Path.userHome / ".sbt" / ".credentials"),
{{/credentialsFile}}
    fetchDependencies := {
      val out = target.value / "resolved"
      IO.createDirectory(out)
      update.value.allFiles.filter(_.getName.endsWith(".jar")).foreach(f => IO.copyFile(f, out / f.getName))
    },
  )
