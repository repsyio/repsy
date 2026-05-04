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
package io.repsy.os.server.protocols.nuget.ui.facades;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.generated.model.NuGetPackageInfo;
import io.repsy.os.generated.model.NuGetPackageListItem;
import io.repsy.os.generated.model.NuGetVersionListItem;
import io.repsy.os.server.protocols.nuget.shared.packages.dtos.NuGetDeletedItem;
import io.repsy.os.server.protocols.nuget.shared.packages.services.NuGetPackageServiceImpl;
import io.repsy.os.server.protocols.nuget.shared.storage.NuGetStorageService;
import io.repsy.os.server.protocols.shared.services.ProtocolApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetVersionInfo;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service("nugetApiFacade")
@Transactional
@RequiredArgsConstructor
@NullMarked
public class NuGetApiFacade implements ProtocolApiFacade {

  private final NuGetPackageServiceImpl nugetPackageService;
  private final NuGetStorageService nugetStorageService;

  @Override
  public void createRepo(final UUID repoId) {
    this.nugetStorageService.createRepo(repoId);
  }

  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public BaseUsages deleteRepo(final RepoInfo repoInfo) throws IOException {
    final long freed = this.nugetStorageService.deleteRepo(repoInfo.getId());
    return BaseUsages.builder().diskUsage(-1L * freed).build();
  }

  @Transactional(readOnly = true)
  public Page<NuGetPackageListItem> search(
      final RepoInfo repoInfo, final String query, final Pageable pageable) {

    return this.nugetPackageService
        .search(repoInfo, query, 0, pageable.getPageSize(), false)
        .map(this::toPackageListItem);
  }

  @Transactional(readOnly = true)
  public NuGetPackageInfo getPackage(final RepoInfo repoInfo, final String packageId) {
    final var versions = this.nugetPackageService.getVersionInfos(repoInfo, packageId);

    if (versions.isEmpty()) {
      throw new ItemNotFoundException("packageNotFound");
    }

    final var latest = versions.getFirst();
    final long totalDownloads = versions.stream().mapToLong(NuGetVersionInfo::downloadCount).sum();

    return new NuGetPackageInfo()
        .packageId(latest.packageId())
        .title(latest.title())
        .description(latest.description())
        .authors(latest.authors())
        .tags(latest.tags())
        .iconUrl(latest.iconUrl())
        .licenseUrl(latest.licenseUrl())
        .projectUrl(latest.projectUrl())
        .latestVersion(latest.version())
        .totalDownloads(totalDownloads);
  }

  @Transactional(readOnly = true)
  public Page<NuGetVersionListItem> getVersions(
      final RepoInfo repoInfo, final String packageId, final Pageable pageable) {

    return this.nugetPackageService
        .getVersionInfosPage(repoInfo, packageId, pageable)
        .map(this::toVersionListItem);
  }

  @Transactional(readOnly = true)
  public io.repsy.os.generated.model.NuGetVersionInfo getVersion(
      final RepoInfo repoInfo, final String packageId, final String version) {

    return this.nugetPackageService
        .findVersionInfo(repoInfo, packageId, version)
        .map(this::toVersionInfo)
        .orElseThrow(() -> new ItemNotFoundException("versionNotFound"));
  }

  public BaseUsages deletePackage(final RepoInfo repoInfo, final String packageId) {
    this.nugetPackageService.deletePackage(repoInfo, packageId);
    long freed = 0L;
    try {
      freed = this.nugetStorageService.deletePackage(repoInfo.getId(), packageId.toLowerCase());
    } catch (final Exception e) {
      log.warn("Storage delete failed for NuGet package {}: {}", packageId, e.getMessage());
    }
    return BaseUsages.builder().diskUsage(-1L * freed).build();
  }

  public record NuGetDeleteVersionResult(NuGetDeletedItem deletedItem, BaseUsages usages) {}

  public NuGetDeleteVersionResult deleteVersion(
      final RepoInfo repoInfo, final String packageId, final String version) {

    final var deletedItem =
        this.nugetPackageService.deleteVersionAndGetDeletedItem(repoInfo, packageId, version);

    long freed = 0L;

    try {
      if (deletedItem == NuGetDeletedItem.PACKAGE) {
        // Keep usage accounting aligned with publish/write operations by freeing only version path
        // usage.
        freed =
            this.nugetStorageService.deletePackageVersion(
                repoInfo.getId(), packageId.toLowerCase(), version.toLowerCase());
        try {
          // Remove possibly empty package directory without affecting usage delta.
          this.nugetStorageService.deletePackage(repoInfo.getId(), packageId.toLowerCase());
        } catch (final Exception cleanupException) {
          log.debug(
              "NuGet package directory cleanup skipped for {}: {}",
              packageId,
              cleanupException.getMessage());
        }
      } else {
        freed =
            this.nugetStorageService.deletePackageVersion(
                repoInfo.getId(), packageId.toLowerCase(), version.toLowerCase());
      }
    } catch (final Exception e) {
      log.warn(
          "Storage delete failed for NuGet package {} version {}: {}",
          packageId,
          version,
          e.getMessage());
    }
    return new NuGetDeleteVersionResult(
        deletedItem, BaseUsages.builder().diskUsage(-1L * freed).build());
  }

  private NuGetPackageListItem toPackageListItem(
      final io.repsy.protocols.nuget.shared.packages.dtos.NuGetPackageSearchResult result) {

    return new NuGetPackageListItem()
        .packageId(result.packageId())
        .latestVersion(result.latestVersion())
        .description(result.description())
        .totalDownloads(result.totalDownloads());
  }

  private NuGetVersionListItem toVersionListItem(final NuGetVersionInfo v) {
    return new NuGetVersionListItem()
        .version(v.version())
        .publishedAt(v.publishedAt())
        .downloads(v.downloadCount())
        .prerelease(v.version().contains("-"))
        .listed(v.listed());
  }

  private io.repsy.os.generated.model.NuGetVersionInfo toVersionInfo(final NuGetVersionInfo v) {
    return new io.repsy.os.generated.model.NuGetVersionInfo()
        .packageId(v.packageId())
        .version(v.version())
        .title(v.title())
        .description(v.description())
        .authors(v.authors())
        .tags(v.tags())
        .iconUrl(v.iconUrl())
        .licenseUrl(v.licenseUrl())
        .projectUrl(v.projectUrl())
        .listed(v.listed())
        .downloadCount(v.downloadCount())
        .publishedAt(v.publishedAt())
        .dependencies(
            v.dependencies() == null
                ? java.util.List.of()
                : v.dependencies().stream()
                    .map(
                        d ->
                            new io.repsy.os.generated.model.NuGetDependencyInfo()
                                .packageId(d.packageId())
                                .versionRange(d.versionRange())
                                .targetFramework(d.targetFramework()))
                    .toList());
  }
}
