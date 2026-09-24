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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.VersionSignature;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionSignatureRepository;
import io.repsy.os.shared.repo.entities.Repo;
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

    final var order = inOrder(this.artifactVersionRepository, this.versionSignatureRepository);
    order.verify(this.artifactVersionRepository).lockForSignedUpdate(this.version.getId());
    order
        .verify(this.versionSignatureRepository)
        .findFileNamesByArtifactVersionId(this.version.getId());
    order.verify(this.artifactVersionRepository).updateSigned(this.version.getId(), true);
  }

  @Test
  @DisplayName("is not signed while one signable file has no verified signature")
  void notSignedWhileAFileIsMissingItsSignature() {
    this.version.setSigned(true);
    this.directoryHolds("lib-1.0.pom", "lib-1.0.jar", "lib-1.0-sources.jar");
    this.verifiedFiles("lib-1.0.pom", "lib-1.0.jar");

    this.service.refreshSigned(this.storageKey, this.version, VERSION_PATH);

    verify(this.artifactVersionRepository).updateSigned(this.version.getId(), false);
  }

  @Test
  @DisplayName("is not signed when the directory holds nothing to sign")
  void notSignedWithoutSignableFiles() {
    this.directoryHolds("lib-1.0.jar.asc", "lib-1.0.jar.sha1", "maven-metadata.xml");
    this.verifiedFiles("lib-1.0.jar");

    this.service.refreshSigned(this.storageKey, this.version, VERSION_PATH);

    verify(this.artifactVersionRepository).updateSigned(this.version.getId(), false);
  }

  @Test
  @DisplayName("ignores a signature row of a file that is gone")
  void aStaleRowDoesNotSign() {
    this.directoryHolds("lib-1.0.pom", "lib-1.0.jar");
    this.verifiedFiles("lib-1.0.pom", "lib-1.0-gone.jar");

    this.service.refreshSigned(this.storageKey, this.version, VERSION_PATH);

    verify(this.artifactVersionRepository).updateSigned(this.version.getId(), false);
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

    verify(this.artifactVersionRepository).updateSigned(this.version.getId(), true);
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

  @Test
  @DisplayName("without verify-all a version is signed by its POM's signature alone (RPS-1316)")
  void withoutVerifyAllOnlyThePomCounts() {
    this.verifiedFiles("lib-1.0.pom");

    this.service.refreshSigned(this.storageKey, this.version, VERSION_PATH, false);

    verify(this.artifactVersionRepository).lockForSignedUpdate(this.version.getId());
    verify(this.artifactVersionRepository).updateSigned(this.version.getId(), true);
    verifyNoInteractions(this.storageStrategy);
  }

  @Test
  @DisplayName("without verify-all a signed jar without a signed POM does not sign the version")
  void withoutVerifyAllAJarSignatureDoesNotCount() {
    this.verifiedFiles("lib-1.0.jar", "lib-1.0-sources.jar");

    this.service.refreshSigned(this.storageKey, this.version, VERSION_PATH, false);

    verify(this.artifactVersionRepository).updateSigned(this.version.getId(), false);
  }

  @Test
  @DisplayName("without verify-all any POM of a snapshot counts")
  void withoutVerifyAllAnyPomOfASnapshotCounts() {
    this.verifiedFiles("lib-1.0-20260921.101010-1.pom");

    this.service.refreshSigned(this.storageKey, this.version, "com/acme/lib/1.0-SNAPSHOT", false);

    verify(this.artifactVersionRepository).updateSigned(this.version.getId(), true);
  }

  @Test
  @DisplayName("refreshSigned without a setting is the verify-all rule")
  void refreshSignedIsTheVerifyAllRule() {
    this.directoryHolds("lib-1.0.pom", "lib-1.0.jar");
    this.verifiedFiles("lib-1.0.pom");

    this.service.refreshSigned(this.storageKey, this.version, VERSION_PATH);

    verify(this.artifactVersionRepository).updateSigned(this.version.getId(), false);
  }

  private ArtifactVersion storedVersion(final boolean verifyAll) {
    final var repo = new Repo();
    repo.setId(this.storageKey);
    repo.setPgpVerifyAllSignaturesEnabled(verifyAll);
    final var artifact = new Artifact();
    artifact.setRepo(repo);
    artifact.setGroupName("com.acme");
    artifact.setArtifactName("lib");
    this.version.setArtifact(artifact);
    this.version.setVersionName("1.0");
    when(this.artifactVersionRepository.findById(this.version.getId()))
        .thenReturn(Optional.of(this.version));

    return this.version;
  }

  @Test
  @DisplayName("recompute locks the version first and applies the verify-all rule of its repo")
  void recomputeAppliesVerifyAllOfTheRepo() {
    this.storedVersion(true);
    this.directoryHolds("lib-1.0.pom", "lib-1.0.jar");
    this.verifiedFiles("lib-1.0.pom");

    assertThat(this.service.recompute(this.version.getId())).isTrue();

    final var order = inOrder(this.artifactVersionRepository, this.storageStrategy);
    order.verify(this.artifactVersionRepository).lockForSignedUpdate(this.version.getId());
    order.verify(this.artifactVersionRepository).findById(this.version.getId());
    order
        .verify(this.storageStrategy)
        .listStorageItems(
            argThat(
                (StoragePath path) ->
                    path.getStorageKey().equals(this.storageKey)
                        && path.getRelativePath().getPath().equals(VERSION_PATH)));
    order.verify(this.artifactVersionRepository).updateSigned(this.version.getId(), false);
  }

  @Test
  @DisplayName("recompute applies the POM rule when the repo does not verify every signature")
  void recomputeAppliesThePomRuleWhenNotVerifyingAll() {
    this.storedVersion(false);
    this.verifiedFiles("lib-1.0.pom");

    assertThat(this.service.recompute(this.version.getId())).isTrue();

    verify(this.artifactVersionRepository).updateSigned(this.version.getId(), true);
    verifyNoInteractions(this.storageStrategy);
  }

  @Test
  @DisplayName("recompute of a version that is gone changes nothing")
  void recomputeOfAVanishedVersion() {
    when(this.artifactVersionRepository.findById(this.version.getId()))
        .thenReturn(Optional.empty());

    assertThat(this.service.recompute(this.version.getId())).isFalse();

    verify(this.artifactVersionRepository, never()).updateSigned(any(), anyBoolean());
  }
}
