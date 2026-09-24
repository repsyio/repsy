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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.SignatureNotVerifiedException;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.VersionSignature;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionSignatureRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.dtos.PublicKeySources;
import io.repsy.os.server.protocols.maven.shared.keystore.services.KeyStoreService;
import io.repsy.os.server.protocols.maven.shared.keystore.services.PGPVerifierService;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

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
  @Mock KeyStoreService keyStoreService;
  @Mock PGPVerifierService pgpVerifierService;

  private VersionSignatureService service;
  private final UUID storageKey = UUID.randomUUID();
  private final ArtifactVersion version = new ArtifactVersion();

  @BeforeEach
  void setUp() {
    this.service =
        new VersionSignatureService(
            this.versionSignatureRepository,
            this.artifactVersionRepository,
            this.storageStrategy,
            this.keyStoreService,
            this.pgpVerifierService);
    this.version.setId(UUID.randomUUID());
    // Nothing is stored unless a test says so.
    lenient()
        .when(this.storageStrategy.get(any(StoragePath.class), any()))
        .thenReturn(Optional.empty());
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
    repo.setName("mvn");
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

  private static final PublicKeySources SOURCES = new PublicKeySources(List.of(), List.of(), true);

  private Resource stored(final String relativePath, final String content) {
    final var resource =
        new ByteArrayResource(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    when(this.storageStrategy.get(
            argThat(path -> path != null && path.getRelativePath().getPath().equals(relativePath)),
            eq("mvn")))
        .thenReturn(Optional.of(resource));

    return resource;
  }

  @Test
  @DisplayName("lockAndIsVerifyAll takes the lock and then reads the setting that is committed")
  void lockAndIsVerifyAllReadsAfterTheLock() {
    when(this.artifactVersionRepository.findVerifyAllSignaturesEnabledByVersionId(
            this.version.getId()))
        .thenReturn(Optional.of(true));

    assertThat(this.service.lockAndIsVerifyAll(this.version)).isTrue();

    final var order = inOrder(this.artifactVersionRepository);
    order.verify(this.artifactVersionRepository).lockForSignedUpdate(this.version.getId());
    order
        .verify(this.artifactVersionRepository)
        .findVerifyAllSignaturesEnabledByVersionId(this.version.getId());
  }

  @Test
  @DisplayName("lockAndIsVerifyAll answers off for a repo that cannot be found")
  void lockAndIsVerifyAllOfAVanishedRepoIsOff() {
    assertThat(this.service.lockAndIsVerifyAll(this.version)).isFalse();
  }

  @Test
  @DisplayName("turning verify-all on verifies a stored .asc that was never verified and counts it")
  void recomputeVerifiesAStoredSignatureThatWasNeverVerified() {
    this.storedVersion(true);
    this.directoryHolds("lib-1.0.pom", "lib-1.0.jar", "lib-1.0.jar.asc", "lib-1.0.pom.asc");
    when(this.versionSignatureRepository.findFileNamesByArtifactVersionId(this.version.getId()))
        .thenReturn(List.of("lib-1.0.pom"), List.of("lib-1.0.pom", "lib-1.0.jar"));
    final var jar = this.stored(VERSION_PATH + "/lib-1.0.jar", "jar");
    final var signature = this.stored(VERSION_PATH + "/lib-1.0.jar.asc", "sig");
    this.stored(VERSION_PATH + "/lib-1.0.pom", "pom");
    this.stored(VERSION_PATH + "/lib-1.0.pom.asc", "pom-sig");
    when(this.keyStoreService.findPublicKeySources(this.storageKey, true)).thenReturn(SOURCES);

    assertThat(this.service.recompute(this.version.getId())).isTrue();

    verify(this.pgpVerifierService).verify(jar, signature, SOURCES);
    final var saved = ArgumentCaptor.forClass(VersionSignature.class);
    verify(this.versionSignatureRepository).save(saved.capture());
    assertThat(saved.getValue().getFileName()).isEqualTo("lib-1.0.jar");
    final var order = inOrder(this.artifactVersionRepository, this.versionSignatureRepository);
    order.verify(this.artifactVersionRepository).lockForSignedUpdate(this.version.getId());
    order.verify(this.versionSignatureRepository).save(any(VersionSignature.class));
    order.verify(this.artifactVersionRepository).updateSigned(this.version.getId(), true);
  }

  @Test
  @DisplayName("a stored .asc that does not verify is not recorded and the version is not signed")
  void recomputeDoesNotCountASignatureThatDoesNotVerify() {
    this.storedVersion(true);
    this.directoryHolds("lib-1.0.pom", "lib-1.0.jar");
    this.verifiedFiles("lib-1.0.pom");
    final var jar = this.stored(VERSION_PATH + "/lib-1.0.jar", "jar");
    final var signature = this.stored(VERSION_PATH + "/lib-1.0.jar.asc", "forged");
    when(this.keyStoreService.findPublicKeySources(this.storageKey, true)).thenReturn(SOURCES);
    doThrow(new SignatureNotVerifiedException("artifactSignatureNotVerified"))
        .when(this.pgpVerifierService)
        .verify(jar, signature, SOURCES);

    assertThat(this.service.recompute(this.version.getId())).isTrue();

    verify(this.versionSignatureRepository, never()).save(any());
    verify(this.versionSignatureRepository, never())
        .deleteByArtifactVersionIdAndFileName(any(), any());
    verify(this.artifactVersionRepository).updateSigned(this.version.getId(), false);
  }

  @Test
  @DisplayName("a recorded signature that does not verify the stored bytes any more is forgotten")
  void recomputeForgetsARecordThatNoLongerVerifies() {
    this.storedVersion(true);
    this.directoryHolds("lib-1.0.pom");
    this.verifiedFiles("lib-1.0.pom");
    final var pom = this.stored(VERSION_PATH + "/lib-1.0.pom", "replaced pom");
    final var signature = this.stored(VERSION_PATH + "/lib-1.0.pom.asc", "sig of the old pom");
    when(this.keyStoreService.findPublicKeySources(this.storageKey, true)).thenReturn(SOURCES);
    doThrow(new SignatureNotVerifiedException("artifactSignatureNotVerified"))
        .when(this.pgpVerifierService)
        .verify(pom, signature, SOURCES);

    this.service.recompute(this.version.getId());

    verify(this.versionSignatureRepository)
        .deleteByArtifactVersionIdAndFileName(this.version.getId(), "lib-1.0.pom");
  }

  @Test
  @DisplayName("a recorded signature that verifies again is left as it is, without a write")
  void recomputeDoesNotRewriteARecordThatStillVerifies() {
    this.storedVersion(true);
    this.directoryHolds("lib-1.0.pom");
    this.verifiedFiles("lib-1.0.pom");
    this.stored(VERSION_PATH + "/lib-1.0.pom", "pom");
    this.stored(VERSION_PATH + "/lib-1.0.pom.asc", "sig");
    when(this.keyStoreService.findPublicKeySources(this.storageKey, true)).thenReturn(SOURCES);

    this.service.recompute(this.version.getId());

    verify(this.versionSignatureRepository, never()).save(any());
    verify(this.versionSignatureRepository, never())
        .deleteByArtifactVersionIdAndFileName(any(), any());
    verify(this.artifactVersionRepository).updateSigned(this.version.getId(), true);
  }

  @Test
  @DisplayName(
      "a signer's key that cannot be found changes no record, so an outage unsigns nothing")
  void recomputeKeepsARecordWhenTheKeyCannotBeFound() {
    this.storedVersion(true);
    this.directoryHolds("lib-1.0.pom", "lib-1.0.jar");
    this.verifiedFiles("lib-1.0.pom", "lib-1.0.jar");
    this.stored(VERSION_PATH + "/lib-1.0.pom", "pom");
    this.stored(VERSION_PATH + "/lib-1.0.pom.asc", "sig");
    this.stored(VERSION_PATH + "/lib-1.0.jar", "jar");
    this.stored(VERSION_PATH + "/lib-1.0.jar.asc", "sig");
    when(this.keyStoreService.findPublicKeySources(this.storageKey, true)).thenReturn(SOURCES);
    doThrow(new ItemNotFoundException("artifactSigningKeyNotFound"))
        .when(this.pgpVerifierService)
        .verify(any(), any(), eq(SOURCES));

    assertThat(this.service.recompute(this.version.getId())).isTrue();

    verify(this.versionSignatureRepository, never()).save(any());
    verify(this.versionSignatureRepository, never())
        .deleteByArtifactVersionIdAndFileName(any(), any());
    verify(this.artifactVersionRepository).updateSigned(this.version.getId(), true);
  }

  @Test
  @DisplayName("a file without a stored signature is not verified and the key sources are not read")
  void recomputeReadsNoKeysWhenNoSignatureIsStored() {
    this.storedVersion(true);
    this.directoryHolds("lib-1.0.pom", "lib-1.0.jar");
    this.verifiedFiles("lib-1.0.pom");

    this.service.recompute(this.version.getId());

    verifyNoInteractions(this.keyStoreService, this.pgpVerifierService);
    verify(this.artifactVersionRepository).updateSigned(this.version.getId(), false);
  }

  @Test
  @DisplayName("a repo that does not look keys up passes that on, and its own key lookup setting")
  void recomputeUsesTheKeyServerSettingOfTheRepo() {
    this.storedVersion(true).getArtifact().getRepo().setPgpKeyServerLookupEnabled(false);
    this.directoryHolds("lib-1.0.pom");
    this.verifiedFiles();
    this.stored(VERSION_PATH + "/lib-1.0.pom", "pom");
    this.stored(VERSION_PATH + "/lib-1.0.pom.asc", "sig");
    final var registeredOnly = new PublicKeySources(List.of("armored"), List.of(), false);
    when(this.keyStoreService.findPublicKeySources(this.storageKey, false))
        .thenReturn(registeredOnly);

    this.service.recompute(this.version.getId());

    verify(this.pgpVerifierService).verify(any(), any(), eq(registeredOnly));
  }

  @Test
  @DisplayName(
      "without verify-all a version with no recorded POM signature gets its stored ones verified")
  void recomputeVerifiesAStoredPomSignatureOfALegacyVersion() {
    this.storedVersion(false);
    this.directoryHolds("lib-1.0.pom", "lib-1.0.pom.asc", "lib-1.0.jar", "lib-1.0.jar.asc");
    when(this.versionSignatureRepository.findFileNamesByArtifactVersionId(this.version.getId()))
        .thenReturn(List.of(), List.of(), List.of("lib-1.0.pom"));
    final var pom = this.stored(VERSION_PATH + "/lib-1.0.pom", "pom");
    final var signature = this.stored(VERSION_PATH + "/lib-1.0.pom.asc", "sig");
    when(this.keyStoreService.findPublicKeySources(this.storageKey, true)).thenReturn(SOURCES);

    this.service.recompute(this.version.getId());

    verify(this.pgpVerifierService).verify(pom, signature, SOURCES);
    final var saved = ArgumentCaptor.forClass(VersionSignature.class);
    verify(this.versionSignatureRepository).save(saved.capture());
    assertThat(saved.getValue().getFileName()).isEqualTo("lib-1.0.pom");
    verify(this.artifactVersionRepository).updateSigned(this.version.getId(), true);
  }

  @Test
  @DisplayName(
      "without verify-all a POM signature that does not verify leaves the version unsigned")
  void recomputeDoesNotSignALegacyVersionByAForgedPomSignature() {
    this.storedVersion(false);
    this.directoryHolds("lib-1.0.pom");
    this.verifiedFiles();
    final var pom = this.stored(VERSION_PATH + "/lib-1.0.pom", "pom");
    final var signature = this.stored(VERSION_PATH + "/lib-1.0.pom.asc", "forged");
    when(this.keyStoreService.findPublicKeySources(this.storageKey, true)).thenReturn(SOURCES);
    doThrow(new SignatureNotVerifiedException("artifactSignatureNotVerified"))
        .when(this.pgpVerifierService)
        .verify(pom, signature, SOURCES);

    this.service.recompute(this.version.getId());

    verify(this.versionSignatureRepository, never()).save(any());
    verify(this.artifactVersionRepository).updateSigned(this.version.getId(), false);
  }

  @Test
  @DisplayName("without verify-all a version that has no POM signature stored is not verified")
  void recomputeOfAnUnsignedVersionWithoutVerifyAllVerifiesNothing() {
    this.storedVersion(false);
    this.directoryHolds("lib-1.0.pom", "lib-1.0.jar");
    this.verifiedFiles();

    this.service.recompute(this.version.getId());

    verifyNoInteractions(this.keyStoreService, this.pgpVerifierService);
    verify(this.artifactVersionRepository).updateSigned(this.version.getId(), false);
  }

  @Test
  @DisplayName("a snapshot without a .pom row is signed once its stored POM signatures verify")
  void recomputeVerifiesTheStoredPomSignatureOfASnapshot() {
    final var snapshotPath = "com/acme/lib/1.0-SNAPSHOT";
    this.storedVersion(false).setVersionName("1.0-SNAPSHOT");
    final var items =
        java.util.stream.Stream.of(
                "lib-1.0-20260921.101010-1.pom", "lib-1.0-20260921.101010-1.pom.asc")
            .map(
                name ->
                    StorageItemInfo.builder()
                        .name(name)
                        .path("/data/" + this.storageKey + "/" + snapshotPath + "/" + name)
                        .build())
            .toList();
    when(this.storageStrategy.listStorageItems(any(StoragePath.class))).thenReturn(items);
    when(this.versionSignatureRepository.findFileNamesByArtifactVersionId(this.version.getId()))
        .thenReturn(List.of(), List.of(), List.of("lib-1.0-20260921.101010-1.pom"));
    this.stored(snapshotPath + "/lib-1.0-20260921.101010-1.pom", "pom");
    this.stored(snapshotPath + "/lib-1.0-20260921.101010-1.pom.asc", "sig");
    when(this.keyStoreService.findPublicKeySources(this.storageKey, true)).thenReturn(SOURCES);

    this.service.recompute(this.version.getId());

    final var saved = ArgumentCaptor.forClass(VersionSignature.class);
    verify(this.versionSignatureRepository).save(saved.capture());
    assertThat(saved.getValue().getFileName()).isEqualTo("lib-1.0-20260921.101010-1.pom");
    verify(this.artifactVersionRepository).updateSigned(this.version.getId(), true);
  }
}
