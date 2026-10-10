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

import com.google.common.base.Suppliers;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.SignatureNotVerifiedException;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.VersionSignature;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionSignatureRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.services.KeyStoreService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.maven.shared.artifact.dtos.SignatureOutcome;
import io.repsy.protocols.maven.shared.keystore.dtos.PublicKeySources;
import io.repsy.protocols.maven.shared.keystore.services.PgpVerifierService;
import io.repsy.protocols.maven.shared.utils.MavenFileNameUtils;
import io.repsy.protocols.maven.shared.utils.MavenGavUtils;
import io.repsy.protocols.maven.shared.utils.SignatureFileUtils;
import io.repsy.protocols.maven.shared.utils.SnapshotNameUtils;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import io.repsy.protocols.shared.storage.StorageStrategyRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps track of which files of a Maven version have a verified detached signature, and derives the
 * version's {@code signed} flag from it on a repo that verifies every signature (RPS-1188). A
 * version is then signed when it has files to sign and every one of them has a verified signature;
 * for a snapshot only the files of its newest build are the ones to sign ({@link
 * SnapshotNameUtils#filesToSign}).
 *
 * <p>It also holds the signature flow of an upload and of a request for a signature (RPS-2064):
 * recording the verified signature of a stored file, recomputing {@code signed} when a signable
 * file is stored, verifying a signature before it is stored or parking it until its file and
 * version are there. It joins the transaction of the upload it is called from, except for {@link
 * #verifySignature} and {@link #getNonSignedStoragePath}, which run read only as they did on the
 * artifact service.
 */
@Slf4j
@Component
@Transactional
@RequiredArgsConstructor
@NullMarked
public class ArtifactSignatureService {

  private static final String SIGNATURE_SUFFIX = ".asc";

  private final VersionSignatureRepository versionSignatureRepository;
  private final ArtifactVersionRepository artifactVersionRepository;

  private final StorageStrategyRegistry storageStrategyRegistry;

  private final KeyStoreService keyStoreService;
  private final PgpVerifierService pgpVerifierService;

  private final ArtifactQueryService artifactQueryService;
  private final PendingSignatureService pendingSignatureService;

  /** Records that the signature of {@code signedFileName} verified now (an upsert). */
  public void recordVerified(final ArtifactVersion version, final String signedFileName) {

    final var signature =
        this.versionSignatureRepository
            .findByArtifactVersionIdAndFileName(version.getId(), signedFileName)
            .orElseGet(
                () -> {
                  final var created = new VersionSignature();
                  created.setArtifactVersion(version);
                  created.setFileName(signedFileName);
                  return created;
                });

    signature.setVerifiedAt(Instant.now());

    this.versionSignatureRepository.save(signature);
  }

  /**
   * Takes the version's row lock, which every request that changes what the version's signed state
   * is computed from holds from its recording of a signature to its commit. What is read after it
   * has been taken is what all the requests before it committed (RPS-1188, RPS-1320).
   */
  public void lock(final ArtifactVersion version) {

    this.artifactVersionRepository.lockForSignedUpdate(version.getId());
  }

  /**
   * Takes the version's row lock and then reads the repo's {@code pgpVerifyAllSignaturesEnabled}
   * setting as it is committed at that moment. A request that decides what to do from the setting
   * must do it from this answer and not from the one it read when it started: a toggle may have
   * committed since, and a stale answer would write a {@code signed} that the recomputation of that
   * toggle has already replaced. A toggle that commits after this read starts its recomputation
   * only then, and that takes the lock after this request has released it (RPS-1323).
   */
  public boolean lockAndIsVerifyAll(final ArtifactVersion version) {

    this.lock(version);

    return this.artifactVersionRepository
        .findVerifyAllSignaturesEnabledByVersionId(version.getId())
        .orElse(false);
  }

  /** Whether a verified signature is recorded for the file. */
  @Transactional(readOnly = true)
  public boolean isRecorded(final ArtifactVersion version, final String fileName) {

    return this.versionSignatureRepository
        .findByArtifactVersionIdAndFileName(version.getId(), fileName)
        .isPresent();
  }

  /** The id of the verified signature recorded for the file, if there is one right now. */
  @Transactional(readOnly = true)
  public Optional<UUID> findRecordedId(final ArtifactVersion version, final String fileName) {

    return this.versionSignatureRepository
        .findByArtifactVersionIdAndFileName(version.getId(), fileName)
        .map(VersionSignature::getId);
  }

  /**
   * Forgets exactly the record that was read, never a newer one for the same file (RPS-1984): a
   * signature request can record the file's signature between the moment the file's own request
   * read the record and the moment it deletes it, and deleting by file name would take that fresh
   * record with it and leave the version unsigned for good.
   */
  public void forget(final UUID signatureId) {

    this.versionSignatureRepository.deleteById(signatureId);
  }

  /** Forgets the verified signature of a file that was stored again: its bytes are new. */
  public void forget(final ArtifactVersion version, final String fileName) {

    this.versionSignatureRepository.deleteByArtifactVersionIdAndFileName(version.getId(), fileName);
  }

  /**
   * Sets {@code version.signed} from what the version directory holds now and what has a verified
   * signature, the way a repo that verifies every signature does.
   *
   * <p>The version's row is locked first and the two are read after that, so requests that change
   * them at the same time (a deploy uploads its files and signatures in parallel) are recomputed
   * one after the other, and the last one sees what all the others recorded: two computed from a
   * state that lacked each other's signature would both answer {@code false} (RPS-1188).
   *
   * @param storageKey the repo's storage key
   * @param versionPath the version directory, {@code <group>/<artifactId>/<version>}
   */
  public void refreshSigned(
      final UUID storageKey, final ArtifactVersion version, final String versionPath) {

    this.refreshSigned(storageKey, version, versionPath, true);
  }

  /**
   * Sets {@code version.signed} from what has a verified signature, by the rule of the repo's
   * setting: with {@code verifyAll} the rule of {@link #refreshSigned(UUID, ArtifactVersion,
   * String)}; without it the version is signed when the signature of its POM is verified, the only
   * signature such a repo verifies ({@link #isSignedByPom}). It is what a toggle of the setting
   * recomputes an existing version by (RPS-1316), so a version ends up as if it had been uploaded
   * under the setting it has now.
   */
  public void refreshSigned(
      final UUID storageKey,
      final ArtifactVersion version,
      final String versionPath,
      final boolean verifyAll) {

    this.lock(version);

    // The directory is listed before the rows are read, as it always was: both come after the lock.
    final var toSign = verifyAll ? this.filesToSign(storageKey, versionPath) : List.<String>of();

    this.updateSigned(version, toSign, verifyAll);
  }

  private void updateSigned(
      final ArtifactVersion version, final List<String> toSign, final boolean verifyAll) {

    final var verified =
        this.versionSignatureRepository.findFileNamesByArtifactVersionId(version.getId());

    final var signed = verifyAll ? isSigned(toSign, verified) : isSignedByPom(verified);

    // A bulk update, so the entity is not marked dirty and flushed again with all its columns.
    this.artifactVersionRepository.updateSigned(version.getId(), signed);
  }

  /**
   * Recomputes {@code signed} of one version of a repo by the repo's setting as it is now, in a
   * transaction of its own when it is not called from one.
   *
   * <p>The signatures that are stored but were never verified are verified first (see {@link
   * #verifyStoredSignatures}), by the key rules of the upload path (the repo's registered keys,
   * then its key servers if it looks them up): a repo that verifies every signature counts a {@code
   * .asc} that was stored while it did not, so an honest publisher's version is not turned unsigned
   * by the toggle alone (RPS-1323). A signature that does not verify, or whose key is not found, is
   * not recorded and counts for nothing. What was recorded for a file and does not verify against
   * the stored bytes any more (they were replaced while the setting was off) is forgotten; when the
   * key cannot be found the record is left as it is, so an outage of a key server cannot unsign a
   * version.
   *
   * <p><b>This verification runs before the version's row lock is taken (RPS-1469).</b> Looking a
   * key up that is not registered asks a key server, one file at a time; a key server that is slow
   * or unreachable can turn what should be a lock held for a few milliseconds into one held for
   * seconds, blocking a concurrent upload's {@link #lock} of the same version for as long. The row
   * lock only needs to be held for the final, local read-modify-write of {@code signed} (below),
   * not for the network calls that decide what gets recorded. The verification is best effort
   * against the setting and the files as they are read here: the lock taken afterwards re-reads
   * both fresh, so a toggle that commits while the network calls are in flight cannot leave the
   * version computed by a setting it no longer has (RPS-1188, RPS-1320) &mdash; it only means this
   * run verified with the rule it started with, and a run that finds the setting changed under it
   * is not the last word: {@link SignedRecomputeService} queues another one behind it whenever a
   * toggle or a key change commits while a run is going.
   *
   * <p>That is also why deleting a registered key or a key-server host does not unsign the versions
   * it verified (RPS-1334): the recomputation that follows the deletion finds the key gone, which
   * is the same as not finding it, and leaves the record. Only what does not verify against a key
   * that is found is forgotten. A registered key that is added makes the versions signed by it
   * signed, by the same recomputation. What a revocation should do is a separate product question.
   *
   * <p>Without the setting only the POM's signature counts, and a version that has none recorded
   * has the stored {@code .pom.asc} files verified (a snapshot signed before RPS-1188 has no row,
   * V0023 backfilled the releases only).
   *
   * @return {@code false} when the version is gone
   */
  public boolean recompute(final UUID versionId) {

    final var version = this.artifactVersionRepository.findById(versionId).orElse(null);

    if (version == null) {
      return false;
    }

    final var artifact = version.getArtifact();
    final var repo = artifact.getRepo();
    final var versionPath =
        artifact.getGroupName().replace('.', '/')
            + "/"
            + artifact.getArtifactName()
            + "/"
            + version.getVersionName();

    // Best effort, no row lock held: may include network calls to a key server (RPS-1469).
    if (repo.isPgpVerifyAllSignaturesEnabled()) {
      final var toSign = this.filesToSign(repo.getId(), versionPath);

      this.verifyStoredSignatures(repo, version, versionPath, toSign);
    } else {
      this.verifyStoredPomSignatures(repo, version, versionPath);
    }

    // The lock, and the setting that decides what is written, are taken and read fresh here: only
    // local reads and writes happen under the lock, so a concurrent upload's lock of this version
    // waits for this, not for whatever the verification above needed to do over the network.
    this.artifactVersionRepository.lockForSignedUpdate(versionId);

    final var verifyAllNow =
        this.artifactVersionRepository
            .findVerifyAllSignaturesEnabledByVersionId(versionId)
            .orElse(null);

    if (verifyAllNow == null) {
      // The version was deleted while the verification above was running.
      return false;
    }

    final var toSignNow =
        verifyAllNow ? this.filesToSign(repo.getId(), versionPath) : List.<String>of();

    this.updateSigned(version, toSignNow, verifyAllNow);

    return true;
  }

  /** Legacy: a version whose POM signature is not recorded gets its stored ones verified. */
  private void verifyStoredPomSignatures(
      final Repo repo, final ArtifactVersion version, final String versionPath) {

    final var recorded =
        this.versionSignatureRepository.findFileNamesByArtifactVersionId(version.getId());

    if (isSignedByPom(recorded)) {
      return;
    }

    final var items =
        this.mavenStorage().listStorageItems(StoragePath.of(repo.getId(), versionPath));
    final var poms =
        SnapshotNameUtils.versionDirFileNames(versionPath, items).stream()
            .filter(MavenFileNameUtils::isPomFile)
            .toList();

    this.verifyStoredSignatures(repo, version, versionPath, poms);
  }

  /**
   * Verifies the stored {@code .asc} of each of the files that has one, against the file as stored,
   * and records the ones that verify (see {@link #recompute}).
   */
  private void verifyStoredSignatures(
      final Repo repo,
      final ArtifactVersion version,
      final String versionPath,
      final Collection<String> fileNames) {

    final var recorded =
        new HashSet<>(
            this.versionSignatureRepository.findFileNamesByArtifactVersionId(version.getId()));
    // Read once, and only when there is a signature to verify.
    final Supplier<PublicKeySources> sources =
        Suppliers.memoize(
            () ->
                this.keyStoreService.getPublicKeySources(
                    repo.getId(), repo.isPgpKeyServerLookupEnabled()));

    for (final var fileName : fileNames) {
      final var outcome = this.verifyStored(repo, versionPath, fileName, sources);

      if (outcome == StoredOutcome.VERIFIED && recorded.add(fileName)) {
        this.recordVerified(version, fileName);
      } else if (outcome == StoredOutcome.NOT_VERIFIED && recorded.remove(fileName)) {
        this.forget(version, fileName);
      }
    }
  }

  /** What the stored signature of a file amounts to. */
  private enum StoredOutcome {
    /** The file or its signature is not stored. */
    NOTHING_STORED,
    VERIFIED,
    /** It is stored and does not verify. */
    NOT_VERIFIED,
    /** It is stored, and could not be verified: the signer's key is not to be found. */
    UNKNOWN
  }

  private StoredOutcome verifyStored(
      final Repo repo,
      final String versionPath,
      final String fileName,
      final Supplier<PublicKeySources> sources) {

    final var filePath = versionPath + "/" + fileName;
    final var signature =
        this.mavenStorage()
            .get(StoragePath.of(repo.getId(), filePath + SIGNATURE_SUFFIX), repo.getName());
    final var file =
        this.mavenStorage().get(StoragePath.of(repo.getId(), filePath), repo.getName());

    if (signature.isEmpty() || file.isEmpty()) {
      return StoredOutcome.NOTHING_STORED;
    }

    try {
      this.pgpVerifierService.verify(file.get(), signature.get(), sources.get());

      return StoredOutcome.VERIFIED;
    } catch (final SignatureNotVerifiedException e) {
      log.info(
          "The stored signature of {} in Maven repo {} does not verify: {}",
          filePath,
          repo.getName(),
          e.getMessage());

      return StoredOutcome.NOT_VERIFIED;
    } catch (final ItemNotFoundException e) {
      log.info(
          "The stored signature of {} in Maven repo {} could not be verified: {}",
          filePath,
          repo.getName(),
          e.getMessage());

      return StoredOutcome.UNKNOWN;
    }
  }

  private List<String> filesToSign(final UUID storageKey, final String versionPath) {

    final var items = this.mavenStorage().listStorageItems(StoragePath.of(storageKey, versionPath));

    return SnapshotNameUtils.filesToSign(
        versionPath, SnapshotNameUtils.versionDirFileNames(versionPath, items));
  }

  /**
   * Whether the POM of the version has a verified signature: what {@code signed} means on a repo
   * that verifies only the POM's (a snapshot has one POM per build, any of them counts).
   */
  static boolean isSignedByPom(final Collection<String> verified) {

    return verified.stream().anyMatch(MavenFileNameUtils::isPomFile);
  }

  /** Whether there is something to sign and every file of it has a verified signature. */
  static boolean isSigned(final Collection<String> filesToSign, final Collection<String> verified) {

    return !filesToSign.isEmpty() && verified.containsAll(filesToSign);
  }

  /**
   * A signable file was stored into a repo that verifies every signature (RPS-1188). The signature
   * that arrived before it, if any, is checked now and recorded ({@link
   * PendingSignatureService#reconcileFile}); otherwise its bytes are new, so the verified signature
   * of the previous ones is forgotten. Whether the version is signed is recomputed either way. A
   * file of a version that is not registered yet (a jar before its POM) is left to the POM's
   * registration.
   *
   * <p>Known window, accepted (RPS-1335): whether the repo verifies every signature is taken from
   * {@code repoInfo}, which is from the start of the request, and a repo for which it says no
   * returns before any lock or query. A toggle-on that commits while such a file is being uploaded
   * starts its recomputation, and if that has already passed the file's version, the version keeps
   * a {@code signed} that was computed without the new file until the next upload into it or the
   * next toggle. It is bounded (one version per upload that raced the toggle), heals itself, and
   * needs an upload and a toggle within the same moment. Closing it needs the setting read for
   * every signable file of a flag-off repo, which is the query per file that RPS-1179 removed; a
   * plain {@code mvn deploy} uploads many of them.
   */
  public void refreshSignedForFile(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath storagePath) {

    if (!this.isSignableInVerifyAllRepo(repoInfo, storagePath)) {
      return;
    }

    final var recorded =
        this.pendingSignatureService.reconcileFile(
            repoInfo, PendingSignatureService.pathOf(storagePath));

    this.updateSignedForFile(repoInfo, storagePath, recorded);
  }

  /**
   * From the setting as {@code repoInfo} has it, so a request that began before a toggle is decided
   * by the old value. When that says on, {@link #updateSignedForFile} reads the setting again under
   * the version's lock; when it says off nothing is read, and that is the accepted window of {@link
   * #refreshSignedForFile} (RPS-1335).
   */
  private boolean isSignableInVerifyAllRepo(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath storagePath) {

    return repoInfo.isPgpVerifyAllSignaturesEnabled()
        && SignatureFileUtils.isSignableFile(storagePath.getRelativePath().getFileName());
  }

  /**
   * Forgets the verified signature of a signable file that was stored again, unless it was just
   * recorded for the new bytes, and recomputes whether the version is signed.
   */
  public void updateSignedForFile(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath storagePath, final boolean recorded) {

    if (!this.isSignableInVerifyAllRepo(repoInfo, storagePath)) {
      return;
    }

    final var relativePath = storagePath.getRelativePath();
    final var gav = MavenGavUtils.convertPathToGav(relativePath.getPath());
    final var version =
        gav == null
            ? null
            : this.artifactQueryService
                .findRegisteredVersion(repoInfo.getStorageKey(), gav)
                .orElse(null);

    if (version == null) {
      return;
    }

    // Locked first: a signature request that recorded this file's signature holds the lock until it
    // commits, so the record is either visible from here on or comes after the recomputation below
    // (RPS-1320). The setting is read after the lock and not taken from repoInfo, which is from the
    // start of the request: a toggle that committed since has its recomputation waiting for this
    // lock, and what is written here must be by the setting it will find (RPS-1323).
    final var verifyAll = this.lockAndIsVerifyAll(version);

    if (verifyAll && !recorded) {
      this.forgetUnlessStillVerifies(repoInfo, version, storagePath);
    }

    this.refreshSigned(repoInfo.getStorageKey(), version, versionPathOf(storagePath), verifyAll);
  }

  private void forgetUnlessStillVerifies(
      final BaseRepoInfo<UUID> repoInfo,
      final ArtifactVersion version,
      final StoragePath storagePath) {

    final var fileName = storagePath.getRelativePath().getFileName();
    final var recordedId = this.findRecordedId(version, fileName);

    // Nothing recorded, nothing to forget. Deleting by file name here would take a record that a
    // signature request commits after this read, and leave the version unsigned (RPS-1984).
    if (recordedId.isEmpty()) {
      return;
    }

    if (!this.stillVerifies(repoInfo, PendingSignatureService.pathOf(storagePath))) {
      this.forget(recordedId.get());
    }
  }

  /**
   * Whether the verified signature recorded for a file that was just stored is the one of the bytes
   * that are stored now, and not of the ones they replaced. Called only when a record exists.
   *
   * <p>A signature is small and its request often runs whole between the moment the file it signs
   * is stored and the moment that file's own request gets here: it finds the file, verifies against
   * these very bytes and records them. Forgetting that record on the file's behalf would leave the
   * version unsigned for good, as nothing recomputes it afterwards (RPS-1320). So a record that is
   * there is checked once more against the file and the signature that are stored, and is kept if
   * it holds. When the file is new no record exists and nothing is read. When it replaced another
   * (a redeploy) the record is, in general, the one of the old bytes, does not verify and goes; if
   * the same bytes were stored again it holds, and stays.
   */
  private boolean stillVerifies(final BaseRepoInfo<UUID> repoInfo, final String filePath) {

    final var repoName = repoInfo.getName();
    final var file =
        this.mavenStorage().get(StoragePath.of(repoInfo.getStorageKey(), filePath), repoName);
    final var signature =
        this.mavenStorage()
            .get(StoragePath.of(repoInfo.getStorageKey(), filePath + SIGNATURE_SUFFIX), repoName);

    if (file.isEmpty() || signature.isEmpty()) {
      return false;
    }

    try {
      this.verifyAgainst(repoInfo, file.get(), signature.get());

      return true;
    } catch (final RuntimeException e) {
      // Not verified, or not verifiable now (a key that cannot be found): not vouched for.
      log.debug("The recorded signature of {} no longer verifies: {}", filePath, e.toString());

      return false;
    }
  }

  /** The version directory a file sits in, {@code <group>/<artifactId>/<version>}. */
  public static String versionPathOf(final StoragePath storagePath) {

    final var fullPath = storagePath.getPath().replace("\\", "/");

    return fullPath.substring(fullPath.indexOf("/") + 1, fullPath.lastIndexOf("/"));
  }

  /**
   * Replaces a signature's path with the path of the file it signs, {@code .asc} dropped, in the
   * same repo.
   */
  @Transactional(readOnly = true)
  public StoragePath getNonSignedStoragePath(final StoragePath signedStoragePath) {

    final var signaturePath = signedStoragePath.getRelativePath().getPath();

    final var suffixLength = SIGNATURE_SUFFIX.length();

    final var nonSignedFileName = signaturePath.substring(0, signaturePath.length() - suffixLength);

    return StoragePath.of(signedStoragePath.getStorageKey(), nonSignedFileName);
  }

  /**
   * Records the verified signature of the file {@code signaturePath} signs and updates {@code
   * signed}: set directly on a repo that only verifies the POM signature, recomputed from all the
   * files of the version on one that verifies every signature (RPS-1188).
   */
  public void processSignedFile(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath signaturePath) {

    final var signedStoragePath = this.getNonSignedStoragePath(signaturePath);

    final var gav = MavenGavUtils.convertPathToGav(signedStoragePath.getRelativePath().getPath());

    if (null == gav) {
      throw new ItemNotFoundException(ProtocolErrorCodes.ITEM_NOT_FOUND);
    }

    final var artifactVersion =
        this.artifactQueryService.findRegisteredVersion(repoInfo.getStorageKey(), gav).orElse(null);

    // verifySignature refuses a signature without a registered version before it is stored, so
    // this is a defensive check for a version that vanished in between.
    if (artifactVersion == null) {
      throw new ItemNotFoundException(ProtocolErrorCodes.ARTIFACT_VERSION_NOT_FOUND);
    }

    if (repoInfo.isPgpVerifyAllSignaturesEnabled()) {
      // A copy of this signature parked earlier goes, after any check of it that is running.
      this.pendingSignatureService.claim(
          repoInfo.getStorageKey(), PendingSignatureService.pathOf(signedStoragePath));
    }

    // The version's lock is taken before its signature is recorded, and the setting is read after
    // it: repoInfo is from the start of the request, and a toggle may have committed since. The
    // recomputation that toggle started waits for this lock, so it comes after what is written here
    // (RPS-1323). The lock comes after the claim above, as it always did, so the two locks are
    // taken in the same order as by the checks of parked signatures.
    final var verifyAll = this.lockAndIsVerifyAll(artifactVersion);

    this.recordVerified(artifactVersion, signedStoragePath.getRelativePath().getFileName());

    // The rule of the setting it is now: every file has a verified signature, or the POM's has.
    this.refreshSigned(
        repoInfo.getStorageKey(), artifactVersion, versionPathOf(signedStoragePath), verifyAll);
  }

  /**
   * Verifies the signature of a stored file before the signature itself is stored, so a refused
   * signature leaves no trace: nothing is written and no row or file is deleted (RPS-1186). It used
   * to run after the signature was stored, and the facade then rolled the whole version back.
   *
   * <p>On a repo that verifies every signature (RPS-1188) a signature whose file is not stored, or
   * whose version is not registered, is parked instead of refused: Maven uploads in parallel, so it
   * may overtake either. It is parked in a transaction of its own that commits first, and the file
   * and the version are looked at once more after that, so a file that landed in between is not
   * missed: the file's own upload finds the parked row if it comes later, this request finds the
   * file if it came earlier.
   *
   * @throws ItemNotFoundException {@code itemNotFound} when the signed file is not stored
   * @throws ItemNotFoundException {@code artifactVersionNotFound} when the POM is stored but its
   *     version is not registered (RPS-1191)
   */
  @Transactional(readOnly = true)
  public SignatureOutcome verifySignature(
      final BaseRepoInfo<UUID> repoInfo,
      final StoragePath signedStoragePath,
      final Resource signature) {

    final var nonSignedStoragePath = this.getNonSignedStoragePath(signedStoragePath);
    final var storedFile = this.mavenStorage().get(nonSignedStoragePath, repoInfo.getName());

    if (storedFile.isPresent() && this.isVersionRegistered(repoInfo, nonSignedStoragePath)) {
      this.verifyAgainst(repoInfo, storedFile.get(), signature);

      return SignatureOutcome.VERIFIED;
    }

    if (!repoInfo.isPgpVerifyAllSignaturesEnabled()) {
      // A POM stored before RPS-1193, or whose rows are gone, can lack a registered version (a POM
      // of another group was stored but skipped by checkExtractedInfos). A signature could then
      // not be recorded, so it is refused here, before the key lookup and before anything is
      // stored (RPS-1191).
      throw new ItemNotFoundException(
          storedFile.isPresent()
              ? ProtocolErrorCodes.ARTIFACT_VERSION_NOT_FOUND
              : ProtocolErrorCodes.ITEM_NOT_FOUND);
    }

    return this.parkOrVerify(repoInfo, nonSignedStoragePath, signature);
  }

  private boolean isVersionRegistered(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath nonSignedStoragePath) {

    final var gav =
        MavenGavUtils.convertPathToGav(nonSignedStoragePath.getRelativePath().getPath());

    return gav != null
        && this.artifactQueryService
            .findRegisteredVersion(repoInfo.getStorageKey(), gav)
            .isPresent();
  }

  private void verifyAgainst(
      final BaseRepoInfo<UUID> repoInfo, final Resource storedFile, final Resource signature) {

    final var sources =
        this.keyStoreService.getPublicKeySources(
            repoInfo.getStorageKey(), repoInfo.isPgpKeyServerLookupEnabled());

    this.pgpVerifierService.verify(storedFile, signature, sources);
  }

  /**
   * Parks the signature, then looks for the file and the version once more: if both are there now,
   * the signature is verified after all and the request stores it like any other (its parked copy
   * is dropped when it is recorded, or here when it does not verify).
   */
  private SignatureOutcome parkOrVerify(
      final BaseRepoInfo<UUID> repoInfo,
      final StoragePath nonSignedStoragePath,
      final Resource signature) {

    final var filePath = PendingSignatureService.pathOf(nonSignedStoragePath);

    this.pendingSignatureService.park(repoInfo.getStorageKey(), filePath, readBytes(signature));

    final var storedFile = this.mavenStorage().get(nonSignedStoragePath, repoInfo.getName());

    if (storedFile.isEmpty() || !this.isVersionRegistered(repoInfo, nonSignedStoragePath)) {
      return SignatureOutcome.PARKED;
    }

    try {
      this.verifyAgainst(repoInfo, storedFile.get(), signature);
    } catch (final RuntimeException e) {
      this.pendingSignatureService.discard(repoInfo.getStorageKey(), filePath);

      throw e;
    }

    return SignatureOutcome.VERIFIED;
  }

  private static byte[] readBytes(final Resource signature) {

    try {
      return signature.getContentAsByteArray();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private StorageStrategy mavenStorage() {
    return this.storageStrategyRegistry.get(RepoType.MAVEN);
  }
}
