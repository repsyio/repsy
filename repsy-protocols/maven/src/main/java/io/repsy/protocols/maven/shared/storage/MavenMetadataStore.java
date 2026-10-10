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
package io.repsy.protocols.maven.shared.storage;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.maven.shared.artifact.dtos.PluginPrefixChange;
import io.repsy.protocols.maven.shared.artifact.dtos.RegisteredPlugin;
import io.repsy.protocols.maven.shared.utils.ArtifactMetadataSynthesizer;
import io.repsy.protocols.maven.shared.utils.MavenMetadataUtils;
import io.repsy.protocols.maven.shared.utils.MavenStoragePathUtils;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Plugin;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;

/**
 * Keeps the stored {@code maven-metadata.xml} of an artifact or a group in step with what is
 * registered: it removes a deleted version, adds the versions and plugins the file lacks and
 * renames a plugin prefix, and recomputes the checksums that are stored while it deletes a stored
 * signature that no longer verifies. Every change of one file, and a client's own upload of it,
 * goes through the same lock (RPS-1437, RPS-1457).
 *
 * <p>The state is the lock table, so one instance belongs to one storage service: the locks only
 * serialize the callers that share it.
 */
@NullMarked
public class MavenMetadataStore<ID> {

  private static final String METADATA_FILENAME = "maven-metadata.xml";

  /**
   * A stored {@code maven-metadata.xml.asc} and its own checksum siblings, in the order they are
   * checked for and deleted when the metadata they sign is rewritten (RPS-1197).
   */
  private static final List<String> METADATA_SIGNATURE_FAMILY =
      List.of(
          METADATA_FILENAME + ".asc",
          METADATA_FILENAME + ".asc.md5",
          METADATA_FILENAME + ".asc.sha1",
          METADATA_FILENAME + ".asc.sha256",
          METADATA_FILENAME + ".asc.sha512");

  /**
   * The number of locks a stored metadata file is guarded by. A directory takes the one its path
   * hashes to, so two may share one: it only makes them wait for each other.
   */
  private static final int ARTIFACT_LOCK_STRIPES = 64;

  /**
   * Serializes every change of an artifact's or a group's stored {@code maven-metadata.xml} inside
   * this process: the append that a registered POM triggers, the rewrite of a version delete and a
   * client's own upload of the file, which would otherwise each read the file, change it and write
   * it back over the change of another (RPS-1437, RPS-1457). It does not reach another instance
   * that shares the storage.
   */
  private final ReentrantLock[] artifactLocks = newArtifactLocks();

  private static ReentrantLock[] newArtifactLocks() {

    final var locks = new ReentrantLock[ARTIFACT_LOCK_STRIPES];

    for (int i = 0; i < locks.length; i++) {
      locks[i] = new ReentrantLock();
    }

    return locks;
  }

  private ReentrantLock lockOf(final UUID storageKey, final Path metadataDirectory) {

    final var hash = Objects.hash(storageKey, metadataDirectory.toString());

    return this.artifactLocks[Math.floorMod(hash, ARTIFACT_LOCK_STRIPES)];
  }

  private final StorageStrategy storageStrategy;

  public MavenMetadataStore(final StorageStrategy storageStrategy) {
    this.storageStrategy = storageStrategy;
  }

  /**
   * Writes a file, under the lock of its directory when it is a {@code maven-metadata.xml}. A
   * client's own artifact-level or group-level {@code maven-metadata.xml} replaces the file whole,
   * so it must not land between the read and the write of an append or a delete rewrite of the same
   * file (RPS-1437, RPS-1457). Any other file is written without a lock.
   */
  public BaseUsages write(
      final StoragePath storagePath, final InputStream inputStream, final String repoName) {

    final var metadataDirectory = this.metadataDirectoryOf(storagePath.getRelativePath().getPath());

    if (metadataDirectory == null) {
      return this.storageStrategy.write(repoName, storagePath, inputStream);
    }

    final var lock =
        this.lockOf(Objects.requireNonNull(storagePath.getStorageKey()), metadataDirectory);

    lock.lock();

    try {
      return this.storageStrategy.write(repoName, storagePath, inputStream);
    } finally {
      lock.unlock();
    }
  }

  /**
   * The directory a stored {@code maven-metadata.xml} is guarded by the lock of, {@code null} when
   * the path is not that file (a checksum of it, a signature and any other file take no lock). A
   * path of three or more segments is the artifact-level file of {@code <parent>:<last>} (which is
   * also where the group-level file of the group of the whole path sits, so both name one lock); a
   * path of two segments, a group of one, is only a group-level file, and is keyed the way {@link
   * #addPluginsToGroupMetadata} keys it.
   */
  private @Nullable Path metadataDirectoryOf(final String relativePath) {

    final var artifactMetadata = ArtifactMetadataSynthesizer.parse(relativePath);

    if (artifactMetadata != null) {
      return artifactMetadata.checksumAlgorithm() == null
          ? MavenStoragePathUtils.artifactPath(
              artifactMetadata.groupId(), artifactMetadata.artifactId())
          : null;
    }

    final var groupMetadata = ArtifactMetadataSynthesizer.parseGroupLevel(relativePath);

    return groupMetadata == null || groupMetadata.checksumAlgorithm() != null
        ? null
        : MavenStoragePathUtils.groupPaths(groupMetadata.groupId())[0];
  }

  /**
   * Removes the given version from the artifact's {@code maven-metadata.xml} and writes the file
   * back. An artifact published without one (Ivy, sbt or a raw PUT never send it, and Repsy only
   * synthesizes one on read, never storing it) has nothing to rewrite: no file is created and the
   * usage delta is zero. A file without a {@code <versioning>} element lists no versions, so it is
   * left as it is.
   *
   * <p>The file is read before anything is written, so a file that cannot be parsed fails here
   * without having changed a byte; the artifact's {@code latest} and {@code release} are not taken
   * from it but computed from the version rows (RPS-1331).
   */
  public BaseUsages deleteVersionFromMetadata(
      final BaseRepoInfo<ID> repoInfo,
      final String groupId,
      final String artifactId,
      final String versionName)
      throws IOException, XmlPullParserException {

    return this.rewriteArtifactMetadata(
        repoInfo,
        groupId,
        artifactId,
        metadata -> {
          metadata.getVersioning().getVersions().remove(versionName);

          return true;
        });
  }

  /**
   * Adds the registered versions that the stored file lacks (RPS-1437), the same rewrite as the one
   * of a version delete: the file is parsed before anything is written, the versions are sorted,
   * {@code latest} is the highest one and {@code release} the highest that is not a snapshot,
   * {@code lastUpdated} is now, and the checksums that are stored are recomputed while a stored
   * signature is deleted. A file that already lists every registered version is left byte for byte
   * as it is, with its signature.
   */
  public long addVersionsToMetadata(
      final BaseRepoInfo<ID> repoInfo,
      final String groupId,
      final String artifactId,
      final Supplier<? extends Collection<String>> registeredVersions)
      throws IOException {

    return this.rewriteArtifactMetadata(
            repoInfo,
            groupId,
            artifactId,
            metadata -> {
              final var listed = metadata.getVersioning().getVersions();
              var changed = false;

              for (final var versionName : registeredVersions.get()) {
                if (!listed.contains(versionName)) {
                  listed.add(versionName);
                  changed = true;
                }
              }

              return changed;
            })
        .getDiskUsage();
  }

  /**
   * Reads the artifact's stored {@code maven-metadata.xml} and, when {@code mutate} answers that it
   * changed the versions, stamps {@code lastUpdated}, recomputes {@code latest} and {@code release}
   * and writes the file back (see {@link #rewriteStoredMetadata}). Nothing is written when there is
   * no file, when it has no {@code <versioning>} (so {@code mutate} always finds one), or when
   * {@code mutate} answers {@code false}.
   */
  private BaseUsages rewriteArtifactMetadata(
      final BaseRepoInfo<ID> repoInfo,
      final String groupId,
      final String artifactId,
      final Predicate<Metadata> mutate)
      throws IOException {

    return this.rewriteStoredMetadata(
        repoInfo,
        MavenStoragePathUtils.artifactPath(groupId, artifactId),
        metadata -> {
          final var versioning = metadata.getVersioning();

          if (versioning == null || !mutate.test(metadata)) {
            return false;
          }

          versioning.setLastUpdatedTimestamp(Date.from(Instant.now()));

          MavenMetadataUtils.setReleaseAndLatest(metadata);

          return true;
        });
  }

  /**
   * Adds to the group-level {@code maven-metadata.xml} that is stored the registered plugins whose
   * artifactId it does not list (RPS-1457), the {@code <plugins>} analogue of {@link
   * #addVersionsToMetadata}. It only ever appends: an entry is matched by its artifactId and never
   * changed or removed, so a prefix Maven wrote (a custom {@code goalPrefix}) is not replaced by
   * the derived one, and {@code <versioning>} and {@code lastUpdated} are not touched. A file that
   * has a {@code <versioning>} and no {@code <plugins>} is the artifact-level file of {@code
   * <parent>:<last>} that has the same path, and is left alone.
   */
  public long addPluginsToGroupMetadata(
      final BaseRepoInfo<ID> repoInfo,
      final String groupId,
      final Supplier<? extends Collection<RegisteredPlugin>> registeredPlugins)
      throws IOException {

    return this.rewriteStoredMetadata(
            repoInfo,
            MavenStoragePathUtils.groupPaths(groupId)[0],
            metadata -> {
              if (metadata.getVersioning() != null && metadata.getPlugins().isEmpty()) {
                return false;
              }

              final var listed =
                  metadata.getPlugins().stream()
                      .map(Plugin::getArtifactId)
                      .collect(Collectors.toCollection(HashSet::new));

              var changed = false;

              for (final var registered : registeredPlugins.get()) {
                if (listed.add(registered.artifactId())) {
                  metadata.addPlugin(ArtifactMetadataSynthesizer.toPlugin(registered));
                  changed = true;
                }
              }

              return changed;
            })
        .getDiskUsage();
  }

  public long replacePluginPrefixInGroupMetadata(
      final BaseRepoInfo<ID> repoInfo, final String groupId, final PluginPrefixChange change)
      throws IOException {

    return this.rewriteStoredMetadata(
            repoInfo,
            MavenStoragePathUtils.groupPaths(groupId)[0],
            metadata -> {
              if (metadata.getVersioning() != null && metadata.getPlugins().isEmpty()) {
                return false;
              }

              final var stale =
                  metadata.getPlugins().stream()
                      .filter(plugin -> change.artifactId().equals(plugin.getArtifactId()))
                      .filter(plugin -> change.from().equals(plugin.getPrefix()))
                      .toList();

              if (stale.isEmpty()) {
                return false;
              }

              final var listedUnderNewPrefix =
                  metadata.getPlugins().stream()
                      .anyMatch(
                          plugin ->
                              change.artifactId().equals(plugin.getArtifactId())
                                  && change.to().equals(plugin.getPrefix()));

              if (listedUnderNewPrefix) {
                metadata.getPlugins().removeAll(stale);
              } else {
                stale.forEach(plugin -> plugin.setPrefix(change.to()));
              }

              return true;
            })
        .getDiskUsage();
  }

  /**
   * Reads the {@code maven-metadata.xml} stored in {@code metadataDirectory} and, when {@code
   * mutate} answers that it changed it, writes it back with the checksums and the signature
   * handling of {@link #writeMetadataAndChecksumsToFile}. Nothing is written when there is no file
   * or when {@code mutate} answers {@code false}, and a file that cannot be parsed fails before a
   * byte is written. The whole read-change-write holds the lock of the directory, the one a
   * client's own upload of the file takes too.
   */
  private BaseUsages rewriteStoredMetadata(
      final BaseRepoInfo<ID> repoInfo,
      final Path metadataDirectory,
      final Predicate<Metadata> mutate)
      throws IOException {

    final var storagePath =
        StoragePath.of(
            repoInfo.getStorageKey(), metadataDirectory.resolve(METADATA_FILENAME).toString());

    final var lock =
        this.lockOf(Objects.requireNonNull(repoInfo.getStorageKey()), metadataDirectory);

    lock.lock();

    try {
      final var metadataResource = this.storageStrategy.get(storagePath, repoInfo.getName());

      if (metadataResource.isEmpty()) {
        return BaseUsages.ofDisk(0L);
      }

      final var metadata =
          MavenMetadataUtils.readMetadata(metadataResource.get().getContentAsByteArray());

      if (!mutate.test(metadata)) {
        return BaseUsages.ofDisk(0L);
      }

      return this.writeMetadataAndChecksumsToFile(
          storagePath, metadataDirectory, metadata, repoInfo.getName());
    } finally {
      lock.unlock();
    }
  }

  /**
   * Write a Metadata object into a file. If a metadata file already exists this method overwrites
   * it
   */
  private BaseUsages writeMetadataAndChecksumsToFile(
      final StoragePath metadataStoragePath,
      final Path artifactBasePath,
      final Metadata metadata,
      final String repoName)
      throws IOException {

    final var metadataXpp3Writer = new MetadataXpp3Writer();

    try (final var byteArrayOutputStream = new ByteArrayOutputStream()) {
      metadataXpp3Writer.write(byteArrayOutputStream, metadata);

      final var metadataBytes = byteArrayOutputStream.toByteArray();

      final BaseUsages usages;

      try (final var metadataInputStream = new ByteArrayInputStream(metadataBytes)) {
        usages = this.storageStrategy.write(repoName, metadataStoragePath, metadataInputStream);
      }

      final var checksumsDelta =
          this.updateChecksumsOfMetadata(metadataStoragePath, artifactBasePath, repoName);

      // A stored maven-metadata.xml.asc signs the file this rewrite just replaced, so it no
      // longer verifies; there is no way to re-sign it here. Delete it and its own checksum
      // siblings rather than leave a signature that silently fails to verify (RPS-1197).
      final var deletedSignatureBytes =
          this.deleteStaleMetadataSignature(
              Objects.requireNonNull(metadataStoragePath.getStorageKey()),
              artifactBasePath,
              repoName);

      return BaseUsages.ofDisk(usages.getDiskUsage() + checksumsDelta - deletedSignatureBytes);
    }
  }

  /**
   * Deletes a stored {@code maven-metadata.xml.asc} and its checksum siblings ({@code
   * .asc.md5}/{@code .asc.sha1}/{@code .asc.sha256}/{@code .asc.sha512}) if present, after the
   * metadata they sign was rewritten. Returns the total bytes freed, folded into the caller's usage
   * delta (subtracted, same sign convention as the metadata rewrite itself).
   */
  private long deleteStaleMetadataSignature(
      final UUID repoId, final Path artifactBasePath, final String repoName) {

    var freedBytes = 0L;

    for (final var fileName : METADATA_SIGNATURE_FAMILY) {
      final var storagePath = StoragePath.of(repoId, artifactBasePath.resolve(fileName).toString());

      final var optionalResource = this.storageStrategy.get(storagePath, repoName);

      if (optionalResource.isEmpty() || !optionalResource.get().exists()) {
        continue;
      }

      freedBytes += this.contentLengthQuietly(optionalResource.get());
      this.storageStrategy.delete(storagePath);
    }

    return freedBytes;
  }

  private long contentLengthQuietly(final Resource resource) {
    try {
      return resource.contentLength();
    } catch (final IOException e) {
      return 0L;
    }
  }

  /**
   * Calculate digest checksums of metadata file and write them into the checksum files that are
   * stored, none is created. Returns the change of the disk usage they caused, which a digest
   * rewritten with a longer or shorter algorithm output would leave out of the count.
   */
  private long updateChecksumsOfMetadata(
      final StoragePath metadataStoragePath, final Path artifactBasePath, final String repoName)
      throws IOException {

    final var repoId = metadataStoragePath.getStorageKey();

    final var resource =
        this.storageStrategy
            .get(metadataStoragePath, repoName)
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.ITEM_NOT_FOUND));

    final var metadataContent = resource.getContentAsString(StandardCharsets.UTF_8);

    // Hash algorithms and its extensions
    final var hashFunctions =
        Map.<String, UnaryOperator<String>>of(
            "md5", DigestUtils::md5Hex,
            "sha1", DigestUtils::sha1Hex,
            "sha256", DigestUtils::sha256Hex,
            "sha512", DigestUtils::sha512Hex);

    var delta = 0L;

    for (final var entry : hashFunctions.entrySet()) {
      delta +=
          this.writeChecksumIfExists(
              Objects.requireNonNull(repoId),
              artifactBasePath,
              repoName,
              metadataContent,
              entry.getKey(),
              entry.getValue());
    }

    return delta;
  }

  private long writeChecksumIfExists(
      final UUID repoId,
      final Path artifactBasePath,
      final String repoName,
      final String content,
      final String extension,
      final UnaryOperator<String> hashFunction)
      throws IOException {

    final var checksumFile = artifactBasePath.resolve(METADATA_FILENAME + "." + extension);

    final var storagePath = StoragePath.of(repoId, checksumFile.toString());

    final var optionalResource = this.storageStrategy.get(storagePath, repoName);

    if (optionalResource.isEmpty() || !optionalResource.get().exists()) {
      return 0L;
    }

    final var checksumBytes = hashFunction.apply(content).getBytes(StandardCharsets.UTF_8);

    try (final var checksumInputStream = new ByteArrayInputStream(checksumBytes)) {
      return this.storageStrategy.write(repoName, storagePath, checksumInputStream).getDiskUsage();
    }
  }
}
