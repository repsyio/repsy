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

import io.repsy.core.events.ArtifactVersionDeletedEvent;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.generated.model.ReleaseDetail;
import io.repsy.os.server.protocols.pypi.shared.python_package.services.PypiPackageServiceImpl;
import io.repsy.os.server.protocols.pypi.shared.storage.services.PypiStorageService;
import io.repsy.os.server.protocols.shared.services.ProtocolApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.pypi.shared.utils.PackageUtils;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PypiApiFacade implements ProtocolApiFacade {

  private final @NonNull PypiStorageService pypiStorageService;
  private final @NonNull PypiPackageServiceImpl pypiPackageService;
  private final @NonNull ApplicationEventPublisher eventPublisher;

  @Override
  public void deleteRepo(final @NonNull RepoInfo repoInfo) {

    this.pypiStorageService.deleteRepo(repoInfo.getStorageKey());
  }

  /**
   * Deletes the package. The rows and the archives go in one transaction under the package's row
   * lock, see {@link PypiPackageServiceImpl#deletePackage}. The events follow the commit, so a
   * delete that rolled back reports nothing.
   */
  public @NonNull BaseUsages deletePackage(
      final @NonNull RepoInfo repoInfo, final @NonNull String packageName) {

    final var normalizedName = PackageUtils.normalizePackageName(packageName);

    final var deletion =
        this.pypiPackageService.deletePackage(repoInfo.getStorageKey(), normalizedName);

    this.publishVersionsDeleted(repoInfo, normalizedName, deletion.versions());

    return BaseUsages.builder().diskUsage(-1L * deletion.freedBytes()).build();
  }

  /** Deletes the release, and the package with it when it was the last one, like the above. */
  public @NonNull BaseUsages deleteRelease(
      final @NonNull RepoInfo repoInfo,
      final @NonNull String packageName,
      final @NonNull String version) {

    final var normalizedName = PackageUtils.normalizePackageName(packageName);

    final var deletion =
        this.pypiPackageService.deleteRelease(repoInfo.getStorageKey(), normalizedName, version);

    this.publishVersionsDeleted(repoInfo, normalizedName, deletion.versions());

    return BaseUsages.builder().diskUsage(-1L * deletion.freedBytes()).build();
  }

  private void publishVersionDeleted(
      final @NonNull RepoInfo repoInfo,
      final @NonNull String normalizedPackageName,
      final @NonNull String version) {

    this.eventPublisher.publishEvent(
        new ArtifactVersionDeletedEvent(
            repoInfo.getStorageKey(),
            repoInfo.getType().name(),
            repoInfo.getName(),
            normalizedPackageName,
            version));
  }

  private void publishVersionsDeleted(
      final @NonNull RepoInfo repoInfo,
      final @NonNull String normalizedPackageName,
      final @NonNull List<String> versions) {

    for (final var version : versions) {
      this.publishVersionDeleted(repoInfo, normalizedPackageName, version);
    }
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

  @Override
  public void createRepo(final @NonNull UUID repoId) {

    this.pypiStorageService.createRepo(repoId);
  }
}
