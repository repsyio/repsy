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
package io.repsy.protocols.maven.shared.storage.services;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import freemarker.template.Configuration;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

/**
 * RPS-1190: a version delete used to fail {@code Files.move} with {@code NoSuchFileException} when
 * the storage directory was already gone (an earlier partial delete, a manual cleanup, a wrong
 * version name reaching storage before the DB check). {@link
 * AbstractMavenStorageService#deleteArtifactVersion} is idempotent now: a missing directory is
 * treated as already deleted instead of failing.
 *
 * <p>RPS-1197: rewriting {@code maven-metadata.xml} (on a version delete) left a stale {@code
 * maven-metadata.xml.asc} and its checksum siblings behind, signing content that no longer matches.
 * They are deleted along with the rewrite now, and the bytes they freed are folded into the
 * returned usage delta.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractMavenStorageService")
class AbstractMavenStorageServiceTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
  private static final String REPO_NAME = "maven-repo";
  private static final String GROUP = "com.example";
  private static final String ARTIFACT = "demo";
  private static final String METADATA_FILENAME = "maven-metadata.xml";

  private static final String METADATA_XML =
      """
      <metadata>
        <groupId>com.example</groupId>
        <artifactId>demo</artifactId>
        <versioning>
          <latest>2.0</latest>
          <release>2.0</release>
          <versions><version>1.0</version><version>2.0</version></versions>
          <lastUpdated>20260101000000</lastUpdated>
        </versioning>
      </metadata>
      """;

  @Mock private StorageStrategy storageStrategy;
  @Mock private Configuration freeMarkerConfiguration;

  private AbstractMavenStorageService<UUID> storageService;

  private static ArgumentMatcher<StoragePath> pathEndingWith(final String fileName) {
    return storagePath -> storagePath != null && storagePath.getPath().endsWith(fileName);
  }

  @BeforeEach
  void setUp() {
    this.storageService =
        new AbstractMavenStorageService<>(this.freeMarkerConfiguration, this.storageStrategy) {};
  }

  @Test
  @DisplayName("deleteArtifactVersion is a no-op when the version directory is already gone")
  void deleteArtifactVersionIsIdempotentWhenDirectoryMissing() {

    when(this.storageStrategy.listDirectoryContents(any()))
        .thenThrow(new ItemNotFoundException("resourceNotFound"));

    final var usage = this.storageService.deleteArtifactVersion(REPO_ID, GROUP, ARTIFACT, "1.0");

    assertThat(usage).isZero();
    verify(this.storageStrategy, never()).delete(any());
    verify(this.storageStrategy, never()).calculatePathUsage(any());
  }

  @Test
  @DisplayName("deleteArtifactVersion deletes the directory and returns its usage when present")
  void deleteArtifactVersionDeletesExistingDirectory() {

    when(this.storageStrategy.listDirectoryContents(any())).thenReturn(List.of());
    when(this.storageStrategy.calculatePathUsage(any())).thenReturn(4096L);

    final var usage = this.storageService.deleteArtifactVersion(REPO_ID, GROUP, ARTIFACT, "1.0");

    assertThat(usage).isEqualTo(4096L);
    verify(this.storageStrategy).delete(argThat(pathEndingWith("com/example/demo/1.0")));
  }

  @Test
  @DisplayName(
      "deleting a version's metadata also deletes a stale maven-metadata.xml.asc family and folds"
          + " the freed bytes into the usage delta")
  void deleteVersionFromMetadataRemovesStaleSignatureFamily() throws Exception {

    final var repoInfo = BaseRepoInfo.<UUID>builder().storageKey(REPO_ID).name(REPO_NAME).build();

    final var ascResource = new ByteArrayResource(new byte[100]);
    final var ascSha1Resource = new ByteArrayResource(new byte[40]);

    when(this.storageStrategy.get(any(), anyString())).thenReturn(Optional.empty());
    when(this.storageStrategy.get(argThat(pathEndingWith(METADATA_FILENAME)), anyString()))
        .thenReturn(Optional.of(new ByteArrayResource(METADATA_XML.getBytes(UTF_8))));
    when(this.storageStrategy.get(argThat(pathEndingWith(METADATA_FILENAME + ".asc")), anyString()))
        .thenReturn(Optional.of(ascResource));
    when(this.storageStrategy.get(
            argThat(pathEndingWith(METADATA_FILENAME + ".asc.sha1")), anyString()))
        .thenReturn(Optional.of(ascSha1Resource));
    when(this.storageStrategy.write(anyString(), any(), any())).thenReturn(BaseUsages.ofDisk(-20L));

    final var result =
        this.storageService.deleteVersionFromMetadata(repoInfo, GROUP, ARTIFACT, "1.0");

    assertThat(result.getFirst().getVersions()).containsExactly("2.0");
    // -20 (metadata rewrite delta) - 100 (.asc) - 40 (.asc.sha1) = -160.
    assertThat(result.getSecond().getDiskUsage()).isEqualTo(-160L);

    verify(this.storageStrategy).delete(argThat(pathEndingWith(METADATA_FILENAME + ".asc")));
    verify(this.storageStrategy).delete(argThat(pathEndingWith(METADATA_FILENAME + ".asc.sha1")));
    verify(this.storageStrategy, never())
        .delete(argThat(pathEndingWith(METADATA_FILENAME + ".asc.md5")));
  }
}
