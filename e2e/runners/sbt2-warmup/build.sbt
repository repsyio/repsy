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

// A throwaway project the maven runner image builds once (maven.Dockerfile) to prime the sbt 2.x caches:
// it names Scala 3 (the version sbt 2.x targets by default), so `+update +compile +package +makePom`
// downloads the sbt jars, the Scala 3 compiler, its compiler bridge and the scala3-library.
// Keep the version equal to `SCALA_3` in src/clients/sbt.ts.
ThisBuild / organization := "io.repsy.e2e.warm"
ThisBuild / version := "0.0.0"
ThisBuild / scalaVersion := "3.3.8"

name := "warm"
