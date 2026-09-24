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
package io.repsy.os.server.protocols.npm.ui.facades;

import io.repsy.core.events.ArtifactVersionDeletedEvent;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.generated.model.PackageVersionDetail;
import io.repsy.os.server.protocols.npm.shared.npm_package.mappers.NpmPackageConverter;
import io.repsy.os.server.protocols.npm.shared.npm_package.services.NpmPackageServiceImpl;
import io.repsy.os.server.protocols.npm.shared.storage.services.NpmStorageService;
import io.repsy.os.server.protocols.shared.services.ProtocolApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import java.io.IOException;
import java.nio.file.Path;
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
public class NpmApiFacade implements ProtocolApiFacade {

  private final @NonNull NpmPackageServiceImpl npmPackageService;
  private final @NonNull NpmStorageService npmStorageService;
  private final @NonNull NpmPackageConverter npmPackageConverter;
  private final @NonNull ApplicationEventPublisher eventPublisher;

  @Override
  public void deleteRepo(final @NonNull RepoInfo repoInfo) {

    this.npmStorageService.deleteRepo(repoInfo.getStorageKey());
  }

  public @NonNull PackageVersionDetail getVersion(
      final @NonNull RepoInfo repoInfo,
      final @Nullable String scopeName,
      final @NonNull String packageName,
      final @Nullable String versionName)
      throws IOException {

    final var packageInfo =
        this.npmPackageService.getPackage(repoInfo.getStorageKey(), scopeName, packageName);

    var packageVersionName = versionName;

    if (versionName == null) {
      packageVersionName = packageInfo.getLatest();
    }

    final var packageVersionInfo =
        this.npmPackageService.getPackageVersion(packageInfo.getId(), packageVersionName);

    final var keywords = this.npmPackageService.getKeywords(packageVersionInfo.getId());

    final var maintainers = this.npmPackageService.getMaintainers(packageVersionInfo.getId());

    final var distributionTags =
        this.npmPackageService.getDistributionTagsOfVersion(packageVersionInfo.getId());

    final var packageBasePath = this.npmStorageService.getPackageBasePath(scopeName, packageName);

    final var readmeFileContent =
        this.npmStorageService.getReadmeContent(
            repoInfo.getStorageKey(), repoInfo.getName(), packageBasePath, packageVersionName);

    return this.npmPackageConverter.toPackageVersionDetail(
        packageInfo,
        packageVersionInfo,
        keywords,
        maintainers,
        distributionTags,
        readmeFileContent);
  }

  /**
   * Deletes the package. The rows and the files go in one transaction under the package's row lock,
   * see {@link NpmPackageServiceImpl#deletePackage}. The events follow the commit, so a delete that
   * rolled back reports nothing.
   */
  public @NonNull BaseUsages deletePackage(
      final @NonNull RepoInfo repoInfo,
      final @Nullable String scopeName,
      final @NonNull String packageName) {

    final var packageBasePath = this.npmStorageService.getPackageBasePath(scopeName, packageName);

    final var deletion =
        this.npmPackageService.deletePackage(
            repoInfo,
            scopeName,
            packageName,
            () -> this.removePackageFiles(repoInfo, packageBasePath));

    this.publishVersionsDeleted(repoInfo, scopeName, packageName, deletion.versions());

    return deletion.usages();
  }

  /**
   * Deletes the version, and the package with it when that was its last version, like {@link
   * #deletePackage}: see {@link NpmPackageServiceImpl#deletePackageVersion}.
   */
  public @NonNull BaseUsages deletePackageVersion(
      final @NonNull RepoInfo repoInfo,
      final @Nullable String scopeName,
      final @NonNull String packageName,
      final @NonNull String versionName)
      throws IOException {

    final var packageBasePath = this.npmStorageService.getPackageBasePath(scopeName, packageName);

    // Deleting the last version deletes the package: the events then name that one version, which
    // is all the package had.
    final var deletion =
        this.npmPackageService.deletePackageVersion(
            repoInfo,
            scopeName,
            packageName,
            versionName,
            newLatest ->
                BaseUsages.ofDisk(
                    this.npmStorageService.removeVersion(
                        repoInfo.getStorageKey(),
                        repoInfo.getName(),
                        packageBasePath,
                        packageName,
                        versionName,
                        newLatest,
                        () ->
                            this.npmPackageService.getSnapshot(
                                repoInfo.getStorageKey(), scopeName, packageName))),
            () -> this.removePackageFiles(repoInfo, packageBasePath));

    this.publishVersionsDeleted(repoInfo, scopeName, packageName, deletion.versions());

    return deletion.usages();
  }

  private @NonNull BaseUsages removePackageFiles(
      final @NonNull RepoInfo repoInfo, final @NonNull Path packageBasePath) {

    return BaseUsages.ofDisk(
        -1L * this.npmStorageService.deletePackage(repoInfo.getStorageKey(), packageBasePath));
  }

  @Override
  public void createRepo(final @NonNull UUID repoId) {

    this.npmStorageService.createRepo(repoId);
  }

  private void publishVersionDeleted(
      final @NonNull RepoInfo repoInfo,
      final @Nullable String scopeName,
      final @NonNull String packageName,
      final @NonNull String versionName) {

    this.eventPublisher.publishEvent(
        new ArtifactVersionDeletedEvent(
            repoInfo.getStorageKey(),
            repoInfo.getType().name(),
            repoInfo.getName(),
            buildArtifactName(scopeName, packageName),
            versionName));
  }

  private void publishVersionsDeleted(
      final @NonNull RepoInfo repoInfo,
      final @Nullable String scopeName,
      final @NonNull String packageName,
      final @NonNull List<String> versionNames) {

    for (final var versionName : versionNames) {
      this.publishVersionDeleted(repoInfo, scopeName, packageName, versionName);
    }
  }

  private static @NonNull String buildArtifactName(
      final @Nullable String scopeName, final @NonNull String packageName) {
    return scopeName == null ? packageName : "@" + scopeName + "/" + packageName;
  }
}
