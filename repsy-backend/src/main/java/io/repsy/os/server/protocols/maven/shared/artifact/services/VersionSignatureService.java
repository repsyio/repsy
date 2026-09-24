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

import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.VersionSignature;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionSignatureRepository;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps track of which files of a Maven version have a verified detached signature, and derives the
 * version's {@code signed} flag from it on a repo that verifies every signature (RPS-1188). A
 * version is then signed when it has files to sign and every one of them has a verified signature;
 * for a snapshot only the files of its newest build are the ones to sign ({@link
 * ArtifactUtils#filesToSign}).
 *
 * <p>It joins the transaction of the upload it is called from.
 */
@Component
@Transactional
@RequiredArgsConstructor
@NullMarked
public class VersionSignatureService {

  private final VersionSignatureRepository versionSignatureRepository;
  private final ArtifactVersionRepository artifactVersionRepository;

  @Qualifier("osStorageStrategyMaven")
  private final StorageStrategy storageStrategy;

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

  /** Whether a verified signature is recorded for the file. */
  @Transactional(readOnly = true)
  public boolean isRecorded(final ArtifactVersion version, final String fileName) {

    return this.versionSignatureRepository
        .findByArtifactVersionIdAndFileName(version.getId(), fileName)
        .isPresent();
  }

  /** Forgets the verified signature of a file that was stored again: its bytes are new. */
  public void forget(final ArtifactVersion version, final String fileName) {

    this.versionSignatureRepository.deleteByArtifactVersionIdAndFileName(version.getId(), fileName);
  }

  /**
   * Sets {@code version.signed} from what the version directory holds now and what has a verified
   * signature.
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

    this.lock(version);

    final var items =
        this.storageStrategy.listStorageItems(StoragePath.of(storageKey, versionPath));
    final var toSign =
        ArtifactUtils.filesToSign(
            versionPath, ArtifactUtils.versionDirFileNames(versionPath, items));
    final var verified =
        this.versionSignatureRepository.findFileNamesByArtifactVersionId(version.getId());

    final var signed = isSigned(toSign, verified);

    // A bulk update, so the entity is not marked dirty and flushed again with all its columns.
    this.artifactVersionRepository.updateSigned(version.getId(), signed);
  }

  /** Whether there is something to sign and every file of it has a verified signature. */
  static boolean isSigned(final Collection<String> filesToSign, final Collection<String> verified) {

    return !filesToSign.isEmpty() && verified.containsAll(filesToSign);
  }
}
