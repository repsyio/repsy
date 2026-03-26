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
package io.repsy.protocols.pypi.shared.storage.services;

import static io.repsy.protocols.pypi.shared.utils.PackageStorageUtils.HASH_ALGORITHM;
import static io.repsy.protocols.pypi.shared.utils.PackageStorageUtils.isFileBelongsRelease;
import static java.nio.charset.StandardCharsets.UTF_8;

import freemarker.template.Configuration;
import freemarker.template.TemplateException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.pypi.shared.python_package.dtos.PackageUploadForm;
import io.repsy.protocols.pypi.shared.python_package.dtos.ReleaseArchiveIndexListItem;
import io.repsy.protocols.pypi.shared.utils.PackageStorageUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.ui.freemarker.FreeMarkerTemplateUtils;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RequiredArgsConstructor
@NullMarked
public abstract class AbstractPypiStorageService<ID> implements PypiStorageService<ID> {

  private static final String PATH_DELIMITER = "/";

  private final StorageStrategy storageStrategy;
  private final Configuration freeMarkerConfiguration;

  protected abstract String buildRepoUri(BaseRepoInfo<ID> baseRepoInfo);

  /** mark package directory as deleted ands return its usage */
  @Override
  public long deletePackage(final UUID repoUuid, final String packageNormalizedName) {

    final var storagePath = StoragePath.of(repoUuid, packageNormalizedName);

    final var usage = this.storageStrategy.calculatePathUsage(storagePath);

    this.storageStrategy.deleteDirectory(storagePath);

    return usage;
  }

  /** mark archive files belongs to given release version as deleted and return total usage */
  @Override
  public long deleteRelease(
      final UUID repoUuid, final String packageNormalizedName, final String releaseVersion) {

    final var normalizedPackagePath =
        packageNormalizedName.endsWith("/")
            ? packageNormalizedName
            : packageNormalizedName + PATH_DELIMITER;

    final var storagePath = StoragePath.of(repoUuid, normalizedPackagePath);

    final Predicate<StorageItemInfo> predicate =
        si -> isFileBelongsRelease(si.getName(), releaseVersion);

    final var archiveResources =
        this.storageStrategy.listDirectoryContents(storagePath).stream().filter(predicate).toList();

    if (archiveResources.isEmpty()) {
      return 0L;
    }

    long totalUsage = 0;

    for (final var archiveResource : archiveResources) {
      totalUsage += archiveResource.getSize();

      final var normalizedPath =
          Paths.get(packageNormalizedName, archiveResource.getName()).toString();

      final var sp = StoragePath.of(repoUuid, normalizedPath);

      this.storageStrategy.delete(sp);
    }

    return totalUsage;
  }

  @Override
  public Resource getArchiveFile(
      final UUID repoUuid, final String repoName, final String packageName, final String fileName) {

    final var storagePath = StoragePath.of(repoUuid, Paths.get(packageName, fileName).toString());

    return this.storageStrategy
        .get(storagePath, repoName)
        .orElseThrow(() -> new ItemNotFoundException("itemNotFound"));
  }

  private void addDirectoryUpLink(
      final String relativePath, final List<StorageItemInfo> directoryList) {

    if (!relativePath.isEmpty() && !relativePath.equals("/")) {
      directoryList.addFirst(StorageItemInfo.builder().name("../").directory(true).build());
    }
  }

  @Override
  public ByteArrayResource getPackageArchiveFileList(
      final BaseRepoInfo<ID> repoInfo,
      final String packageName,
      final Map<String, String> versionAndRequiresPythonMap)
      throws IOException, TemplateException {

    final var normalizedPath =
        packageName.endsWith(PATH_DELIMITER) ? packageName : packageName + PATH_DELIMITER;
    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), normalizedPath);

    final var storageItemInfoList = this.storageStrategy.listDirectoryContents(storagePath);

    if (!storageItemInfoList.isEmpty()) {
      this.addDirectoryUpLink(storagePath.getRelativePath().getPath(), storageItemInfoList);
    }

    final var archiveFiles =
        this.getReleaseArchiveIndexListItem(
            packageName, repoInfo, versionAndRequiresPythonMap, storageItemInfoList);

    final var template = this.freeMarkerConfiguration.getTemplate("package-versions.ftl");

    return new ByteArrayResource(
        FreeMarkerTemplateUtils.processTemplateIntoString(
                template,
                Map.of(
                    "archiveFiles",
                    archiveFiles,
                    "hashAlgorithm",
                    HASH_ALGORITHM,
                    "packageName",
                    packageName,
                    "repoUri",
                    this.buildRepoUri(repoInfo)))
            .getBytes(UTF_8));
  }

  private List<ReleaseArchiveIndexListItem> getReleaseArchiveIndexListItem(
      final String packageName,
      final BaseRepoInfo<ID> repoInfo,
      final Map<String, String> versionAndRequiresPythonMap,
      final List<StorageItemInfo> storageItemInfoList)
      throws IOException {

    if (storageItemInfoList.isEmpty()) {
      return List.of();
    }

    final var archiveFiles = new ArrayList<ReleaseArchiveIndexListItem>();

    for (final var si : storageItemInfoList) {
      final var filename = si.getName();

      if (si.isDirectory() || filename.endsWith(HASH_ALGORITHM)) {
        continue;
      }

      final var version = PackageStorageUtils.extractVersionFromArchiveFilename(filename);

      final var fileStoragePath =
          StoragePath.of(
              repoInfo.getStorageKey(),
              Paths.get(packageName, filename + "." + HASH_ALGORITHM).toString());

      final var resource =
          this.storageStrategy
              .get(fileStoragePath, repoInfo.getName())
              .orElseThrow(() -> new ItemNotFoundException("itemNotFound"));

      final var item =
          ReleaseArchiveIndexListItem.builder()
              .filename(filename)
              .fileHash(resource.getContentAsString(UTF_8))
              .build();

      if (version != null) {
        item.setRequiresPython(versionAndRequiresPythonMap.get(version));
      } else {
        log.warn(
            "Cannot extract release version from archive filename." + "\n Filename is {}",
            filename);
      }

      archiveFiles.add(item);
    }

    return archiveFiles;
  }

  /** mark repo directory as deleted and return usage */
  @Override
  public long deleteRepo(final UUID repoUuid) {

    final var storagePath = StoragePath.of(repoUuid);

    final var usage = this.storageStrategy.calculatePathUsage(storagePath);

    this.storageStrategy.deleteDirectory(storagePath);

    return usage;
  }

  @Override
  public void createRepo(final UUID repoUuid) {

    this.storageStrategy.createDirectory(repoUuid.toString());
  }

  @Override
  public void clearTrash() {

    this.storageStrategy.clearTrash();
  }

  @Override
  public boolean isPackageFileExist(
      final UUID repoId, final String normalizedName, final String version, final String filename) {

    if (!PackageStorageUtils.isFileBelongsRelease(filename, version)) {
      return false;
    }

    final var path = StoragePath.of(repoId, Paths.get(normalizedName, filename).toString());

    return this.storageStrategy.get(path, normalizedName).isPresent();
  }

  /** Write archive file to fs, if it does not exists already */
  @Override
  public BaseUsages writePackageArchive(
      final UUID repoId,
      final String repoName,
      final PackageUploadForm uploadForm,
      final MultipartFile file)
      throws IOException {

    final var path = Paths.get(repoId.toString(), uploadForm.getNormalizedName()).toString();
    this.storageStrategy.createDirectory(path);

    final var storagePath =
        StoragePath.of(
            repoId,
            Paths.get(uploadForm.getNormalizedName(), file.getOriginalFilename()).toString());

    final var packageUsage =
        this.storageStrategy.write(repoName, storagePath, file.getInputStream());

    final var digestPath =
        Paths.get(uploadForm.getNormalizedName(), file.getOriginalFilename() + "." + HASH_ALGORITHM)
            .toString();

    final var digestStoragePath = StoragePath.of(repoId, digestPath);

    final var uploadFormSha256DigestBytes = uploadForm.getSha256_digest().getBytes(UTF_8);

    try (final var bais = new ByteArrayInputStream(uploadFormSha256DigestBytes)) {
      final var metadataUsage = this.storageStrategy.write(repoName, digestStoragePath, bais);

      packageUsage.setDiskUsage(packageUsage.getDiskUsage() + metadataUsage.getDiskUsage());
    }

    return packageUsage;
  }
}
