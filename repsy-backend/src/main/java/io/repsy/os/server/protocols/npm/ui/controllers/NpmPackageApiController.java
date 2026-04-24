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
package io.repsy.os.server.protocols.npm.ui.controllers;

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.generated.model.PackageListItem;
import io.repsy.os.generated.model.PackageVersionDetail;
import io.repsy.os.generated.model.PackageVersionListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.services.NpmPackageServiceImpl;
import io.repsy.os.server.protocols.npm.ui.facades.NpmApiFacade;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.npm.shared.npm_package.dtos.PackageDistributionTagMapListItem;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/npm/packages")
@NullMarked
@SuppressWarnings("java:S6856")
public class NpmPackageApiController {

  private static final String PACKAGES_FETCHED = "packagesFetched";

  private final UsageUpdateService usageUpdateService;
  private final NpmPackageServiceImpl npmPackageService;
  private final NpmApiFacade npmFacade;
  private final RestResponseFactory restResponseFactory;

  @DeleteMapping({
    "/{repoName}/{packageName}",
    "/{repoName}/{scope}/{packageName}",
  })
  @RepoOperation(permission = Permission.MANAGE)
  public RestResponse<Void> delete(
      final RepoInfo repoInfo,
      @PathVariable(required = false) final @Nullable String scope,
      @PathVariable final String packageName) {

    final var usages = this.npmFacade.deletePackage(repoInfo, scope, packageName);

    this.updateUsage(repoInfo, usages);

    return this.restResponseFactory.success("packageDeleted");
  }

  @DeleteMapping({
    "/{repoName}/{packageName}/versions/{versionName}",
    "/{repoName}/{scope}/{packageName}/versions/{versionName}",
  })
  @RepoOperation(permission = Permission.MANAGE)
  public RestResponse<Void> deleteVersion(
      final RepoInfo repoInfo,
      @PathVariable(required = false) final @Nullable String scope,
      @PathVariable final String packageName,
      @PathVariable final String versionName)
      throws IOException {

    final var usages =
        this.npmFacade.deletePackageVersion(repoInfo, scope, packageName, versionName);

    this.updateUsage(repoInfo, usages);

    return this.restResponseFactory.success("packageVersionDeleted");
  }

  @GetMapping("/{repoName}")
  @RepoOperation
  public RestResponse<PagedModel<PackageListItem>> list(
      final RepoInfo repoInfo,
      @RequestParam(required = false) final @Nullable String scope,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    final var packages =
        this.npmPackageService.getPackagesContainsScope(repoInfo.getStorageKey(), scope, pageable);

    return this.restResponseFactory.success(PACKAGES_FETCHED, new PagedModel<>(packages));
  }

  @GetMapping("/{repoName}/{ignoredScope}")
  @RepoOperation
  public RestResponse<PagedModel<PackageListItem>> list(
      final RepoInfo repoInfo,
      @PathVariable final String ignoredScope,
      @RequestParam(required = false, defaultValue = "") final String name,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    final var packages =
        this.npmPackageService.getPackagesContainsName(repoInfo.getStorageKey(), name, pageable);

    return this.restResponseFactory.success(PACKAGES_FETCHED, new PagedModel<>(packages));
  }

  @GetMapping({
    "/{repoName}/scope",
    "/{repoName}/scope/{scope}",
  })
  @RepoOperation
  public RestResponse<PagedModel<PackageListItem>> listFilterByScope(
      final RepoInfo repoInfo,
      @PathVariable(required = false) final @Nullable String scope,
      @RequestParam(required = false, defaultValue = "") final String name,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    final var packages =
        this.npmPackageService.getPackagesByScopeContainsName(
            repoInfo.getStorageKey(), scope, name, pageable);

    return this.restResponseFactory.success(PACKAGES_FETCHED, new PagedModel<>(packages));
  }

  @GetMapping({
    "/{repoName}/{packageName}",
    "/{repoName}/{scope}/{packageName}",
    "/{repoName}/{packageName}/versions/{versionName}",
    "/{repoName}/{scope}/{packageName}/versions/{versionName}",
  })
  @RepoOperation
  public RestResponse<PackageVersionDetail> getVersion(
      final RepoInfo repoInfo,
      @PathVariable(required = false) final @Nullable String scope,
      @PathVariable final String packageName,
      @PathVariable final @Nullable String versionName)
      throws IOException {

    final var version = this.npmFacade.getVersion(repoInfo, scope, packageName, versionName);

    return this.restResponseFactory.success("packageVersionFetched", version);
  }

  @GetMapping({
    "/{repoName}/package/{packageName}/versions",
    "/{repoName}/{scope}/package/{packageName}/versions",
  })
  @RepoOperation
  public RestResponse<PagedModel<PackageVersionListItem>> listVersions(
      final RepoInfo repoInfo,
      @PathVariable(required = false) final @Nullable String scope,
      @PathVariable final String packageName,
      @RequestParam(required = false, defaultValue = "") final String version,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    final var versions =
        this.npmPackageService.getVersionsContainsVersion(
            repoInfo.getStorageKey(), scope, packageName, version, pageable);

    return this.restResponseFactory.success("packageVersionsFetched", new PagedModel<>(versions));
  }

  @GetMapping({
    "/{repoName}/package/{packageName}/tags",
    "/{repoName}/{scope}/package/{packageName}/tags",
  })
  @RepoOperation
  public RestResponse<List<PackageDistributionTagMapListItem>> listTags(
      final RepoInfo repoInfo,
      @PathVariable(required = false) final @Nullable String scope,
      @PathVariable final String packageName) {

    final var tags =
        this.npmPackageService.getDistributionTags(repoInfo.getStorageKey(), scope, packageName);

    return this.restResponseFactory.success("packageTagsFetched", tags);
  }

  private void updateUsage(final RepoInfo repoInfo, final BaseUsages usages) {

    final var usageUpdatedInfo = new UsageChangedInfo(repoInfo.getStorageKey(), usages);

    this.usageUpdateService.updateUsage(usageUpdatedInfo);
  }
}
