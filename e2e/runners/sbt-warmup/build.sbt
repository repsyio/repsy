// Copyright 2026 the original author or authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// A throwaway project the maven runner image builds once (maven.Dockerfile) to prime the sbt caches:
// it names every Scala version the sbt templates of e2e/src/packages/sbt use, so `+update +compile
// +package +makePom` downloads the sbt jars, both compilers, their compiler bridges and the
// scala-library of each. Keep the versions equal to `SCALA_213` and `SCALA_3` in src/clients/sbt.ts.
ThisBuild / organization := "io.repsy.e2e.warm"
ThisBuild / version := "0.0.0"
ThisBuild / scalaVersion := "2.13.18"
ThisBuild / crossScalaVersions := Seq("2.13.18", "3.3.8")

name := "warm"
