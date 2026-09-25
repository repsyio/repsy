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

import io.repsy.core.error_handling.exceptions.SignatureNotVerifiedException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.PendingSignature;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.PendingSignatureRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.services.KeyStoreService;
import io.repsy.os.server.protocols.maven.shared.keystore.services.PGPVerifierService;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Holds the detached signatures ({@code .asc}) that reach a repo verifying every signature before
 * the file they sign, and checks them when that file arrives (RPS-1188). Maven uploads the files of
 * a deploy in parallel, so a signature routinely overtakes a large file it signs, or the POM that
 * registers the version.
 *
 * <p>A parked signature lives in {@code maven_pending_signature}, not in storage: until it verifies
 * it is not served and not charged, so a signature that does not verify still leaves no trace. It
 * is verified when the file it signs is stored (an upload into a registered version), or when the
 * POM registers the version (every pending signature of the version directory whose file is
 * stored). Then, and only then, it is written to its path, recorded as verified and deleted from
 * here.
 *
 * <p>Every check runs in a transaction of its own, {@code REQUIRES_NEW}, that locks the pending row
 * ({@code SELECT ... FOR UPDATE}). A signature request first commits its row and then looks again
 * for the file, and a file request looks for a row only after the file is stored, so whichever side
 * comes second sees the other's work, and the lock lets exactly one of them do it. The transaction
 * of the upload that calls in holds no lock on a pending row, so the two cannot wait for each
 * other. A signature that does not verify is deleted in that transaction, which commits before the
 * failure is thrown, so a retry starts clean; a failure to find the signer's key (a key server that
 * does not answer, a key not registered yet) keeps the row.
 *
 * <p><b>Lock order (RPS-1352).</b> Two kinds of row lock meet here: the parked row ({@code
 * maven_pending_signature}) and the version's row lock that {@link VersionSignatureService#lock}
 * takes ({@code update ... set signed = signed}). Every request that needs both takes them in one
 * order, the parked row first and the version second:
 *
 * <ul>
 *   <li>{@link #claim}, in the transaction of a signature's upload: the parked row, then {@code
 *       lockAndIsVerifyAll};
 *   <li>{@link #reconcileFile}: the parked row in a transaction of its own, then, only when the
 *       signature does not verify, the version ({@code forget}, {@code refreshSigned});
 *   <li>{@link #reconcileDirectory} and {@link #verifyDirectory}: the parked rows of the directory,
 *       ordered by path, and no version lock at all.
 * </ul>
 *
 * {@link #park}, {@link #discard} and {@link #purgeOlderThan} touch parked rows only. What could
 * break the order is a transaction that already holds the version's row and then calls in here for
 * a parked row: the checks above run in transactions of their own, {@code REQUIRES_NEW}, that the
 * caller waits for, so the database cannot see that wait and would not report a deadlock, the
 * requests would just hang. The POM's registration is that caller: it calls {@link
 * #reconcileDirectory} after it wrote the version, and stays clear only because the row it wrote
 * has not been flushed by then (the update is flushed by the first {@code lockAndIsVerifyAll} that
 * follows). {@code PendingSignatureLockOrderIT} forces both interleavings, and asserts that the
 * POM's transaction holds no version lock at that point; change the order, or write the version's
 * row earlier, and it fails.
 */
@Slf4j
@Component
@NullMarked
public class PendingSignatureService {

  /** What a check does with the signatures it finds. */
  private enum Mode {
    /** Only verify, before the POM registers the version. */
    VERIFY_ONLY,
    /** A file was stored into a registered version: verify, write, record, recompute signed. */
    FILE,
    /** The POM registered the version: verify, write and record all its signatures. */
    DIRECTORY
  }

  /**
   * What one transaction did: the failure to throw once it committed, the bytes it wrote to storage
   * and the files whose signature it recorded.
   */
  private record Outcome(
      @Nullable SignatureNotVerifiedException failure, long bytesWritten, Set<String> recorded) {

    static Outcome none() {
      return new Outcome(null, 0, Set.of());
    }
  }

  private final PendingSignatureRepository pendingSignatureRepository;
  private final ArtifactRepository artifactRepository;
  private final ArtifactVersionRepository artifactVersionRepository;
  private final RepoRepository repoRepository;
  private final VersionSignatureService versionSignatureService;
  private final PGPVerifierService pgpVerifierService;
  private final KeyStoreService keyStoreService;
  private final UsageUpdateService usageUpdateService;
  private final StorageStrategy storageStrategy;
  private final TransactionTemplate newTransaction;

  @SuppressWarnings("java:S107")
  public PendingSignatureService(
      final PendingSignatureRepository pendingSignatureRepository,
      final ArtifactRepository artifactRepository,
      final ArtifactVersionRepository artifactVersionRepository,
      final RepoRepository repoRepository,
      final VersionSignatureService versionSignatureService,
      final PGPVerifierService pgpVerifierService,
      final KeyStoreService keyStoreService,
      final UsageUpdateService usageUpdateService,
      @Qualifier("osStorageStrategyMaven") final StorageStrategy storageStrategy,
      final PlatformTransactionManager transactionManager) {

    this.pendingSignatureRepository = pendingSignatureRepository;
    this.artifactRepository = artifactRepository;
    this.artifactVersionRepository = artifactVersionRepository;
    this.repoRepository = repoRepository;
    this.versionSignatureService = versionSignatureService;
    this.pgpVerifierService = pgpVerifierService;
    this.keyStoreService = keyStoreService;
    this.usageUpdateService = usageUpdateService;
    this.storageStrategy = storageStrategy;
    this.newTransaction = new TransactionTemplate(transactionManager);
    this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * The repo-relative path parked signatures are keyed by: the path of the file as a storage path
   * spells it, without the leading slash it may carry.
   */
  public static String pathOf(final StoragePath storagePath) {

    return removeLeadingSlash(storagePath.getRelativePath().getPath());
  }

  private static String removeLeadingSlash(final String path) {

    return path.startsWith("/") ? path.substring(1) : path;
  }

  /**
   * Parks the signature of the file {@code signedFilePath}, in a transaction that commits before
   * this returns. The signature of the same file that was already parked is replaced.
   *
   * @throws SignatureNotVerifiedException {@code artifactSignatureNotVerified} when the bytes are
   *     not an OpenPGP signature (or not text: a parked signature is armored)
   */
  public void park(final UUID repoId, final String filePath, final byte[] signature) {

    final var signedFilePath = removeLeadingSlash(filePath);
    final var armored = new String(signature, UTF_8);

    if (!Arrays.equals(armored.getBytes(UTF_8), signature)) {
      throw new SignatureNotVerifiedException("artifactSignatureNotVerified");
    }

    final var keyId = this.pgpVerifierService.readSignerKeyId(new ByteArrayResource(signature));

    try {
      this.upsert(repoId, signedFilePath, armored, keyId);
    } catch (final DataIntegrityViolationException e) {
      // Two signatures of one file raced to insert it: the second one replaces the first.
      log.debug("Pending signature of {} was inserted concurrently, replacing it", signedFilePath);
      this.upsert(repoId, signedFilePath, armored, keyId);
    }
  }

  private void upsert(
      final UUID repoId, final String signedFilePath, final String armored, final String keyId) {

    this.newTransaction.executeWithoutResult(
        status -> {
          final var row =
              this.pendingSignatureRepository
                  .lockByRepoIdAndSignedFilePath(repoId, signedFilePath)
                  .orElseGet(PendingSignature::new);

          row.setRepoId(repoId);
          row.setSignedFilePath(signedFilePath);
          row.setArmoredSignature(armored);
          row.setKeyId(keyId);
          row.setCreatedAt(Instant.now());

          this.pendingSignatureRepository.saveAndFlush(row);
        });
  }

  /** Drops the parked signature of a file, in a transaction of its own. */
  public void discard(final UUID repoId, final String filePath) {

    final var signedFilePath = removeLeadingSlash(filePath);

    this.newTransaction.executeWithoutResult(
        status ->
            this.pendingSignatureRepository
                .lockByRepoIdAndSignedFilePath(repoId, signedFilePath)
                .ifPresent(this.pendingSignatureRepository::delete));
  }

  /**
   * Takes the parked signature of a file whose signature is being stored now out of the way, in the
   * transaction of the caller: it locks the row first, so a file request that is reconciling the
   * same signature finishes before this one records it.
   */
  @Transactional
  public void claim(final UUID repoId, final String filePath) {

    this.pendingSignatureRepository
        .lockByRepoIdAndSignedFilePath(repoId, removeLeadingSlash(filePath))
        .ifPresent(this.pendingSignatureRepository::delete);
  }

  /**
   * A signable file was just stored into a registered version: checks the signature parked for it,
   * if any, writes it to storage and records it. A version that is not registered yet leaves the
   * signature parked for the POM's registration.
   *
   * @return whether the signature of {@code fileRelativePath} was verified and recorded
   * @throws SignatureNotVerifiedException {@code pendingSignatureNotVerified} when the parked
   *     signature does not verify against the file; the row is gone and the file's recorded
   *     signature forgotten
   */
  public boolean reconcileFile(final BaseRepoInfo<UUID> repoInfo, final String filePath) {

    final var fileRelativePath = removeLeadingSlash(filePath);
    final var outcome =
        this.newTransaction.execute(status -> this.reconcileFileRow(repoInfo, fileRelativePath));

    return this.settle(repoInfo, outcome).contains(fileRelativePath);
  }

  private Outcome reconcileFileRow(final BaseRepoInfo<UUID> repoInfo, final String path) {

    final var row =
        this.pendingSignatureRepository
            .lockByRepoIdAndSignedFilePath(repoInfo.getStorageKey(), path)
            .orElse(null);
    final var version = row == null ? null : this.findVersion(repoInfo.getStorageKey(), path);

    if (row == null || version == null) {
      return Outcome.none();
    }

    return this.processRows(repoInfo, List.of(row), version, Mode.FILE);
  }

  /**
   * Before the POM registers a version: checks every parked signature of the version directory
   * whose file is stored, so a signature that does not verify fails the POM's upload before
   * anything is registered. Nothing is written or recorded, the signatures stay parked for {@link
   * #reconcileDirectory}.
   *
   * @throws SignatureNotVerifiedException {@code pendingSignatureNotVerified}, as {@link
   *     #reconcileFile}
   */
  public void verifyDirectory(final BaseRepoInfo<UUID> repoInfo, final String directory) {

    final var versionPath = removeLeadingSlash(directory);
    final var outcome =
        this.newTransaction.execute(
            status ->
                this.processRows(
                    repoInfo, this.lockDirectory(repoInfo, versionPath), null, Mode.VERIFY_ONLY));

    this.settle(repoInfo, outcome);
  }

  /**
   * After the POM registered the version: verifies, writes and records every parked signature of
   * the version directory whose file is stored.
   *
   * @return the repo-relative paths of the files whose signature was recorded
   * @throws SignatureNotVerifiedException {@code pendingSignatureNotVerified}, as {@link
   *     #reconcileFile}; the signatures checked before it are recorded
   */
  public Set<String> reconcileDirectory(final BaseRepoInfo<UUID> repoInfo, final String directory) {

    final var versionPath = removeLeadingSlash(directory);
    final var outcome =
        this.newTransaction.execute(status -> this.reconcileDirectoryRows(repoInfo, versionPath));

    return this.settle(repoInfo, outcome);
  }

  private Outcome reconcileDirectoryRows(
      final BaseRepoInfo<UUID> repoInfo, final String versionPath) {

    final var rows = this.lockDirectory(repoInfo, versionPath);
    final var version =
        rows.isEmpty()
            ? null
            : this.findVersion(repoInfo.getStorageKey(), rows.getFirst().getSignedFilePath());

    if (version == null) {
      return Outcome.none();
    }

    return this.processRows(repoInfo, rows, version, Mode.DIRECTORY);
  }

  private List<PendingSignature> lockDirectory(
      final BaseRepoInfo<UUID> repoInfo, final String versionPath) {

    return this.pendingSignatureRepository
        .findByRepoIdAndSignedFilePathStartingWithOrderBySignedFilePath(
            repoInfo.getStorageKey(), versionPath + "/");
  }

  /** Runs after the transaction committed: charges what it wrote, then throws what it found. */
  private Set<String> settle(final BaseRepoInfo<UUID> repoInfo, final @Nullable Outcome outcome) {

    final var result = Objects.requireNonNull(outcome);

    if (result.bytesWritten() != 0) {
      this.usageUpdateService.updateUsage(
          new UsageChangedInfo(repoInfo.getStorageKey(), BaseUsages.ofDisk(result.bytesWritten())));
    }

    if (result.failure() != null) {
      throw result.failure();
    }

    return result.recorded();
  }

  private Outcome processRows(
      final BaseRepoInfo<UUID> repoInfo,
      final List<PendingSignature> rows,
      final @Nullable ArtifactVersion version,
      final Mode mode) {

    var bytesWritten = 0L;
    final Set<String> recorded = new HashSet<>();

    for (final var row : rows) {
      final var fileResource =
          this.storageStrategy.get(
              StoragePath.of(repoInfo.getStorageKey(), row.getSignedFilePath()),
              repoInfo.getName());

      if (fileResource.isEmpty()) {
        continue;
      }

      final var failure = this.verify(repoInfo, row, fileResource.get());

      if (failure != null) {
        this.dropFailed(repoInfo, row, version, mode);

        return new Outcome(failure, bytesWritten, recorded);
      }

      if (mode != Mode.VERIFY_ONLY && version != null) {
        bytesWritten += this.materialise(repoInfo, row, version);
        recorded.add(row.getSignedFilePath());
      }
    }

    return new Outcome(null, bytesWritten, recorded);
  }

  /**
   * Verifies a parked signature against its stored file.
   *
   * @return {@code null} when it verifies, the failure to report when it does not; a key that
   *     cannot be found is thrown, which rolls the transaction back and keeps the row
   */
  private @Nullable SignatureNotVerifiedException verify(
      final BaseRepoInfo<UUID> repoInfo, final PendingSignature row, final Resource file) {

    final var sources =
        this.keyStoreService.findPublicKeySources(
            repoInfo.getStorageKey(), repoInfo.isPgpKeyServerLookupEnabled());

    try {
      this.pgpVerifierService.verify(
          file, new ByteArrayResource(row.getArmoredSignature().getBytes(UTF_8)), sources);

      return null;
    } catch (final SignatureNotVerifiedException e) {
      log.warn(
          "the signature parked for {} in repo {} does not verify: {}",
          row.getSignedFilePath(),
          repoInfo.getName(),
          e.getMessage());

      return new SignatureNotVerifiedException("pendingSignatureNotVerified");
    }
  }

  /**
   * A parked signature that does not verify is dropped, and the recorded signature of the file it
   * signed (of its previous bytes) is forgotten. After a file upload the version is not signed any
   * more, either.
   */
  private void dropFailed(
      final BaseRepoInfo<UUID> repoInfo,
      final PendingSignature row,
      final @Nullable ArtifactVersion version,
      final Mode mode) {

    this.pendingSignatureRepository.delete(row);

    if (version == null) {
      return;
    }

    this.versionSignatureService.forget(version, fileNameOf(row.getSignedFilePath()));

    if (mode == Mode.FILE) {
      this.versionSignatureService.refreshSigned(
          repoInfo.getStorageKey(), version, parentOf(row.getSignedFilePath()));
    }
  }

  /**
   * Writes a verified signature to its path, records it and drops the parked row. Storage comes
   * first, as everywhere: a failure after the write leaves the row, and the next check writes the
   * same bytes again.
   *
   * @return the bytes it added to storage
   */
  private long materialise(
      final BaseRepoInfo<UUID> repoInfo,
      final PendingSignature row,
      final ArtifactVersion version) {

    final var signaturePath =
        StoragePath.of(repoInfo.getStorageKey(), row.getSignedFilePath() + ".asc");

    final var usages =
        this.storageStrategy.write(
            repoInfo.getName(),
            signaturePath,
            new ByteArrayInputStream(row.getArmoredSignature().getBytes(UTF_8)));

    this.versionSignatureService.recordVerified(version, fileNameOf(row.getSignedFilePath()));
    this.pendingSignatureRepository.delete(row);

    return usages.getDiskUsage();
  }

  /** The registered version a file belongs to, or {@code null} when it is not registered. */
  private @Nullable ArtifactVersion findVersion(final UUID repoId, final String filePath) {

    final var gav = ArtifactUtils.convertPathToGav(filePath);

    if (gav == null) {
      return null;
    }

    final var artifact =
        this.artifactRepository
            .findByRepoIdAndGroupNameAndArtifactName(repoId, gav.getGroupId(), gav.getArtifactId())
            .orElse(null);

    if (artifact == null) {
      return null;
    }

    final var versionName = gav.isSnapshot() ? gav.getBaseVersion() : gav.getVersion();

    return this.artifactVersionRepository
        .findByArtifactIdAndVersionName(artifact.getId(), versionName)
        .orElse(null);
  }

  private static String fileNameOf(final String path) {

    return path.substring(path.lastIndexOf('/') + 1);
  }

  private static String parentOf(final String path) {

    return path.substring(0, path.lastIndexOf('/'));
  }

  /**
   * Deletes the signatures that were parked longer than {@code ttl} ago. A deploy takes minutes;
   * what is older belongs to a file that never came.
   *
   * @return how many signatures were deleted
   */
  @Transactional
  public int purgeOlderThan(final Duration ttl) {

    final var expired =
        this.pendingSignatureRepository.findByCreatedAtBefore(Instant.now().minus(ttl));

    if (expired.isEmpty()) {
      return 0;
    }

    final Map<UUID, Long> perRepo =
        expired.stream()
            .collect(Collectors.groupingBy(PendingSignature::getRepoId, Collectors.counting()));
    final var names =
        this.repoRepository.findAllById(perRepo.keySet()).stream()
            .collect(Collectors.toMap(repo -> repo.getId(), repo -> repo.getName()));

    log.info(
        "Purging {} pending Maven signature(s) older than {}: {}",
        expired.size(),
        ttl,
        perRepo.entrySet().stream()
            .map(
                entry ->
                    names.getOrDefault(entry.getKey(), entry.getKey().toString())
                        + "="
                        + entry.getValue())
            .collect(Collectors.joining(", ")));

    this.pendingSignatureRepository.deleteAll(expired);

    return expired.size();
  }
}
