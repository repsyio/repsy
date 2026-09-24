/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.repsy.protocols.npm.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.protocols.npm.shared.utils.NpmRevPath.RevPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("NpmRevPath (RPS-1289)")
class NpmRevPathTest {

  @Test
  @DisplayName("the packument path of an unscoped package has no scope and no tarball")
  void unscopedPackage() {
    assertThat(NpmRevPath.parse("/left-pad/-rev/3-abc"))
        .contains(new RevPath(null, "left-pad", null, "3-abc"));
  }

  @Test
  @DisplayName("the packument path of a scoped package splits the scope from the name")
  void scopedPackage() {
    assertThat(NpmRevPath.parse("/@acme/left-pad/-rev/3-abc"))
        .contains(new RevPath("acme", "left-pad", null, "3-abc"));
  }

  @Test
  @DisplayName("the tarball path of an unscoped package names the tarball, not the package")
  void unscopedTarball() {
    assertThat(NpmRevPath.parse("/left-pad/-/left-pad-1.0.0.tgz/-rev/3-abc"))
        .contains(new RevPath(null, "left-pad", "left-pad-1.0.0.tgz", "3-abc"));
  }

  @Test
  @DisplayName("the tarball path of a scoped package names the tarball, not the package")
  void scopedTarball() {
    assertThat(NpmRevPath.parse("/@acme/left-pad/-/left-pad-1.0.0-beta.1.tgz/-rev/3-abc"))
        .contains(new RevPath("acme", "left-pad", "left-pad-1.0.0-beta.1.tgz", "3-abc"));
  }

  @Test
  @DisplayName("a tarball that repeats the scope, as some clients send it, still parses")
  void tarballThatRepeatsTheScope() {
    assertThat(NpmRevPath.parse("/@acme/left-pad/-/@acme/left-pad-1.0.0.tgz/-rev/3-abc"))
        .contains(new RevPath("acme", "left-pad", "left-pad-1.0.0.tgz", "3-abc"));
  }

  @ParameterizedTest(name = "{0} is not a -rev path")
  @ValueSource(
      strings = {
        "/left-pad",
        "/@acme/left-pad",
        "/left-pad/-/left-pad-1.0.0.tgz",
        "/left-pad/-rev/",
        "/left-pad/-rev",
        "/-rev/3-abc",
        "/-/-rev/3-abc",
        "/left-pad/-rev/3-abc/extra",
        "/left-pad/-/left-pad-1.0.0.tgz/x/-rev/3-abc",
        "/-/package/left-pad/dist-tags/next",
        ""
      })
  void notARevPath(final String relativePath) {
    assertThat(NpmRevPath.parse(relativePath)).isEmpty();
  }

  @Test
  @DisplayName("a tarball file name gives back its version, prerelease and build included")
  void versionOfTarball() {
    assertThat(NpmRevPath.versionOfTarball("left-pad", "left-pad-1.0.0.tgz")).contains("1.0.0");
    assertThat(NpmRevPath.versionOfTarball("left-pad", "left-pad-2.0.0-rc.1+b5.tgz"))
        .contains("2.0.0-rc.1+b5");
  }

  @ParameterizedTest(name = "{0} is not the tarball of left-pad")
  @ValueSource(
      strings = {"right-pad-1.0.0.tgz", "left-pad-.tgz", "left-pad-1.0.0.zip", "left-pad.tgz", ""})
  void notATarballOfThePackage(final String filename) {
    assertThat(NpmRevPath.versionOfTarball("left-pad", filename)).isEmpty();
  }
}
