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
package io.repsy.os.server.protocols.pypi.ui.facades;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.generated.model.ReleaseDetail;
import io.repsy.os.server.protocols.pypi.shared.python_package.services.PypiPackageServiceImpl;
import io.repsy.os.server.protocols.pypi.shared.storage.services.PypiStorageService;
import io.repsy.os.server.protocols.shared.services.ProtocolApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.pypi.shared.utils.PackageUtils;
import io.repsy.protocols.pypi.shared.utils.ReleaseVersion;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PypiApiFacade implements ProtocolApiFacade {

  private final @NonNull PypiStorageService pypiStorageService;
  private final @NonNull PypiPackageServiceImpl pypiPackageService;

  public BaseUsages deleteRepo(final @NonNull RepoInfo repoInfo) {

    final var free = this.pypiStorageService.deleteRepo(repoInfo.getStorageKey());

    return BaseUsages.ofDisk(-1 * free);
  }

  public @NonNull BaseUsages deletePackage(
      final @NonNull RepoInfo repoInfo, final @NonNull String packageName) {

    final var packageInfo =
        this.pypiPackageService.getPackage(
            repoInfo.getStorageKey(), PackageUtils.normalizePackageName(packageName));

    this.pypiPackageService.deletePackage(
        repoInfo.getStorageKey(), packageInfo.getNormalizedName());

    final var usage =
        this.pypiStorageService.deletePackage(
            repoInfo.getStorageKey(), packageInfo.getNormalizedName());

    return BaseUsages.builder().diskUsage(-1L * usage).build();
  }

  public @NonNull BaseUsages deleteRelease(
      final @NonNull RepoInfo repoInfo,
      final @NonNull String packageName,
      final @NonNull String version) {

    final var packageInfo =
        this.pypiPackageService.getPackage(
            repoInfo.getStorageKey(), PackageUtils.normalizePackageName(packageName));

    final var releaseVersion = ReleaseVersion.of(version);

    this.pypiPackageService.deleteRelease(packageInfo.getId(), releaseVersion.getVersion());

    final var isHasNoReleases = this.pypiPackageService.isPackageHasNoReleases(packageInfo.getId());

    final var usage = BaseUsages.builder().build();

    if (isHasNoReleases) {
      this.pypiPackageService.deletePackage(
          repoInfo.getStorageKey(), packageInfo.getNormalizedName());

      usage.setDiskUsage(
          -1L
              * this.pypiStorageService.deletePackage(
                  repoInfo.getStorageKey(), packageInfo.getNormalizedName()));

    } else {
      usage.setDiskUsage(
          -1L
              * this.pypiStorageService.deleteRelease(
                  repoInfo.getStorageKey(),
                  packageInfo.getNormalizedName(),
                  releaseVersion.getVersion()));

      this.pypiPackageService.updatePackageReleaseVersionsIfNecessary(
          packageInfo, releaseVersion.getVersion());
    }

    return usage;
  }

  public @NonNull ReleaseDetail getReleaseDetail(
      final @NonNull UUID repoId,
      final @NonNull String packageName,
      final @Nullable String releaseVersion) {

    final var packageInfo =
        this.pypiPackageService.getPackage(repoId, PackageUtils.normalizePackageName(packageName));

    final ReleaseDetail releaseDetail;

    if (releaseVersion == null) {
      releaseDetail =
          this.pypiPackageService.getReleaseDetail(
              packageInfo.getId(), packageInfo.getLatestVersion());
    } else {
      releaseDetail = this.pypiPackageService.getReleaseDetail(packageInfo.getId(), releaseVersion);
    }

    releaseDetail.setPackageName(packageInfo.getName());
    releaseDetail.setStableVersion(packageInfo.getStableVersion());

    return releaseDetail;
  }

  public void createRepo(final @NonNull UUID repoId) {

    this.pypiStorageService.createRepo(repoId);
  }
}
