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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.SignatureNotVerifiedException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.PendingSignature;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.PendingSignatureRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.dtos.PublicKeySources;
import io.repsy.os.server.protocols.maven.shared.keystore.services.KeyStoreService;
import io.repsy.os.server.protocols.maven.shared.keystore.services.PGPVerifierService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * A signature that reaches a repo verifying every signature before its file is parked, and is
 * checked when the file arrives (RPS-1188).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PendingSignatureService (RPS-1188)")
class PendingSignatureServiceTest {

  private static final String FILE = "com/acme/lib/1.0/lib-1.0-javadoc.jar";
  private static final String ARMORED =
      "-----BEGIN PGP SIGNATURE-----\n\nabc\n-----END PGP SIGNATURE-----\n";

  @Mock PendingSignatureRepository pendingSignatureRepository;
  @Mock ArtifactRepository artifactRepository;
  @Mock ArtifactVersionRepository artifactVersionRepository;
  @Mock RepoRepository repoRepository;
  @Mock VersionSignatureService versionSignatureService;
  @Mock PGPVerifierService pgpVerifierService;
  @Mock KeyStoreService keyStoreService;
  @Mock UsageUpdateService usageUpdateService;
  @Mock StorageStrategy storageStrategy;
  @Mock PlatformTransactionManager transactionManager;

  private PendingSignatureService service;
  private final UUID repoId = UUID.randomUUID();
  private final BaseRepoInfo<UUID> repoInfo =
      BaseRepoInfo.<UUID>builder().id(this.repoId).storageKey(this.repoId).name("mvn").build();
  private final ArtifactVersion version = new ArtifactVersion();
  private final Resource storedFile = new ByteArrayResource("javadoc".getBytes(UTF_8));

  @BeforeEach
  void setUp() {
    lenient()
        .when(this.transactionManager.getTransaction(any()))
        .thenReturn(new SimpleTransactionStatus());
    this.service =
        new PendingSignatureService(
            this.pendingSignatureRepository,
            this.artifactRepository,
            this.artifactVersionRepository,
            this.repoRepository,
            this.versionSignatureService,
            this.pgpVerifierService,
            this.keyStoreService,
            this.usageUpdateService,
            this.storageStrategy,
            this.transactionManager);
    this.version.setId(UUID.randomUUID());
  }

  private PendingSignature row(final String path) {
    final var row = new PendingSignature();
    row.setId(UUID.randomUUID());
    row.setRepoId(this.repoId);
    row.setSignedFilePath(path);
    row.setArmoredSignature(ARMORED);
    row.setKeyId("0123456789ABCDEF");
    row.setCreatedAt(Instant.now());
    return row;
  }

  private void rowIsParked(final PendingSignature row) {
    when(this.pendingSignatureRepository.lockByRepoIdAndSignedFilePath(
            this.repoId, row.getSignedFilePath()))
        .thenReturn(Optional.of(row));
  }

  private void versionIsRegistered() {
    final var artifact = new Artifact();
    artifact.setId(UUID.randomUUID());
    when(this.artifactRepository.findByRepoIdAndGroupNameAndArtifactName(
            this.repoId, "com.acme", "lib"))
        .thenReturn(Optional.of(artifact));
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.of(this.version));
  }

  private void fileIsStored(final String path) {
    when(this.storageStrategy.get(any(StoragePath.class), eq("mvn")))
        .thenAnswer(
            invocation ->
                invocation.<StoragePath>getArgument(0).getRelativePath().getPath().equals(path)
                    ? Optional.of(this.storedFile)
                    : Optional.empty());
    lenient()
        .when(this.keyStoreService.findPublicKeySources(this.repoId, true))
        .thenReturn(PublicKeySources.none());
  }

  private void writeReports(final long bytes) {
    when(this.storageStrategy.write(eq("mvn"), any(StoragePath.class), any(InputStream.class)))
        .thenReturn(BaseUsages.ofDisk(bytes));
  }

  @Test
  @DisplayName("parks the signature of a file: a new row, or the row of the same file replaced")
  void parkUpsertsTheRow() {
    when(this.pgpVerifierService.readSignerKeyId(any())).thenReturn("0123456789ABCDEF");

    this.service.park(this.repoId, FILE, ARMORED.getBytes(UTF_8));

    final var saved = ArgumentCaptor.forClass(PendingSignature.class);
    verify(this.pendingSignatureRepository).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getRepoId()).isEqualTo(this.repoId);
    assertThat(saved.getValue().getSignedFilePath()).isEqualTo(FILE);
    assertThat(saved.getValue().getArmoredSignature()).isEqualTo(ARMORED);
    assertThat(saved.getValue().getKeyId()).isEqualTo("0123456789ABCDEF");
    assertThat(saved.getValue().getCreatedAt()).isNotNull();
  }

  @Test
  @DisplayName("a second signature of the file replaces the parked one and restarts its clock")
  void parkReplacesTheRowOfTheSameFile() {
    final var existing = this.row(FILE);
    existing.setCreatedAt(Instant.parse("2020-01-01T00:00:00Z"));
    this.rowIsParked(existing);
    when(this.pgpVerifierService.readSignerKeyId(any())).thenReturn("FEDCBA9876543210");

    this.service.park(this.repoId, FILE, ARMORED.getBytes(UTF_8));

    verify(this.pendingSignatureRepository).saveAndFlush(existing);
    assertThat(existing.getKeyId()).isEqualTo("FEDCBA9876543210");
    assertThat(existing.getCreatedAt()).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
  }

  @Test
  @DisplayName("refuses to park what is not a signature, or not text, and stores nothing")
  void parkRefusesWhatIsNotASignature() {
    when(this.pgpVerifierService.readSignerKeyId(any()))
        .thenThrow(new SignatureNotVerifiedException("artifactSignatureNotVerified"));

    assertThatThrownBy(() -> this.service.park(this.repoId, FILE, "garbage".getBytes(UTF_8)))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage("artifactSignatureNotVerified");
    assertThatThrownBy(() -> this.service.park(this.repoId, FILE, new byte[] {(byte) 0xC3, 0x28}))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage("artifactSignatureNotVerified");

    verifyNoInteractions(this.pendingSignatureRepository);
  }

  @Test
  @DisplayName("a file with nothing parked for it changes nothing")
  void reconcileFileWithoutARowDoesNothing() {
    when(this.pendingSignatureRepository.lockByRepoIdAndSignedFilePath(this.repoId, FILE))
        .thenReturn(Optional.empty());

    assertThat(this.service.reconcileFile(this.repoInfo, FILE)).isFalse();

    verifyNoInteractions(this.storageStrategy, this.pgpVerifierService, this.usageUpdateService);
  }

  @Test
  @DisplayName("a file of a version that is not registered yet leaves the signature parked")
  void reconcileFileOfAnUnregisteredVersionKeepsTheRow() {
    this.rowIsParked(this.row(FILE));
    when(this.artifactRepository.findByRepoIdAndGroupNameAndArtifactName(any(), any(), any()))
        .thenReturn(Optional.empty());

    assertThat(this.service.reconcileFile(this.repoInfo, FILE)).isFalse();

    verify(this.pendingSignatureRepository, never()).delete(any());
    verifyNoInteractions(this.storageStrategy, this.pgpVerifierService);
  }

  @Test
  @DisplayName("verifies the parked signature, writes it, records it, drops the row and charges it")
  void reconcileFileMaterialisesAVerifiedSignature() {
    final var row = this.row(FILE);
    this.rowIsParked(row);
    this.versionIsRegistered();
    this.fileIsStored(FILE);
    this.writeReports(ARMORED.length());

    assertThat(this.service.reconcileFile(this.repoInfo, FILE)).isTrue();

    verify(this.pgpVerifierService)
        .verify(eq(this.storedFile), any(ByteArrayResource.class), any(PublicKeySources.class));
    final var target = ArgumentCaptor.forClass(StoragePath.class);
    verify(this.storageStrategy).write(eq("mvn"), target.capture(), any(InputStream.class));
    assertThat(target.getValue().getRelativePath().getPath()).isEqualTo(FILE + ".asc");
    verify(this.versionSignatureService).recordVerified(this.version, "lib-1.0-javadoc.jar");
    verify(this.pendingSignatureRepository).delete(row);
    verify(this.usageUpdateService)
        .updateUsage(new UsageChangedInfo(this.repoId, BaseUsages.ofDisk(ARMORED.length())));
  }

  @Test
  @DisplayName("a parked signature that does not verify fails the file, and nothing is written")
  void reconcileFileRefusesABadSignature() {
    final var row = this.row(FILE);
    this.rowIsParked(row);
    this.versionIsRegistered();
    this.fileIsStored(FILE);
    doThrow(new SignatureNotVerifiedException("artifactSignatureNotVerified"))
        .when(this.pgpVerifierService)
        .verify(any(), any(), any());

    assertThatThrownBy(() -> this.service.reconcileFile(this.repoInfo, FILE))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage("pendingSignatureNotVerified");

    verify(this.pendingSignatureRepository).delete(row);
    verify(this.versionSignatureService).forget(this.version, "lib-1.0-javadoc.jar");
    verify(this.versionSignatureService)
        .refreshSigned(this.repoId, this.version, "com/acme/lib/1.0");
    verify(this.storageStrategy, never()).write(anyString(), any(), any());
    verify(this.versionSignatureService, never()).recordVerified(any(), anyString());
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("a key that cannot be found is not a bad signature: the row is kept")
  void reconcileFileKeepsTheRowWhenTheKeyCannotBeFound() {
    final var row = this.row(FILE);
    this.rowIsParked(row);
    this.versionIsRegistered();
    this.fileIsStored(FILE);
    doThrow(new ItemNotFoundException("artifactSigningKeyNotFound"))
        .when(this.pgpVerifierService)
        .verify(any(), any(), any());

    assertThatThrownBy(() -> this.service.reconcileFile(this.repoInfo, FILE))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("artifactSigningKeyNotFound");

    verify(this.pendingSignatureRepository, never()).delete(any());
    verify(this.storageStrategy, never()).write(anyString(), any(), any());
  }

  @Test
  @DisplayName("a signature whose file is not stored stays parked")
  void reconcileFileWhoseFileIsNotStoredKeepsTheRow() {
    this.rowIsParked(this.row(FILE));
    this.versionIsRegistered();
    this.fileIsStored("com/acme/lib/1.0/other.jar");

    assertThat(this.service.reconcileFile(this.repoInfo, FILE)).isFalse();

    verify(this.pendingSignatureRepository, never()).delete(any());
    verifyNoInteractions(this.pgpVerifierService);
  }

  @Test
  @DisplayName("before the POM registers the version a bad signature fails it, a good one waits")
  void verifyDirectoryOnlyChecks() {
    final var good = this.row("com/acme/lib/1.0/lib-1.0.jar");
    this.fileIsStored("com/acme/lib/1.0/lib-1.0.jar");
    when(this.pendingSignatureRepository
            .findByRepoIdAndSignedFilePathStartingWithOrderBySignedFilePath(
                this.repoId, "com/acme/lib/1.0/"))
        .thenReturn(List.of(good));

    this.service.verifyDirectory(this.repoInfo, "com/acme/lib/1.0");

    verify(this.pendingSignatureRepository, never()).delete(any());
    verifyNoInteractions(this.usageUpdateService);
    verify(this.storageStrategy, never()).write(anyString(), any(), any());

    doThrow(new SignatureNotVerifiedException("artifactSignatureNotVerified"))
        .when(this.pgpVerifierService)
        .verify(any(), any(), any());

    assertThatThrownBy(() -> this.service.verifyDirectory(this.repoInfo, "com/acme/lib/1.0"))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage("pendingSignatureNotVerified");
    verify(this.pendingSignatureRepository).delete(good);
    verify(this.storageStrategy, never()).write(anyString(), any(), any());
  }

  @Test
  @DisplayName(
      "after the POM registered the version every parked signature of a stored file is recorded")
  void reconcileDirectoryRecordsTheSignaturesOfTheStoredFiles() {
    final var jar = this.row("com/acme/lib/1.0/lib-1.0.jar");
    final var sources = this.row("com/acme/lib/1.0/lib-1.0-sources.jar");
    this.versionIsRegistered();
    this.fileIsStored("com/acme/lib/1.0/lib-1.0.jar");
    this.writeReports(10);
    when(this.pendingSignatureRepository
            .findByRepoIdAndSignedFilePathStartingWithOrderBySignedFilePath(
                this.repoId, "com/acme/lib/1.0/"))
        .thenReturn(List.of(jar, sources));

    final var recorded = this.service.reconcileDirectory(this.repoInfo, "com/acme/lib/1.0");

    assertThat(recorded).containsExactly("com/acme/lib/1.0/lib-1.0.jar");
    verify(this.pendingSignatureRepository).delete(jar);
    verify(this.pendingSignatureRepository, never()).delete(sources);
    verify(this.usageUpdateService)
        .updateUsage(new UsageChangedInfo(this.repoId, BaseUsages.ofDisk(10)));
  }

  @Test
  @DisplayName("purges what is older than the ttl and reports how many")
  void purgeDeletesTheExpiredRows() {
    final var old = this.row(FILE);
    final var repo = new Repo();
    repo.setId(this.repoId);
    repo.setName("mvn");
    when(this.pendingSignatureRepository.findByCreatedAtBefore(any(Instant.class)))
        .thenReturn(List.of(old));
    when(this.repoRepository.findAllById(any())).thenReturn(List.of(repo));

    final var before = Instant.now().minus(Duration.ofHours(24));
    assertThat(this.service.purgeOlderThan(Duration.ofHours(24))).isEqualTo(1);

    final var cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(this.pendingSignatureRepository).findByCreatedAtBefore(cutoff.capture());
    assertThat(cutoff.getValue())
        .isBetween(before, Instant.now().minus(Duration.ofHours(24)).plusSeconds(1));
    verify(this.pendingSignatureRepository).deleteAll(List.of(old));
  }

  @Test
  @DisplayName("purges nothing when nothing is old")
  void purgeWithNothingExpiredDeletesNothing() {
    when(this.pendingSignatureRepository.findByCreatedAtBefore(any(Instant.class)))
        .thenReturn(List.of());

    assertThat(this.service.purgeOlderThan(Duration.ofHours(24))).isZero();

    verify(this.pendingSignatureRepository, never()).deleteAll(any());
  }
}
