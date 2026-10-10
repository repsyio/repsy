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
import io.repsy.protocols.maven.shared.artifact.dtos.PluginPrefixChange;
import io.repsy.protocols.maven.shared.artifact.dtos.RegisteredPlugin;
import io.repsy.protocols.maven.shared.storage.MavenMetadataStore;
import io.repsy.protocols.maven.shared.utils.MavenStoragePathUtils;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.SneakyThrows;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.ui.freemarker.FreeMarkerTemplateUtils;

@NullMarked
public abstract class AbstractMavenStorageService<ID> implements MavenStorageService<ID> {

  private static final String METADATA_FILENAME = "maven-metadata.xml";

  private final Configuration freeMarkerConfiguration;
  private final StorageStrategy storageStrategy;

  /** Rewrites the stored {@code maven-metadata.xml} files and guards them with its locks. */
  private final MavenMetadataStore<ID> metadataStore;

  protected AbstractMavenStorageService(
      final Configuration freeMarkerConfiguration, final StorageStrategy storageStrategy) {

    this.freeMarkerConfiguration = freeMarkerConfiguration;
    this.storageStrategy = storageStrategy;
    this.metadataStore = new MavenMetadataStore<>(storageStrategy);
  }

  @Override
  public void createRepo(final UUID repoId) {

    this.storageStrategy.createDirectory(repoId.toString());
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
          .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.ITEM_NOT_FOUND));

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

    return this.metadataStore.write(storagePath, inputStream, repoName);
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
  public long deleteArtifact(final UUID repoId, final String groupId, final String artifactId) {

    final var artifactPath = this.getPath(groupId, artifactId);
    final var storagePath = StoragePath.of(repoId, artifactPath.toString());
    final var usage = this.storageStrategy.calculatePathUsage(storagePath);

    this.storageStrategy.delete(storagePath);

    return usage;
  }

  @Override
  public long deleteArtifactVersion(
      final UUID repoId, final String groupId, final String artifactId, final String versionName) {

    final var versionPath = this.getPath(groupId, artifactId, versionName);
    final var storagePath = StoragePath.of(repoId, versionPath.toString());

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
      final UUID repoId, final String groupId, final List<String> artifactNames) {

    final var groupPaths = MavenStoragePathUtils.groupPaths(groupId);
    final var groupPath = groupPaths[0];

    var usage = this.deleteGroupLevelMetadataFiles(repoId, groupPath);

    for (final var artifactName : artifactNames) {
      final var artifactStoragePath =
          StoragePath.of(repoId, groupPath.resolve(artifactName).normalize().toString());

      usage += this.storageStrategy.calculatePathUsage(artifactStoragePath);
      this.storageStrategy.delete(artifactStoragePath);
    }

    // Prune this group's own directory and its now-empty ancestors, stopping at the first one that
    // still has content — a nested sibling group's directory, or anything else this group's
    // deletion has no business touching.
    for (final var path : groupPaths) {
      final var sp = StoragePath.of(repoId, path + "/");

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
  private long deleteGroupLevelMetadataFiles(final UUID repoId, final Path groupPath) {

    final var groupStoragePath = StoragePath.of(repoId, groupPath + "/");

    var usage = 0L;

    for (final var item : this.listDirectoryOrEmpty(groupStoragePath)) {
      if (item.isDirectory() || !item.getName().startsWith(METADATA_FILENAME)) {
        continue;
      }

      final var itemStoragePath =
          StoragePath.of(repoId, groupPath.resolve(item.getName()).toString());

      usage += item.getSize() == null ? 0L : item.getSize();
      this.storageStrategy.delete(itemStoragePath);
    }

    return usage;
  }

  @Override
  public void deleteRepo(final UUID repoId) {
    final var storagePath = StoragePath.of(repoId);
    this.storageStrategy.delete(storagePath);
  }

  @Override
  public BaseUsages deleteVersionFromMetadata(
      final BaseRepoInfo<ID> repoInfo,
      final String groupId,
      final String artifactId,
      final String versionName)
      throws IOException, XmlPullParserException {

    return this.metadataStore.deleteVersionFromMetadata(repoInfo, groupId, artifactId, versionName);
  }

  @Override
  public long addVersionsToMetadata(
      final BaseRepoInfo<ID> repoInfo,
      final String groupId,
      final String artifactId,
      final Supplier<? extends Collection<String>> registeredVersions)
      throws IOException {

    return this.metadataStore.addVersionsToMetadata(
        repoInfo, groupId, artifactId, registeredVersions);
  }

  @Override
  public long addPluginsToGroupMetadata(
      final BaseRepoInfo<ID> repoInfo,
      final String groupId,
      final Supplier<? extends Collection<RegisteredPlugin>> registeredPlugins)
      throws IOException {

    return this.metadataStore.addPluginsToGroupMetadata(repoInfo, groupId, registeredPlugins);
  }

  @Override
  public long replacePluginPrefixInGroupMetadata(
      final BaseRepoInfo<ID> repoInfo, final String groupId, final PluginPrefixChange change)
      throws IOException {

    return this.metadataStore.replacePluginPrefixInGroupMetadata(repoInfo, groupId, change);
  }

  @Override
  public Path getPath(final String groupId, final String artifactId) {

    return MavenStoragePathUtils.artifactPath(groupId, artifactId);
  }

  @Override
  public Resource getResource(final StoragePath storagePath, final String repoName) {

    return this.storageStrategy
        .get(storagePath, repoName)
        .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.ITEM_NOT_FOUND));
  }

  private Path getPath(final String groupId, final String artifactId, final String versionName) {

    return this.getPath(groupId, artifactId).resolve(versionName).normalize();
  }

  @Override
  public void clearTrash() {

    final var unused = this.storageStrategy.clearTrash();
  }
}
