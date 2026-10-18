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
package io.repsy.os;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RPS-2265: the root POM consumes a released repsy-core. Its version is written twice, as the
 * version of the parent (Maven allows no property there) and as {@code repsy-core.version}, which
 * the core-bom import uses for every {@code io.repsy.core} dependency of the modules. The two must
 * be equal, and a release: a SNAPSHOT would be resolved from a repository that serves none.
 */
@DisplayName("Root POM: the repsy-core pin (RPS-2265)")
class CoreVersionPinTest {

  private static final Pattern PARENT_VERSION =
      Pattern.compile(
          "<parent>\\s*<groupId>io\\.repsy\\.core</groupId>\\s*<artifactId>core-parent</artifactId>"
              + "\\s*<version>([^<]+)</version>\\s*</parent>");
  private static final Pattern CORE_VERSION =
      Pattern.compile("<repsy-core\\.version>([^<]+)</repsy-core\\.version>");
  private static final Pattern RELEASE = Pattern.compile("\\d+\\.\\d+\\.\\d+");

  /**
   * The repository root: the tests run with the module directory ({@code repsy-backend}) as cwd.
   */
  private static final Path ROOT = findRoot();

  private static Path findRoot() {
    final Path cwd = Path.of("").toAbsolutePath();
    for (Path dir = cwd; dir != null; dir = dir.getParent()) {
      if (Files.isRegularFile(dir.resolve("Dockerfile"))
          && Files.isRegularFile(dir.resolve("entrypoint.sh"))) {
        return dir;
      }
    }
    throw new IllegalStateException("No Dockerfile above " + cwd);
  }

  private static String find(final String pom, final Pattern pattern, final String what) {
    final Matcher m = pattern.matcher(pom);
    assertThat(m.find()).as("%s in the root pom.xml", what).isTrue();
    return m.group(1).trim();
  }

  @Test
  @DisplayName("the parent version equals repsy-core.version")
  void parentVersionEqualsCoreVersionProperty() throws IOException {
    final String pom = Files.readString(ROOT.resolve("pom.xml"));

    assertThat(find(pom, PARENT_VERSION, "the core-parent parent"))
        .as("<parent> version and <repsy-core.version> of the root pom.xml: bump both together")
        .isEqualTo(find(pom, CORE_VERSION, "<repsy-core.version>"));
  }

  @Test
  @DisplayName("the pinned repsy-core version is a release, not a SNAPSHOT")
  void pinnedVersionIsARelease() throws IOException {
    final String pom = Files.readString(ROOT.resolve("pom.xml"));

    assertThat(find(pom, CORE_VERSION, "<repsy-core.version>"))
        .as("repsy-core is consumed as a release from repo.repsy.io")
        .matches(RELEASE);
  }
}
