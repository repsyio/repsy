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
package io.repsy.os.server.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.shared.repo.dtos.RepoInfo;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RPS-1576: {@code shouldSkipAuthentication} used to be copy-pasted into {@code
 * CargoAuthPreProcessor}, {@code NuGetAuthPreProcessor} and {@code HelmAuthPreProcessor}
 * (byte-for-byte identical), with {@code RubyAuthPreProcessor} reaching into Cargo's copy across a
 * module boundary. This test pins the one shared truth table so a future edit to any of the four
 * pre-processors that now call this method cannot silently change the decision.
 */
@DisplayName("PreProcessorUtils.shouldSkipAuthentication")
class PreProcessorUtilsTest {

  private static final String SKIP_KEY = "skipPreProcessor";
  private static final String WRITE_KEY = "writeOperation";

  private static RepoInfo repoOf(final boolean privateRepo) {
    return RepoInfo.builder()
        .id(UUID.randomUUID())
        .storageKey(UUID.randomUUID())
        .name("repo")
        .privateRepo(privateRepo)
        .build();
  }

  private static boolean shouldSkip(final RepoInfo repoInfo, final Map<String, Object> properties) {
    return PreProcessorUtils.shouldSkipAuthentication(SKIP_KEY, WRITE_KEY, repoInfo, properties);
  }

  @Test
  @DisplayName("the skip-preprocessor property always skips, even for a private write")
  void skipPropertyWins() {
    final var properties = Map.<String, Object>of(SKIP_KEY, true, WRITE_KEY, true);

    assertThat(shouldSkip(repoOf(true), properties)).isTrue();
  }

  @Test
  @DisplayName("a read of a public repo is skipped")
  void publicRead() {
    final var properties = Map.<String, Object>of(WRITE_KEY, false);

    assertThat(shouldSkip(repoOf(false), properties)).isTrue();
  }

  @Test
  @DisplayName("a write to a public repo is not skipped")
  void publicWrite() {
    final var properties = Map.<String, Object>of(WRITE_KEY, true);

    assertThat(shouldSkip(repoOf(false), properties)).isFalse();
  }

  @Test
  @DisplayName("a read of a private repo is not skipped")
  void privateRead() {
    final var properties = Map.<String, Object>of(WRITE_KEY, false);

    assertThat(shouldSkip(repoOf(true), properties)).isFalse();
  }

  @Test
  @DisplayName("a write to a private repo is not skipped")
  void privateWrite() {
    final var properties = Map.<String, Object>of(WRITE_KEY, true);

    assertThat(shouldSkip(repoOf(true), properties)).isFalse();
  }

  @Test
  @DisplayName("a missing write-operation property defaults to a read")
  void missingWriteKeyDefaultsToRead() {
    assertThat(shouldSkip(repoOf(false), Map.of())).isTrue();
    assertThat(shouldSkip(repoOf(true), Map.of())).isFalse();
  }
}
