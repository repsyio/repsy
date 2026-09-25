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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Consistency checks on the root {@code Dockerfile}, without building the image (no Docker needed).
 * CI is off on purpose, so these run with {@code mvn test}.
 *
 * <ul>
 *   <li>RPS-1401: everything the image persists lives under a {@code VOLUME}, so a recreated
 *       container keeps its artifacts along with its database.
 *   <li>RPS-1166: the pnpm version the image installs is the one CI and the e2e runners install.
 * </ul>
 */
@DisplayName("Dockerfile: volume paths (RPS-1401) and the pnpm pin (RPS-1166)")
class DockerfileTest {

  private static final Pattern PNPM_PIN = Pattern.compile("pnpm@(\\d+\\.\\d+\\.\\d+)\\b");

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

  private static String read(final Path path) throws IOException {
    return Files.readString(path);
  }

  /** The final (runtime) stage: what {@code docker run} executes. */
  private static String runtimeStage() throws IOException {
    final String dockerfile = read(ROOT.resolve("Dockerfile"));
    final int start = dockerfile.lastIndexOf("\nFROM ");
    assertThat(start).as("the runtime stage's FROM").isPositive();
    return dockerfile.substring(start);
  }

  /** The value of {@code ENV <name>="..."} in the runtime stage. */
  private static String env(final String stage, final String name) {
    final Matcher m =
        Pattern.compile("^ENV\\s+" + name + "=\"?([^\"\\n]+)\"?\\s*$", Pattern.MULTILINE)
            .matcher(stage);
    assertThat(m.find()).as("ENV %s in the runtime stage", name).isTrue();
    return m.group(1);
  }

  private static List<String> volumes(final String stage) {
    final var result = new ArrayList<String>();
    final Matcher m = Pattern.compile("^VOLUME\\s+(.+)$", Pattern.MULTILINE).matcher(stage);
    while (m.find()) {
      for (final String v : m.group(1).replaceAll("[\\[\\]\",]", " ").trim().split("\\s+")) {
        result.add(v);
      }
    }
    return result;
  }

  /** The directories the {@code mkdir -p} of the runtime stage creates. */
  private static List<String> createdDirs(final String stage) {
    final Matcher m = Pattern.compile("mkdir -p ([^&\\\\\\n]+)").matcher(stage);
    assertThat(m.find()).as("a mkdir -p in the runtime stage").isTrue();
    return List.of(m.group(1).trim().split("\\s+"));
  }

  private static boolean isUnder(final String path, final List<String> roots) {
    return roots.stream().anyMatch(root -> path.equals(root) || path.startsWith(root + "/"));
  }

  @Test
  @DisplayName("RPS-1401: the image default artifact storage is under a VOLUME and is created")
  void artifactStorageIsOnTheVolume() throws IOException {
    final String stage = runtimeStage();
    final String storage = env(stage, "STORAGE_BASE_PATH");

    assertThat(volumes(stage)).as("VOLUME paths").isNotEmpty();
    assertThat(isUnder(storage, volumes(stage)))
        .as("STORAGE_BASE_PATH=%s is under one of %s", storage, volumes(stage))
        .isTrue();
    assertThat(createdDirs(stage)).as("created and chowned by mkdir -p").contains(storage);
  }

  @Test
  @DisplayName("RPS-1401: the password reset directory is under a VOLUME and is created")
  void passwordResetDirIsOnTheVolume() throws IOException {
    final String stage = runtimeStage();
    final String dir = env(stage, "PASSWORD_RESET_MARKER_DIR");

    assertThat(isUnder(dir, volumes(stage))).as("%s is under a VOLUME", dir).isTrue();
    assertThat(createdDirs(stage)).contains(dir);
  }

  @Test
  @DisplayName("RPS-1401: the default H2 database file is under a VOLUME")
  void h2FileIsOnTheVolume() throws IOException {
    final String stage = runtimeStage();
    final String url = env(stage, "DB_URL");
    final Matcher m = Pattern.compile("^jdbc:h2:file:([^;]+)").matcher(url);

    assertThat(m.find()).as("the image's DB_URL is an H2 file url").isTrue();
    assertThat(isUnder(m.group(1), volumes(stage)))
        .as("the H2 file %s is under a VOLUME", m.group(1))
        .isTrue();
  }

  @Test
  @DisplayName("RPS-1401: entrypoint.sh's legacy fallback names the same default directory")
  void entrypointFallbackMatchesTheImageDefault() throws IOException {
    final String storage = env(runtimeStage(), "STORAGE_BASE_PATH");

    assertThat(read(ROOT.resolve("entrypoint.sh")))
        .contains("DEFAULT_STORAGE_DIR=\"" + storage + "\"")
        .contains("/.repsy");
  }

  @Test
  @DisplayName("RPS-1166: the Dockerfile pnpm pin equals CI's setup-frontend pin and is a version")
  void dockerfilePnpmEqualsTheCiPin() throws IOException {
    final String image = pin(ROOT.resolve("Dockerfile"), "corepack prepare");
    final String ci =
        pin(ROOT.resolve(".github/actions/setup-frontend/action.yml"), "npm install -g");

    assertThat(image).as("the Dockerfile's pnpm version").isEqualTo(ci);
    assertThat(read(ROOT.resolve("Dockerfile"))).doesNotContain("pnpm@latest");
  }

  @Test
  @DisplayName("RPS-1166: every e2e runner image installs the same pnpm as CI")
  void e2eRunnersUseTheCiPin() throws IOException {
    final String ci =
        pin(ROOT.resolve(".github/actions/setup-frontend/action.yml"), "npm install -g");

    final var runners = new ArrayList<Path>();
    try (Stream<Path> files = Files.list(ROOT.resolve("e2e/runners"))) {
      files.filter(p -> p.getFileName().toString().endsWith(".Dockerfile")).forEach(runners::add);
    }
    int checked = 0;
    for (final Path runner : runners) {
      final Matcher m = PNPM_PIN.matcher(read(runner));
      while (m.find()) {
        assertThat(m.group(1)).as("pnpm in %s", ROOT.relativize(runner)).isEqualTo(ci);
        checked++;
      }
    }
    assertThat(checked).as("e2e runner pins found").isPositive();
  }

  @Test
  @DisplayName("RPS-1166: the frontend package.json does not pin a different pnpm")
  void frontendPackageJsonAgrees() throws IOException {
    final String ci =
        pin(ROOT.resolve(".github/actions/setup-frontend/action.yml"), "npm install -g");
    final Matcher m =
        Pattern.compile("\"packageManager\"\\s*:\\s*\"pnpm@(\\d+\\.\\d+\\.\\d+)")
            .matcher(read(ROOT.resolve("repsy-frontend/package.json")));

    if (m.find()) {
      assertThat(m.group(1)).isEqualTo(ci);
    }
  }

  /** The {@code pnpm@x.y.z} version on the first line of the file that contains {@code marker}. */
  private static String pin(final Path file, final String marker) throws IOException {
    for (final String line : Files.readAllLines(file)) {
      if (!line.stripLeading().startsWith("#") && line.contains(marker)) {
        final Matcher m = PNPM_PIN.matcher(line);
        if (m.find()) {
          return m.group(1);
        }
      }
    }
    throw new AssertionError("no pinned pnpm@x.y.z on a '" + marker + "' line in " + file);
  }
}
