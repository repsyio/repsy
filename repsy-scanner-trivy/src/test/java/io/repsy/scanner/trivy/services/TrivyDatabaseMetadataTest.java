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
package io.repsy.scanner.trivy.services;

import static io.repsy.scanner.trivy.services.TrivyTestFiles.FAR_AHEAD;
import static io.repsy.scanner.trivy.services.TrivyTestFiles.LONG_AGO;
import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.scanner.trivy.config.TrivyScannerProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class TrivyDatabaseMetadataTest {

  private static final Instant UPDATED = Instant.parse("2026-09-26T19:03:57.371914884Z");

  @TempDir Path cacheDir;

  private TrivyDatabaseMetadata metadata() {
    return new TrivyDatabaseMetadata(
        new TrivyScannerProperties(
            "trivy", 30, "db", "java-db", this.cacheDir.toString(), Duration.ofHours(12)),
        JsonMapper.builder().build());
  }

  @Test
  void readsTheDatesTrivyRecordsWithTheirNanoseconds() throws IOException {
    TrivyTestFiles.writeVulnerabilityDb(this.cacheDir, "x", UPDATED, FAR_AHEAD);

    assertThat(this.metadata().vulnerabilityDbUpdatedAt()).contains(UPDATED);
    assertThat(this.metadata().vulnerabilityDb().orElseThrow().nextUpdate()).isEqualTo(FAR_AHEAD);
  }

  @Test
  void hasNoDatabaseWhenTheMetadataOrTheDatabaseFileIsMissing() throws IOException {
    assertThat(this.metadata().vulnerabilityDb()).isEmpty();

    TrivyTestFiles.writeVulnerabilityDb(this.cacheDir, "x", UPDATED, FAR_AHEAD);
    Files.delete(this.cacheDir.resolve("db/metadata.json"));
    assertThat(this.metadata().vulnerabilityDb()).isEmpty();

    TrivyTestFiles.writeVulnerabilityDb(this.cacheDir, "x", UPDATED, FAR_AHEAD);
    Files.delete(this.cacheDir.resolve("db/trivy.db"));
    assertThat(this.metadata().vulnerabilityDb()).isEmpty();
  }

  @Test
  void hasNoDatabaseWhenTheMetadataIsCorrupt() throws IOException {
    TrivyTestFiles.writeVulnerabilityDb(this.cacheDir, "x", UPDATED, FAR_AHEAD);
    Files.writeString(this.cacheDir.resolve("db/metadata.json"), "{not json");

    assertThat(this.metadata().vulnerabilityDb()).isEmpty();
  }

  @Test
  void isNotDueWhileBothDatabasesAreBeforeTheirNextUpdate() throws IOException {
    TrivyTestFiles.writeVulnerabilityDb(this.cacheDir, "x", UPDATED, FAR_AHEAD);
    TrivyTestFiles.writeJavaDb(this.cacheDir, "x", UPDATED, FAR_AHEAD);

    assertThat(this.metadata().isRefreshDue(Instant.now())).isFalse();
  }

  @Test
  void isDueWhenADatabaseIsMissingOrPastItsNextUpdate() throws IOException {
    assertThat(this.metadata().isRefreshDue(Instant.now())).isTrue();

    TrivyTestFiles.writeVulnerabilityDb(this.cacheDir, "x", UPDATED, FAR_AHEAD);
    assertThat(this.metadata().isRefreshDue(Instant.now())).as("no Java database").isTrue();

    TrivyTestFiles.writeJavaDb(this.cacheDir, "x", UPDATED, FAR_AHEAD);
    assertThat(this.metadata().isRefreshDue(Instant.now())).isFalse();

    TrivyTestFiles.writeJavaDb(this.cacheDir, "x", UPDATED, LONG_AGO);
    assertThat(this.metadata().isRefreshDue(Instant.now())).as("Java database past").isTrue();

    TrivyTestFiles.writeJavaDb(this.cacheDir, "x", UPDATED, FAR_AHEAD);
    TrivyTestFiles.writeVulnerabilityDb(this.cacheDir, "x", UPDATED, LONG_AGO);
    assertThat(this.metadata().isRefreshDue(Instant.now())).as("vulnerability db past").isTrue();
  }
}
