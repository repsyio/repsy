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
package io.repsy.os.server.protocols.docker.shared.tag.services;

import static io.repsy.protocols.docker.shared.utils.ManifestNameGenerator.generate;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Manifest;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.TagRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.docker.shared.utils.DockerDigestCalculator;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Brings the manifests that an earlier version stored to the layout of RPS-1216: a file at {@code
 * manifests/<sha256 digest>} and a {@code sha512} digest on the row.
 *
 * <p>Before RPS-1216 a manifest file was named after the image and the tag it was pushed under, and
 * the database migration keeps that reference in {@code docker_manifest.storage_name}, so the
 * registry keeps serving those files while this service has not reached them. For each such row the
 * service finds the file that really holds the manifest (the name in {@code storage_name}, the name
 * of a tag that points at the digest, or the digest itself: the bytes must hash to the row's
 * digest), fills the row's {@code sha512} digest from them, renames the file to the digest and
 * clears {@code storage_name}. When a file with that digest name exists already (the manifest was
 * pushed again after the upgrade) the legacy file is redundant and is dropped, and the bytes it
 * held are released from the repo's usage. So are the other legacy copies of the same bytes that
 * the old code stored, one per tag or digest reference the manifest was pushed under, whose rows
 * the migration folded into this one: they have no row any more and would stay on disk, and in the
 * repo's usage, for good.
 *
 * <p>A row whose file cannot be found, or whose bytes do not hash to its digest, is logged and left
 * exactly as it is: it keeps answering as it did before. The service is idempotent and resumable: a
 * row is repaired in a transaction of its own, a repaired row no longer matches the query that
 * finds work, and a run that finds nothing costs one query. A failed run is retried by the next.
 */
@Slf4j
@NullMarked
@Service
public class DockerManifestLayoutRepairService {

  private static final int BATCH_SIZE = 100;
  private static final String MANIFESTS_DIRECTORY = "manifests";

  private final ManifestRepository manifestRepository;
  private final TagRepository tagRepository;
  private final DockerStorageService dockerStorageService;
  private final UsageUpdateService usageUpdateService;
  private final TransactionTemplate transactionTemplate;

  public DockerManifestLayoutRepairService(
      final ManifestRepository manifestRepository,
      final TagRepository tagRepository,
      final DockerStorageService dockerStorageService,
      final UsageUpdateService usageUpdateService,
      final PlatformTransactionManager transactionManager) {

    this.manifestRepository = manifestRepository;
    this.tagRepository = tagRepository;
    this.dockerStorageService = dockerStorageService;
    this.usageUpdateService = usageUpdateService;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * What one run of {@link #repair()} did.
   *
   * @param repaired rows that are now in the new layout
   * @param unresolved rows left as they are: no file holds their digest
   * @param failed rows whose repair failed with an error (logged), retried by the next run
   */
  public record RepairReport(int repaired, int unresolved, int failed) {

    public boolean isEmpty() {
      return this.repaired == 0 && this.unresolved == 0 && this.failed == 0;
    }
  }

  private enum Outcome {
    REPAIRED,
    UNRESOLVED,
    FAILED,
    UNCHANGED
  }

  /** The file of a manifest, found under one of its candidate names, and what it holds. */
  private static final class LocatedFile {

    private final String name;
    private final byte[] bytes;

    private LocatedFile(final String name, final byte[] bytes) {
      this.name = name;
      this.bytes = bytes;
    }

    private String name() {
      return this.name;
    }

    private byte[] bytes() {
      return this.bytes;
    }
  }

  public RepairReport repair() {

    final var outcomes = new EnumMap<Outcome, Integer>(Outcome.class);

    var ids = this.manifestRepository.findRepairableIds(PageRequest.ofSize(BATCH_SIZE));

    while (!ids.isEmpty()) {
      for (final var id : ids) {
        outcomes.merge(this.repairInTransaction(id), 1, Integer::sum);
      }

      ids =
          this.manifestRepository.findRepairableIdsAfter(
              ids.getLast(), PageRequest.ofSize(BATCH_SIZE));
    }

    return new RepairReport(
        outcomes.getOrDefault(Outcome.REPAIRED, 0),
        outcomes.getOrDefault(Outcome.UNRESOLVED, 0),
        outcomes.getOrDefault(Outcome.FAILED, 0));
  }

  private Outcome repairInTransaction(final UUID id) {

    try {
      final var outcome = this.transactionTemplate.execute(status -> this.repairOne(id));

      return outcome != null ? outcome : Outcome.FAILED;
    } catch (final RuntimeException e) {
      log.error("Could not repair the layout of Docker manifest {}", id, e);
      return Outcome.FAILED;
    }
  }

  private Outcome repairOne(final UUID id) {

    final var manifest = this.manifestRepository.findById(id).orElse(null);

    if (manifest == null) {
      return Outcome.UNCHANGED;
    }

    final var located = this.locate(manifest);

    if (located.isEmpty()) {
      log.warn(
          "Docker manifest {} of image {} in repo {} has no stored file that hashes to its digest"
              + " {}. Left as it is.",
          manifest.getId(),
          manifest.getImage().getName(),
          manifest.getImage().getRepo().getName(),
          manifest.getDigest());
      return Outcome.UNRESOLVED;
    }

    this.fillSha512(manifest, located.get().bytes());

    if (manifest.getStorageName() != null) {
      // Taken before the move clears storage_name.
      final var legacyNames = this.legacyNames(manifest);

      if (!this.moveToDigest(manifest, located.get().name())) {
        return Outcome.UNRESOLVED;
      }

      this.removeRedundantCopies(manifest, legacyNames, located.get().name());
    }

    return Outcome.REPAIRED;
  }

  /**
   * The first candidate file whose bytes hash to the digest of the row. The name in {@code
   * storage_name} comes first, then the names of the tags that point at the digest (the old code
   * could leave the row's name at another tag than the one whose push wrote these bytes), then the
   * digest itself.
   */
  private Optional<LocatedFile> locate(final Manifest manifest) {

    for (final var name : this.candidateNames(manifest)) {
      final var bytes = this.read(manifest, name);

      if (bytes.isPresent() && manifest.getDigest().equals(sha256(bytes.get()))) {
        return Optional.of(new LocatedFile(name, bytes.get()));
      }
    }

    return Optional.empty();
  }

  private List<String> candidateNames(final Manifest manifest) {

    final var names = new ArrayList<String>();

    if (manifest.getStorageName() != null) {
      names.addAll(this.legacyNames(manifest));
    }

    names.add(manifest.getDigest());

    return names;
  }

  /**
   * The names the old code may have stored this manifest's bytes under: the reference in {@code
   * storage_name}, the tags that point at the digest, and the digest references (sha256, and the
   * sha512 one when it is known) a push by digest was stored under.
   */
  private Set<String> legacyNames(final Manifest manifest) {

    final var repoId = manifest.getImage().getRepo().getId();
    final var imageName = manifest.getImage().getName();
    final var references = new LinkedHashSet<String>();

    references.add(manifest.getStorageName());
    references.addAll(
        this.tagRepository.findNamesByImageIdAndDigest(
            manifest.getImage().getId(), manifest.getDigest()));
    references.add(manifest.getDigest());

    if (manifest.getDigestSha512() != null) {
      references.add(manifest.getDigestSha512());
    }

    return references.stream()
        .map(reference -> generate(repoId, imageName, reference))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Drops the other legacy files that hold exactly this manifest: they were written by pushes of
   * the same bytes under other references, whose rows the migration folded into this one, and are
   * unreachable now. Only a file whose bytes hash to the digest goes.
   */
  private void removeRedundantCopies(
      final Manifest manifest, final Set<String> legacyNames, final String keptName) {

    final var repo = manifest.getImage().getRepo();
    var freed = 0L;

    for (final var name : legacyNames) {
      final var bytes =
          name.equals(keptName) ? Optional.<byte[]>empty() : this.read(manifest, name);

      if (bytes.isPresent() && manifest.getDigest().equals(sha256(bytes.get()))) {
        freed += this.deleteManifestFile(repo, name);
      }
    }

    if (freed > 0) {
      this.usageUpdateService.updateUsage(
          new UsageChangedInfo(repo.getId(), BaseUsages.ofDisk(-freed)));
    }
  }

  private long deleteManifestFile(final Repo repo, final String name) {

    final var repoInfo = new BaseRepoInfo<UUID>();
    repoInfo.setId(repo.getId());
    repoInfo.setStorageKey(repo.getId());
    repoInfo.setName(repo.getName());

    return this.dockerStorageService.deleteManifest(repoInfo, name);
  }

  private Optional<byte[]> read(final Manifest manifest, final String name) {

    final var repo = manifest.getImage().getRepo();
    final var storagePath =
        StoragePath.of(repo.getId(), Paths.get(MANIFESTS_DIRECTORY, name).toString());

    return this.dockerStorageService
        .getResource(storagePath, repo.getName())
        .filter(Resource::exists)
        .map(
            resource -> {
              try {
                return resource.getContentAsByteArray();
              } catch (final IOException e) {
                log.warn("Could not read manifest file {} of repo {}", name, repo.getName(), e);
                return null;
              }
            });
  }

  private void fillSha512(final Manifest manifest, final byte[] bytes) {

    if (manifest.getDigestSha512() == null) {
      manifest.setDigestSha512(sha512(bytes));
      this.manifestRepository.save(manifest);
    }
  }

  /**
   * Renames the file to the digest, or drops it when a file with that name exists already, and
   * clears {@code storage_name}. Answers {@code false} when the file at the digest name is not what
   * the digest says (it is then not overwritten).
   */
  private boolean moveToDigest(final Manifest manifest, final String foundName) {

    final var repo = manifest.getImage().getRepo();
    final var digest = manifest.getDigest();

    if (!foundName.equals(digest)) {
      if (!this.isDigestFileSound(manifest)) {
        log.warn(
            "Docker manifest {} of repo {}: a file named {} exists but does not hold that"
                + " manifest, so the legacy file is not renamed over it.",
            manifest.getId(),
            repo.getName(),
            digest);
        return false;
      }

      final var usages =
          this.dockerStorageService.rename(
              repo.getId(),
              new RelativePath(Paths.get(MANIFESTS_DIRECTORY, foundName).toString()),
              digest);

      // Non-zero when the digest file existed and the legacy file was dropped as a duplicate.
      if (usages.getDiskUsage() != 0) {
        this.usageUpdateService.updateUsage(new UsageChangedInfo(repo.getId(), usages));
      }
    }

    manifest.setStorageName(null);
    this.manifestRepository.save(manifest);

    return true;
  }

  private boolean isDigestFileSound(final Manifest manifest) {

    return this.read(manifest, manifest.getDigest())
        .map(bytes -> manifest.getDigest().equals(sha256(bytes)))
        .orElse(true);
  }

  private static String sha256(final byte[] bytes) {

    try {
      return DockerDigestCalculator.calculateDigest(bytes);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sha512(final byte[] bytes) {

    try {
      return DockerDigestCalculator.calculateSha512Digest(bytes);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
