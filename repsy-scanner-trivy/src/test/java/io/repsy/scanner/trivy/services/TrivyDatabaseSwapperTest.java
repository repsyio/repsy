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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrivyDatabaseSwapperTest {

  private static final Instant OLD = Instant.parse("2026-09-01T00:00:00Z");
  private static final Instant NEW = Instant.parse("2026-09-26T00:00:00Z");

  @TempDir Path tempDir;

  private Path live() {
    return this.tempDir.resolve("live");
  }

  private Path staging() {
    return this.tempDir.resolve("live/refresh-staging");
  }

  @Test
  void replacesBothDatabasesAndTheirMetadata() throws IOException {
    TrivyTestFiles.writeVulnerabilityDb(this.live(), "old", OLD, FAR_AHEAD);
    TrivyTestFiles.writeJavaDb(this.live(), "old-java", OLD, FAR_AHEAD);
    TrivyTestFiles.writeVulnerabilityDb(this.staging(), "new", NEW, FAR_AHEAD);
    TrivyTestFiles.writeJavaDb(this.staging(), "new-java", NEW, FAR_AHEAD);

    TrivyDatabaseSwapper.swap(this.staging(), this.live());

    assertThat(this.live().resolve("db/trivy.db")).hasContent("new");
    assertThat(this.live().resolve("java-db/trivy-java.db")).hasContent("new-java");
    assertThat(this.live().resolve("db/metadata.json")).content().contains(NEW.toString());
    assertThat(this.live().resolve("java-db/metadata.json")).content().contains(NEW.toString());
  }

  @Test
  void createsTheLiveDirectoriesOfAFirstDownload() throws IOException {
    TrivyTestFiles.writeVulnerabilityDb(this.staging(), "new", NEW, FAR_AHEAD);
    TrivyTestFiles.writeJavaDb(this.staging(), "new-java", NEW, FAR_AHEAD);

    TrivyDatabaseSwapper.swap(this.staging(), this.live());

    assertThat(this.live().resolve("db/trivy.db")).hasContent("new");
  }

  @Test
  void touchesNothingWhenADownloadIsIncomplete() throws IOException {
    TrivyTestFiles.writeVulnerabilityDb(this.live(), "old", OLD, FAR_AHEAD);
    TrivyTestFiles.writeJavaDb(this.live(), "old-java", OLD, FAR_AHEAD);
    TrivyTestFiles.writeVulnerabilityDb(this.staging(), "new", NEW, FAR_AHEAD);
    // no Java database, and a vulnerability database without its metadata are both incomplete

    assertThatThrownBy(() -> TrivyDatabaseSwapper.swap(this.staging(), this.live()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("java-db");
    assertThat(this.live().resolve("db/trivy.db")).hasContent("old");

    TrivyTestFiles.writeJavaDb(this.staging(), "new-java", NEW, FAR_AHEAD);
    Files.delete(this.staging().resolve("db/metadata.json"));

    assertThatThrownBy(() -> TrivyDatabaseSwapper.swap(this.staging(), this.live()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("db");
    assertThat(this.live().resolve("db/trivy.db")).hasContent("old");
    assertThat(this.live().resolve("java-db/trivy-java.db")).hasContent("old-java");
  }

  @Test
  void recreateEmptiesADirectoryThatAnInterruptedRefreshLeft() throws IOException {
    TrivyTestFiles.writeVulnerabilityDb(this.staging(), "partial", NEW, FAR_AHEAD);

    TrivyDatabaseSwapper.recreate(this.staging());

    assertThat(this.staging()).isEmptyDirectory();
  }

  @Test
  void deleteRecursivelyQuietlyIgnoresAMissingDirectory() {
    TrivyDatabaseSwapper.deleteRecursivelyQuietly(this.tempDir.resolve("nothing"));

    assertThat(this.tempDir.resolve("nothing")).doesNotExist();
  }
}
