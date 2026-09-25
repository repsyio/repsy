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

import freemarker.template.Configuration;
import freemarker.template.TemplateException;
import io.repsy.core.error_handling.exceptions.ErrorOccurredException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.exceptions.IsADirectoryException;
import io.repsy.libs.storage.core.exceptions.RedirectToSlashEndedLocationException;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.maven.shared.utils.ArtifactMetadataSynthesizer;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.ui.freemarker.FreeMarkerTemplateUtils;

@RequiredArgsConstructor
@NullMarked
public abstract class AbstractMavenStorageService<ID> implements MavenStorageService<ID> {

  private static final String METADATA_FILENAME = "maven-metadata.xml";
  private static final String ERR_ITEM_NOT_FOUND = "itemNotFound";

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
   * The number of locks an artifact's metadata is guarded by. An artifact takes the one its path
   * hashes to, so two artifacts may share one: it only makes them wait for each other.
   */
  private static final int ARTIFACT_LOCK_STRIPES = 64;

  private final Configuration freeMarkerConfiguration;
  private final StorageStrategy storageStrategy;

  /**
   * Serializes every change of an artifact's stored {@code maven-metadata.xml} inside this process:
   * the append that a registered POM triggers, the rewrite of a version delete and a client's own
   * upload of the file, which would otherwise each read the file, change it and write it back over
   * the change of another (RPS-1437). It does not reach another instance that shares the storage.
   */
  private final ReentrantLock[] artifactLocks = newArtifactLocks();

  private static ReentrantLock[] newArtifactLocks() {

    final var locks = new ReentrantLock[ARTIFACT_LOCK_STRIPES];

    for (int i = 0; i < locks.length; i++) {
      locks[i] = new ReentrantLock();
    }

    return locks;
  }

  private ReentrantLock lockOf(final UUID storageKey, final Path artifactBasePath) {

    final var hash = Objects.hash(storageKey, artifactBasePath.toString());

    return this.artifactLocks[Math.floorMod(hash, ARTIFACT_LOCK_STRIPES)];
  }

  @Override
  public void createRepo(final UUID repoUuid) {

    this.storageStrategy.createDirectory(repoUuid.toString());
  }

  @Override
  public BaseUsages getUsages(
      final StoragePath storagePath, final String repoName, final long contentLength)
      throws IOException {

    return this.storageStrategy.getUsages(storagePath, repoName, contentLength);
  }

  @Override
  public List<StorageItemInfo> getItems(final StoragePath storagePath) {

    final var itemInfoList = this.storageStrategy.listDirectoryContents(storagePath);

    if (!itemInfoList.isEmpty()) {
      this.addDirectoryUpLink(storagePath.getRelativePath().getPath(), itemInfoList);
    }

    return itemInfoList;
  }

  @SneakyThrows
  @Override
  public Resource getResource(final String repoName, final StoragePath storagePath) {

    try {
      return this.storageStrategy
          .get(storagePath, repoName)
          .orElseThrow(() -> new ItemNotFoundException(ERR_ITEM_NOT_FOUND));

    } catch (final IsADirectoryException _) {
      if (!storagePath.getPath().endsWith("/")) {
        throw new RedirectToSlashEndedLocationException();
      }

      return this.generateDirectoryContentInHtml(repoName, storagePath);
    }
  }

  @SneakyThrows
  @Override
  public BaseUsages writeInputStreamToPath(
      final StoragePath storagePath, final InputStream inputStream, final String repoName) {

    final var artifactMetadata =
        ArtifactMetadataSynthesizer.parse(storagePath.getRelativePath().getPath());

    if (artifactMetadata == null || artifactMetadata.checksumAlgorithm() != null) {
      return this.storageStrategy.write(repoName, storagePath, inputStream);
    }

    // A client's own artifact-level maven-metadata.xml replaces the file whole, so it must not land
    // between the read and the write of an append or a delete rewrite of the same artifact
    // (RPS-1437).
    final var lock =
        this.lockOf(
            Objects.requireNonNull(storagePath.getStorageKey()),
            this.getPath(artifactMetadata.groupId(), artifactMetadata.artifactId()));

    lock.lock();

    try {
      return this.storageStrategy.write(repoName, storagePath, inputStream);
    } finally {
      lock.unlock();
    }
  }

  @SneakyThrows
  private Resource generateDirectoryContentInHtml(
      final String repoName, final StoragePath storagePath) {

    final var template = this.freeMarkerConfiguration.getTemplate("directory.ftl");

    final String directoryContentHtml;

    final var items = this.storageStrategy.listDirectoryContents(storagePath);

    if (!items.isEmpty()) {
      this.addDirectoryUpLink(storagePath.getRelativePath().getPath(), items);
    }

    try {
      directoryContentHtml =
          FreeMarkerTemplateUtils.processTemplateIntoString(
              template,
              Map.of(
                  "repository",
                  repoName,
                  "relativePath",
                  storagePath.getRelativePath().getPath(),
                  "items",
                  items));
    } catch (final TemplateException e) {

      throw new ErrorOccurredException(e);
    }

    return new ByteArrayResource(directoryContentHtml.getBytes(UTF_8));
  }

  private void addDirectoryUpLink(
      final String relativePath, final List<StorageItemInfo> directoryList) {

    if (!relativePath.isEmpty() && !relativePath.equals("/")) {
      directoryList.addFirst(StorageItemInfo.builder().name("../").directory(true).build());
    }
  }

  @Override
  public boolean exists(final StoragePath storagePath, final String repoName) {

    try {
      return this.storageStrategy.get(storagePath, repoName).isPresent();
    } catch (final IsADirectoryException _) {
      return true;
    }
  }

  @Override
  public void deleteFile(final StoragePath storagePath) {

    this.storageStrategy.delete(storagePath);
  }

  @Override
  public long deleteArtifact(final UUID repoUuid, final String groupId, final String artifactId) {

    final var artifactPath = this.getPath(groupId, artifactId);
    final var storagePath = StoragePath.of(repoUuid, artifactPath.toString());
    final var usage = this.storageStrategy.calculatePathUsage(storagePath);

    this.storageStrategy.delete(storagePath);

    return usage;
  }

  @Override
  public long deleteArtifactVersion(
      final UUID repoUuid,
      final String groupId,
      final String artifactId,
      final String versionName) {

    final var versionPath = this.getPath(groupId, artifactId, versionName);
    final var storagePath = StoragePath.of(repoUuid, versionPath.toString());

    // An earlier partial delete or a manual cleanup can leave the DB row without a directory
    // (RPS-1190): the usage of a directory that is gone is zero and deleting it is a no-op, so the
    // caller still deletes the row and rewrites the metadata.
    final var usage = this.storageStrategy.calculatePathUsage(storagePath);

    this.storageStrategy.delete(storagePath);

    return usage;
  }

  /**
   * Lists a directory that may already be gone (an earlier partial delete, a manual cleanup): a
   * missing directory has no items, so a group delete does not fail on it (RPS-1290).
   */
  private List<StorageItemInfo> listDirectoryOrEmpty(final StoragePath storagePath) {

    try {
      return this.storageStrategy.listDirectoryContents(storagePath);
    } catch (final ItemNotFoundException _) {
      return List.of();
    }
  }

  /**
   * Deletes only {@code artifactNames}' own {@code g/a} directories and this group's own
   * group-level metadata files, then prunes this group's directory and any now-empty ancestor.
   *
   * <p>Let's say our package is {@code io.repsy.test}; the folder structure is
   *
   * <pre>
   * - io
   *   - fria
   *     - ...
   *   - repsy
   *     - test
   *       - ...
   * </pre>
   *
   * Deleting only the artifact directories registered under {@code io/repsy/test} (never the whole
   * {@code io/repsy/test} directory outright) keeps a nested or sibling group that shares the path
   * prefix — for example {@code io.repsy.test.sub} — untouched even though its own directory sits
   * inside this one (RPS-1190).
   */
  @Override
  public long deleteGroup(
      final UUID repoUuid, final String groupId, final List<String> artifactNames) {

    final var groupPaths = this.getPath(groupId);
    final var groupPath = groupPaths[0];

    var usage = this.deleteGroupLevelMetadataFiles(repoUuid, groupPath);

    for (final var artifactName : artifactNames) {
      final var artifactStoragePath =
          StoragePath.of(repoUuid, groupPath.resolve(artifactName).normalize().toString());

      usage += this.storageStrategy.calculatePathUsage(artifactStoragePath);
      this.storageStrategy.delete(artifactStoragePath);
    }

    // Prune this group's own directory and its now-empty ancestors, stopping at the first one that
    // still has content — a nested sibling group's directory, or anything else this group's
    // deletion has no business touching.
    for (final var path : groupPaths) {
      final var sp = StoragePath.of(repoUuid, path + "/");

      if (!this.listDirectoryOrEmpty(sp).isEmpty()) {
        break;
      }

      this.storageStrategy.delete(sp);
    }

    return usage;
  }

  /**
   * Deletes a group-level {@code maven-metadata.xml} (a plugin-group listing) and its checksum
   * siblings, if present directly in the group's own directory. Never looks inside a subdirectory,
   * so an artifact's or a nested group's files are never considered here.
   */
  private long deleteGroupLevelMetadataFiles(final UUID repoUuid, final Path groupPath) {

    final var groupStoragePath = StoragePath.of(repoUuid, groupPath + "/");

    var usage = 0L;

    for (final var item : this.listDirectoryOrEmpty(groupStoragePath)) {
      if (item.isDirectory() || !item.getName().startsWith(METADATA_FILENAME)) {
        continue;
      }

      final var itemStoragePath =
          StoragePath.of(repoUuid, groupPath.resolve(item.getName()).toString());

      usage += item.getSize() == null ? 0L : item.getSize();
      this.storageStrategy.delete(itemStoragePath);
    }

    return usage;
  }

  @Override
  public void deleteRepo(final UUID repoUuid) {
    final var storagePath = StoragePath.of(repoUuid);
    this.storageStrategy.delete(storagePath);
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
  @Override
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
  @Override
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
   * and writes the file back with the checksums and the signature handling of {@link
   * #writeMetadataAndChecksumsToFile}. Nothing is written when there is no file, when it has no
   * {@code <versioning>} (so {@code mutate} always finds one), or when {@code mutate} answers
   * {@code false}. The whole read-change-write holds the artifact's lock.
   */
  private BaseUsages rewriteArtifactMetadata(
      final BaseRepoInfo<ID> repoInfo,
      final String groupId,
      final String artifactId,
      final Predicate<Metadata> mutate)
      throws IOException {

    final var artifactBasePath = this.getPath(groupId, artifactId);

    final var storagePath =
        StoragePath.of(
            repoInfo.getStorageKey(), artifactBasePath.resolve(METADATA_FILENAME).toString());

    final var lock =
        this.lockOf(Objects.requireNonNull(repoInfo.getStorageKey()), artifactBasePath);

    lock.lock();

    try {
      final var metadataResource = this.storageStrategy.get(storagePath, repoInfo.getName());

      if (metadataResource.isEmpty()) {
        return BaseUsages.ofDisk(0L);
      }

      final var metadata =
          ArtifactUtils.readMetadata(metadataResource.get().getContentAsByteArray());

      final var versioning = metadata.getVersioning();

      if (versioning == null || !mutate.test(metadata)) {
        return BaseUsages.ofDisk(0L);
      }

      versioning.setLastUpdatedTimestamp(Date.from(Instant.now()));

      ArtifactUtils.setReleaseAndLatest(metadata);

      return this.writeMetadataAndChecksumsToFile(
          storagePath, artifactBasePath, metadata, repoInfo.getName());
    } finally {
      lock.unlock();
    }
  }

  private Path[] getPath(final String groupId) {

    final var paths = groupId.split("\\.", -1);
    final var groupPaths = new Path[paths.length];

    var path = Path.of(paths[0]);
    groupPaths[paths.length - 1] = path;

    for (int i = 1; i < paths.length; i++) {
      path = path.resolve(paths[i]).normalize();
      groupPaths[paths.length - 1 - i] = path;
    }

    return groupPaths;
  }

  @Override
  public Path getPath(final String groupId, final String artifactId) {

    final var paths = groupId.split("\\.", -1);

    var path = Path.of(paths[0]);

    for (int i = 1; i < paths.length; ++i) {
      path = path.resolve(paths[i]);
    }

    return path.resolve(artifactId).normalize();
  }

  @Override
  public Resource getResource(final StoragePath storagePath, final String repoName) {

    return this.storageStrategy
        .get(storagePath, repoName)
        .orElseThrow(() -> new ItemNotFoundException(ERR_ITEM_NOT_FOUND));
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
      final UUID repoUuid, final Path artifactBasePath, final String repoName) {

    var freedBytes = 0L;

    for (final var fileName : METADATA_SIGNATURE_FAMILY) {
      final var storagePath =
          StoragePath.of(repoUuid, artifactBasePath.resolve(fileName).toString());

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

  private Path getPath(final String groupId, final String artifactId, final String versionName) {

    return this.getPath(groupId, artifactId).resolve(versionName).normalize();
  }

  /**
   * Calculate digest checksums of metadata file and write them into the checksum files that are
   * stored, none is created. Returns the change of the disk usage they caused, which a digest
   * rewritten with a longer or shorter algorithm output would leave out of the count.
   */
  private long updateChecksumsOfMetadata(
      final StoragePath metadataStoragePath, final Path artifactBasePath, final String repoName)
      throws IOException {

    final var repoUuid = metadataStoragePath.getStorageKey();

    final var resource =
        this.storageStrategy
            .get(metadataStoragePath, repoName)
            .orElseThrow(() -> new ItemNotFoundException(ERR_ITEM_NOT_FOUND));

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
              Objects.requireNonNull(repoUuid),
              artifactBasePath,
              repoName,
              metadataContent,
              entry.getKey(),
              entry.getValue());
    }

    return delta;
  }

  private long writeChecksumIfExists(
      final UUID repoUuid,
      final Path artifactBasePath,
      final String repoName,
      final String content,
      final String extension,
      final UnaryOperator<String> hashFunction)
      throws IOException {

    final var checksumFile = artifactBasePath.resolve(METADATA_FILENAME + "." + extension);

    final var storagePath = StoragePath.of(repoUuid, checksumFile.toString());

    final var optionalResource = this.storageStrategy.get(storagePath, repoName);

    if (optionalResource.isEmpty() || !optionalResource.get().exists()) {
      return 0L;
    }

    final var checksumBytes = hashFunction.apply(content).getBytes(StandardCharsets.UTF_8);

    try (final var checksumInputStream = new ByteArrayInputStream(checksumBytes)) {
      return this.storageStrategy.write(repoName, storagePath, checksumInputStream).getDiskUsage();
    }
  }

  @Override
  public void clearTrash() {

    final var unused = this.storageStrategy.clearTrash();
  }
}
