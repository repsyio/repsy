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
package io.repsy.protocols.maven.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MavenStoragePathUtils")
class MavenStoragePathUtilsTest {

  @Test
  @DisplayName("lists the directories of a group, the deepest first")
  void listsTheGroupDirectoriesDeepestFirst() {
    assertThat(MavenStoragePathUtils.groupPaths("io.repsy.test"))
        .containsExactly(Path.of("io/repsy/test"), Path.of("io/repsy"), Path.of("io"));
  }

  @Test
  @DisplayName("a one-segment group is its own and only directory")
  void aOneSegmentGroupHasOneDirectory() {
    assertThat(MavenStoragePathUtils.groupPaths("junit")).containsExactly(Path.of("junit"));
  }

  @Test
  @DisplayName("an artifact sits in its group's directory")
  void anArtifactSitsInItsGroupDirectory() {
    assertThat(MavenStoragePathUtils.artifactPath("io.repsy.test", "demo-app"))
        .isEqualTo(Path.of("io/repsy/test/demo-app"));
    assertThat(MavenStoragePathUtils.artifactPath("junit", "junit"))
        .isEqualTo(Path.of("junit/junit"));
  }
}
