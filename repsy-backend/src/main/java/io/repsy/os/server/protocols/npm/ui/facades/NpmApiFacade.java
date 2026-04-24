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

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.generated.model.PackageVersionDetail;
import io.repsy.os.server.protocols.npm.shared.npm_package.mappers.NpmPackageConverter;
import io.repsy.os.server.protocols.npm.shared.npm_package.services.NpmPackageServiceImpl;
import io.repsy.os.server.protocols.npm.shared.storage.services.NpmStorageService;
import io.repsy.os.server.protocols.shared.services.ProtocolApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NpmApiFacade implements ProtocolApiFacade {

  private final @NonNull NpmPackageServiceImpl npmPackageService;
  private final @NonNull NpmStorageService npmStorageService;
  private final @NonNull NpmPackageConverter npmPackageConverter;

  public BaseUsages deleteRepo(final @NonNull RepoInfo repoInfo) {

    final var free = this.npmStorageService.deleteRepo(repoInfo.getStorageKey());

    return BaseUsages.ofDisk(-1 * free);
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

  public @NonNull BaseUsages deletePackage(
      final @NonNull RepoInfo repoInfo,
      final @Nullable String scopeName,
      final @NonNull String packageName) {

    final var packageInfo =
        this.npmPackageService.getPackage(repoInfo.getStorageKey(), scopeName, packageName);

    final var packageBasePath = this.npmStorageService.getPackageBasePath(scopeName, packageName);

    final var usage =
        this.npmStorageService.deletePackage(repoInfo.getStorageKey(), packageBasePath);

    final var usages = BaseUsages.builder().diskUsage(-1L * usage).build();

    this.npmPackageService.deletePackage(packageInfo.getId());

    return usages;
  }

  public @NonNull BaseUsages deletePackageVersion(
      final @NonNull RepoInfo repoInfo,
      final @Nullable String scopeName,
      final @NonNull String packageName,
      final @NonNull String versionName)
      throws IOException {

    if (this.npmPackageService.isLastVersion(repoInfo.getStorageKey(), scopeName, packageName)) {
      return this.deletePackage(repoInfo, scopeName, packageName);
    }

    final var packageBasePath = this.npmStorageService.getPackageBasePath(scopeName, packageName);

    final var pair =
        this.npmStorageService.deletePackageVersion(
            repoInfo.getStorageKey(),
            repoInfo.getName(),
            packageBasePath,
            packageName,
            versionName);

    this.npmPackageService.deletePackageVersion(
        repoInfo, scopeName, packageName, versionName, pair.getFirst());

    return BaseUsages.builder().diskUsage(pair.getSecond() * -1L).build();
  }

  public void createRepo(final @NonNull UUID repoId) {

    this.npmStorageService.createRepo(repoId);
  }
}
