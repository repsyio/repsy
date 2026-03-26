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

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.server.protocols.npm.shared.auth.services.NpmAuthComponentImpl;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageVersionDetail;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageVersionListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.services.NpmPackageServiceImpl;
import io.repsy.os.server.protocols.npm.ui.facades.NpmApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.npm.shared.npm_package.dtos.PackageDistributionTagMapListItem;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/npm/packages")
public class NpmPackageApiController {

  private static final @NonNull String PACKAGES_FETCHED = "packagesFetched";

  private final @NonNull NpmAuthComponentImpl npmAuthComponent;
  private final @NonNull UsageUpdateService usageUpdateService;
  private final @NonNull RepoTxService repoTxService;
  private final @NonNull NpmPackageServiceImpl npmPackageService;
  private final @NonNull NpmApiFacade npmFacade;
  private final @NonNull RestResponseFactory restResponseFactory;

  @DeleteMapping({
    "/{repoName}/{packageName}",
    "/{repoName}/{scope}/{packageName}",
  })
  public @NonNull RestResponse<Void> deletePackage(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable(required = false) final @Nullable String scope,
      @PathVariable final @NonNull String packageName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.NPM);

    this.npmAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    final var usages = this.npmFacade.deletePackage(repoInfo, scope, packageName);

    this.updateUsage(repoInfo, usages);

    return this.restResponseFactory.success("packageDeleted");
  }

  @DeleteMapping({
    "/{repoName}/{packageName}/version/{versionName}",
    "/{repoName}/{scope}/{packageName}/version/{versionName}",
  })
  public @NonNull RestResponse<Void> deletePackageVersion(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable(required = false) final @Nullable String scope,
      @PathVariable final @NonNull String packageName,
      @PathVariable final @NonNull String versionName)
      throws IOException {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.NPM);

    this.npmAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    final var usages =
        this.npmFacade.deletePackageVersion(repoInfo, scope, packageName, versionName);

    this.updateUsage(repoInfo, usages);

    return this.restResponseFactory.success("packageVersionDeleted");
  }

  @GetMapping("/{repoName}/search")
  public @NonNull RestResponse<PagedModel<PackageListItem>> getPackages(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestParam(required = false) final @Nullable String scope,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.NPM);

    this.npmAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var packages =
        this.npmPackageService.getPackagesContainsScope(repoInfo.getStorageKey(), scope, pageable);

    return this.restResponseFactory.success(PACKAGES_FETCHED, new PagedModel<>(packages));
  }

  @GetMapping("/{repoName}/{ignoredScope}/search")
  public @NonNull RestResponse<PagedModel<PackageListItem>> getPackages(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull String ignoredScope,
      @RequestParam(required = false, defaultValue = "") final @NonNull String name,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.NPM);

    this.npmAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var packages =
        this.npmPackageService.getPackagesContainsName(repoInfo.getStorageKey(), name, pageable);

    return this.restResponseFactory.success(PACKAGES_FETCHED, new PagedModel<>(packages));
  }

  @GetMapping({
    "/{repoName}/scope/search",
    "/{repoName}/scope/{scope}/search",
  })
  public @NonNull RestResponse<PagedModel<PackageListItem>> getPackagesFilterByScope(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable(required = false) final @Nullable String scope,
      @RequestParam(required = false, defaultValue = "") final @NonNull String name,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.NPM);

    this.npmAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var packages =
        this.npmPackageService.getPackagesByScopeContainsName(
            repoInfo.getStorageKey(), scope, name, pageable);

    return this.restResponseFactory.success(PACKAGES_FETCHED, new PagedModel<>(packages));
  }

  @GetMapping({
    "/{repoName}/{packageName}",
    "/{repoName}/{scope}/{packageName}",
    "/{repoName}/{packageName}/version/{versionName}",
    "/{repoName}/{scope}/{packageName}/version/{versionName}",
  })
  public @NonNull RestResponse<PackageVersionDetail> getVersion(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable(required = false) final @Nullable String scope,
      @PathVariable final @NonNull String packageName,
      @PathVariable final @Nullable String versionName)
      throws IOException {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.NPM);

    this.npmAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var version = this.npmFacade.getVersion(repoInfo, scope, packageName, versionName);

    return this.restResponseFactory.success("packageVersionFetched", version);
  }

  @GetMapping({
    "/{repoName}/package/{packageName}/versions/search",
    "/{repoName}/{scope}/package/{packageName}/versions/search",
  })
  public @NonNull RestResponse<PagedModel<PackageVersionListItem>> getVersions(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable(required = false) final @Nullable String scope,
      @PathVariable final @NonNull String packageName,
      @RequestParam(required = false, defaultValue = "") final @NonNull String version,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.NPM);

    this.npmAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var versions =
        this.npmPackageService.getVersionsContainsVersion(
            repoInfo.getStorageKey(), scope, packageName, version, pageable);

    return this.restResponseFactory.success("packageVersionsFetched", new PagedModel<>(versions));
  }

  @GetMapping({
    "/{repoName}/package/{packageName}/tags",
    "/{repoName}/{scope}/package/{packageName}/tags",
  })
  public @NonNull RestResponse<List<PackageDistributionTagMapListItem>> getTags(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable(required = false) final @Nullable String scope,
      @PathVariable final @NonNull String packageName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.NPM);

    this.npmAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var tags =
        this.npmPackageService.getDistributionTags(repoInfo.getStorageKey(), scope, packageName);

    return this.restResponseFactory.success("packageTagsFetched", tags);
  }

  public void updateUsage(final @NonNull RepoInfo repoInfo, final @NonNull BaseUsages usages) {

    final var usageUpdatedInfo = new UsageChangedInfo(repoInfo.getStorageKey(), usages);

    this.usageUpdateService.updateUsage(usageUpdatedInfo);
  }
}
