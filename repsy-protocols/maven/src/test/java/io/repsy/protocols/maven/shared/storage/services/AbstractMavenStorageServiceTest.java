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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import freemarker.template.Configuration;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.exceptions.IsADirectoryException;
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
  @DisplayName("deleteArtifactVersion frees nothing when the version directory is already gone")
  void deleteArtifactVersionIsIdempotentWhenDirectoryMissing() {

    // The storage strategy's delete is idempotent, and a directory that is gone has no usage.
    when(this.storageStrategy.calculatePathUsage(any())).thenReturn(0L);

    final var usage = this.storageService.deleteArtifactVersion(REPO_ID, GROUP, ARTIFACT, "1.0");

    assertThat(usage).isZero();
    verify(this.storageStrategy).delete(argThat(pathEndingWith("com/example/demo/1.0")));
  }

  @Test
  @DisplayName("deleteGroup succeeds when the group directory is already gone (RPS-1290)")
  void deleteGroupIsIdempotentWhenDirectoryMissing() {

    when(this.storageStrategy.listDirectoryContents(any()))
        .thenThrow(new ItemNotFoundException("resourceNotFound"));
    when(this.storageStrategy.calculatePathUsage(any())).thenReturn(0L);

    final var usage = this.storageService.deleteGroup(REPO_ID, GROUP, List.of(ARTIFACT));

    assertThat(usage).isZero();
    verify(this.storageStrategy).delete(argThat(pathEndingWith("com/example/demo")));
  }

  @Test
  @DisplayName("deleteArtifactVersion deletes the directory and returns its usage when present")
  void deleteArtifactVersionDeletesExistingDirectory() {

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

    // -20 (metadata rewrite delta) - 100 (.asc) - 40 (.asc.sha1) = -160.
    assertThat(result.getDiskUsage()).isEqualTo(-160L);

    verify(this.storageStrategy).delete(argThat(pathEndingWith(METADATA_FILENAME + ".asc")));
    verify(this.storageStrategy).delete(argThat(pathEndingWith(METADATA_FILENAME + ".asc.sha1")));
    verify(this.storageStrategy, never())
        .delete(argThat(pathEndingWith(METADATA_FILENAME + ".asc.md5")));
  }

  @Test
  @DisplayName(
      "deleting a version's metadata of an artifact that has no maven-metadata.xml is nothing to"
          + " rewrite: zero usage, no file written or deleted, no exception (RPS-1331)")
  void deleteVersionFromMetadataWithoutMetadataFileIsANoOp() throws Exception {

    final var repoInfo = BaseRepoInfo.<UUID>builder().storageKey(REPO_ID).name(REPO_NAME).build();
    when(this.storageStrategy.get(any(), anyString())).thenReturn(Optional.empty());

    final var result =
        this.storageService.deleteVersionFromMetadata(repoInfo, GROUP, ARTIFACT, "1.0");

    assertThat(result.getDiskUsage()).isZero();
    verify(this.storageStrategy, never()).write(anyString(), any(), any());
    verify(this.storageStrategy, never()).delete(any());
  }

  @Test
  @DisplayName(
      "deleting a version's metadata of a file without <versioning> leaves the file alone and"
          + " answers zero usage instead of a NullPointerException (RPS-1331)")
  void deleteVersionFromMetadataWithoutVersioningIsANoOp() throws Exception {

    final var repoInfo = BaseRepoInfo.<UUID>builder().storageKey(REPO_ID).name(REPO_NAME).build();
    when(this.storageStrategy.get(any(), anyString()))
        .thenReturn(Optional.of(new ByteArrayResource("<metadata/>".getBytes(UTF_8))));

    final var result =
        this.storageService.deleteVersionFromMetadata(repoInfo, GROUP, ARTIFACT, "1.0");

    assertThat(result.getDiskUsage()).isZero();
    verify(this.storageStrategy, never()).write(anyString(), any(), any());
    verify(this.storageStrategy, never()).delete(any());
  }

  @Test
  @DisplayName(
      "deleting a version's metadata of a file that cannot be parsed fails before anything is"
          + " written or deleted (RPS-1331)")
  void deleteVersionFromMetadataWithUnparsableFileChangesNothing() {

    final var repoInfo = BaseRepoInfo.<UUID>builder().storageKey(REPO_ID).name(REPO_NAME).build();
    when(this.storageStrategy.get(any(), anyString()))
        .thenReturn(Optional.of(new ByteArrayResource("<metadata><versioning>".getBytes(UTF_8))));

    assertThatThrownBy(
            () -> this.storageService.deleteVersionFromMetadata(repoInfo, GROUP, ARTIFACT, "1.0"))
        .isInstanceOf(BadRequestException.class);

    verify(this.storageStrategy, never()).write(anyString(), any(), any());
    verify(this.storageStrategy, never()).delete(any());
  }

  @Test
  @DisplayName("exists tells a stored file, a missing one and a directory (RPS-1199)")
  void existsTellsStoredMissingAndDirectory() {
    final var pom = StoragePath.of(REPO_ID, "com/example/demo/1.0/demo-1.0.pom");
    final var missing = StoragePath.of(REPO_ID, "com/example/demo/2.0/demo-2.0.pom");
    final var directory = StoragePath.of(REPO_ID, "com/example/demo/3.0/demo-3.0.pom");
    when(this.storageStrategy.get(pom, REPO_NAME))
        .thenReturn(Optional.of(new ByteArrayResource("<project/>".getBytes(UTF_8))));
    when(this.storageStrategy.get(missing, REPO_NAME)).thenReturn(Optional.empty());
    when(this.storageStrategy.get(directory, REPO_NAME)).thenThrow(new IsADirectoryException());

    assertThat(this.storageService.exists(pom, REPO_NAME)).isTrue();
    assertThat(this.storageService.exists(missing, REPO_NAME)).isFalse();
    assertThat(this.storageService.exists(directory, REPO_NAME)).isTrue();
  }

  @Test
  @DisplayName("deleteFile soft-deletes only the one file (RPS-1199)")
  void deleteFileSoftDeletesOnlyTheFile() {
    final var pom = StoragePath.of(REPO_ID, "com/example/demo/1.0/demo-1.0.pom");

    this.storageService.deleteFile(pom);

    verify(this.storageStrategy).delete(pom);
    verify(this.storageStrategy, never()).calculatePathUsage(any());
    verify(this.storageStrategy, never()).listDirectoryContents(any());
  }
}
