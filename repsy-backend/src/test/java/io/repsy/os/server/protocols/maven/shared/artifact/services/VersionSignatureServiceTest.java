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
package io.repsy.os.server.protocols.maven.shared.artifact.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.VersionSignature;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionSignatureRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A version of a repo that verifies every signature is signed when it has files to sign and every
 * one of them has a verified signature (RPS-1188).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VersionSignatureService (RPS-1188)")
class VersionSignatureServiceTest {

  private static final String VERSION_PATH = "com/acme/lib/1.0";

  @Mock VersionSignatureRepository versionSignatureRepository;
  @Mock ArtifactVersionRepository artifactVersionRepository;
  @Mock StorageStrategy storageStrategy;

  private VersionSignatureService service;
  private final UUID storageKey = UUID.randomUUID();
  private final ArtifactVersion version = new ArtifactVersion();

  @BeforeEach
  void setUp() {
    this.service =
        new VersionSignatureService(
            this.versionSignatureRepository, this.artifactVersionRepository, this.storageStrategy);
    this.version.setId(UUID.randomUUID());
  }

  private void directoryHolds(final String... fileNames) {
    final var items =
        java.util.Arrays.stream(fileNames)
            .map(
                name ->
                    StorageItemInfo.builder()
                        .name(name)
                        .path("/data/" + this.storageKey + "/" + VERSION_PATH + "/" + name)
                        .build())
            .toList();

    when(this.storageStrategy.listStorageItems(any(StoragePath.class))).thenReturn(items);
  }

  private void verifiedFiles(final String... fileNames) {
    when(this.versionSignatureRepository.findFileNamesByArtifactVersionId(this.version.getId()))
        .thenReturn(List.of(fileNames));
  }

  @Test
  @DisplayName("is signed when every signable file has a verified signature")
  void signedWhenEverySignableFileIsVerified() {
    this.directoryHolds(
        "lib-1.0.pom", "lib-1.0.jar", "lib-1.0.jar.asc", "lib-1.0.jar.sha1", "maven-metadata.xml");
    this.verifiedFiles("lib-1.0.pom", "lib-1.0.jar");

    this.service.refreshSigned(this.storageKey, this.version, VERSION_PATH);

    assertThat(this.version.isSigned()).isTrue();
    verify(this.artifactVersionRepository).save(this.version);
  }

  @Test
  @DisplayName("is not signed while one signable file has no verified signature")
  void notSignedWhileAFileIsMissingItsSignature() {
    this.version.setSigned(true);
    this.directoryHolds("lib-1.0.pom", "lib-1.0.jar", "lib-1.0-sources.jar");
    this.verifiedFiles("lib-1.0.pom", "lib-1.0.jar");

    this.service.refreshSigned(this.storageKey, this.version, VERSION_PATH);

    assertThat(this.version.isSigned()).isFalse();
    verify(this.artifactVersionRepository).save(this.version);
  }

  @Test
  @DisplayName("is not signed when the directory holds nothing to sign")
  void notSignedWithoutSignableFiles() {
    this.directoryHolds("lib-1.0.jar.asc", "lib-1.0.jar.sha1", "maven-metadata.xml");
    this.verifiedFiles("lib-1.0.jar");

    this.service.refreshSigned(this.storageKey, this.version, VERSION_PATH);

    assertThat(this.version.isSigned()).isFalse();
    verify(this.artifactVersionRepository, never()).save(any());
  }

  @Test
  @DisplayName("ignores a signature row of a file that is gone, and saves nothing when unchanged")
  void aStaleRowDoesNotSignAndAnUnchangedValueIsNotSaved() {
    this.directoryHolds("lib-1.0.pom", "lib-1.0.jar");
    this.verifiedFiles("lib-1.0.pom", "lib-1.0-gone.jar");

    this.service.refreshSigned(this.storageKey, this.version, VERSION_PATH);

    assertThat(this.version.isSigned()).isFalse();
    verify(this.artifactVersionRepository, never()).save(any());
  }

  @Test
  @DisplayName("in a snapshot directory only the newest build has to be signed")
  void aSnapshotIsSignedByItsNewestBuild() {
    final var snapshotPath = "com/acme/lib/1.0-SNAPSHOT";
    final var items =
        java.util.stream.Stream.of(
                "lib-1.0-20260921.101010-1.pom",
                "lib-1.0-20260921.101010-1.jar",
                "lib-1.0-20260921.101010-2.pom",
                "lib-1.0-20260921.101010-2.jar")
            .map(
                name ->
                    StorageItemInfo.builder()
                        .name(name)
                        .path("/data/" + this.storageKey + "/" + snapshotPath + "/" + name)
                        .build())
            .toList();
    when(this.storageStrategy.listStorageItems(any(StoragePath.class))).thenReturn(items);
    this.verifiedFiles("lib-1.0-20260921.101010-2.pom", "lib-1.0-20260921.101010-2.jar");

    this.service.refreshSigned(this.storageKey, this.version, snapshotPath);

    assertThat(this.version.isSigned()).isTrue();
  }

  @Test
  @DisplayName("records a signature once: a second verification only moves its time")
  void recordVerifiedIsAnUpsert() {
    final var existing = new VersionSignature();
    existing.setFileName("lib-1.0.jar");
    when(this.versionSignatureRepository.findByArtifactVersionIdAndFileName(
            this.version.getId(), "lib-1.0.jar"))
        .thenReturn(Optional.of(existing));

    this.service.recordVerified(this.version, "lib-1.0.jar");

    verify(this.versionSignatureRepository).save(existing);
    assertThat(existing.getVerifiedAt()).isNotNull();
  }

  @Test
  @DisplayName("records a new row for a file whose signature was not verified before")
  void recordVerifiedInsertsAMissingRow() {
    when(this.versionSignatureRepository.findByArtifactVersionIdAndFileName(
            this.version.getId(), "lib-1.0.jar"))
        .thenReturn(Optional.empty());

    this.service.recordVerified(this.version, "lib-1.0.jar");

    final var saved = ArgumentCaptor.forClass(VersionSignature.class);
    verify(this.versionSignatureRepository).save(saved.capture());
    assertThat(saved.getValue().getArtifactVersion()).isSameAs(this.version);
    assertThat(saved.getValue().getFileName()).isEqualTo("lib-1.0.jar");
    assertThat(saved.getValue().getVerifiedAt()).isNotNull();
  }

  @Test
  @DisplayName("forgets the signature of a file that was stored again")
  void forgetDeletesTheRow() {
    this.service.forget(this.version, "lib-1.0.jar");

    verify(this.versionSignatureRepository)
        .deleteByArtifactVersionIdAndFileName(this.version.getId(), "lib-1.0.jar");
  }
}
