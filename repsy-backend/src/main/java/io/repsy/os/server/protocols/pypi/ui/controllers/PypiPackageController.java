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
package io.repsy.os.server.protocols.pypi.ui.controllers;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.server.protocols.pypi.shared.auth.services.PypiAuthComponent;
import io.repsy.os.server.protocols.pypi.shared.python_package.dtos.PackageListItem;
import io.repsy.os.server.protocols.pypi.shared.python_package.dtos.ReleaseDetail;
import io.repsy.os.server.protocols.pypi.shared.python_package.dtos.ReleaseListItem;
import io.repsy.os.server.protocols.pypi.shared.python_package.services.PypiPackageServiceImpl;
import io.repsy.os.server.protocols.pypi.ui.facades.PypiApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoType;
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
@RequestMapping("/api/pypi/packages")
public class PypiPackageController {

  private final @NonNull PypiAuthComponent pypiAuthComponent;
  private final @NonNull UsageUpdateService usageUpdateService;
  private final @NonNull RepoTxService repoTxService;
  private final @NonNull PypiApiFacade pypiApiFacade;
  private final @NonNull PypiPackageServiceImpl pypiPackageService;
  private final @NonNull RestResponseFactory restResponseFactory;

  @DeleteMapping("/{repoName}/{packageName}")
  public @NonNull RestResponse<Void> delete(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull String packageName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.PYPI);

    this.pypiAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    final var usages = this.pypiApiFacade.deletePackage(repoInfo, packageName);

    this.updateUsage(repoInfo, usages);

    return this.restResponseFactory.success("packageDeleted");
  }

  @DeleteMapping("/{repoName}/{packageName}/releases/{releaseVersion}")
  public @NonNull RestResponse<Void> deleteRelease(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull String packageName,
      @PathVariable final @NonNull String releaseVersion) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.PYPI);

    this.pypiAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    final var usages = this.pypiApiFacade.deleteRelease(repoInfo, packageName, releaseVersion);

    this.updateUsage(repoInfo, usages);

    return this.restResponseFactory.success("packageReleaseDeleted");
  }

  @GetMapping("/{repoName}")
  public @NonNull RestResponse<PagedModel<PackageListItem>> list(
      @RequestHeader(AUTHORIZATION) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.PYPI);

    this.pypiAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var packageList =
        this.pypiPackageService.getPackageList(repoInfo.getStorageKey(), pageable);

    return this.restResponseFactory.success("packagesFetched", new PagedModel<>(packageList));
  }

  @GetMapping(value = "/{repoName}", params = "name")
  public @NonNull RestResponse<PagedModel<PackageListItem>> listLikeName(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestParam(required = false, defaultValue = "") final @NonNull String name,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.PYPI);

    this.pypiAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var packageList =
        this.pypiPackageService.getPackagesContainsName(repoInfo.getStorageKey(), name, pageable);

    return this.restResponseFactory.success("packagesFetched", new PagedModel<>(packageList));
  }

  @GetMapping("/{repoName}/{packageName}/releases")
  public @NonNull RestResponse<PagedModel<ReleaseListItem>> listReleases(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull String packageName,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.PYPI);

    this.pypiAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var releases =
        this.pypiPackageService.getReleaseList(repoInfo.getStorageKey(), packageName, pageable);

    return this.restResponseFactory.success("releasesFetched", new PagedModel<>(releases));
  }

  @GetMapping(value = "/{repoName}/{packageName}/releases", params = "version")
  public @NonNull RestResponse<PagedModel<ReleaseListItem>> listReleasesLikeVersion(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull String packageName,
      @RequestParam(required = false, defaultValue = "") final @NonNull String version,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.PYPI);

    this.pypiAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var releases =
        this.pypiPackageService.getReleasesContainsVersion(
            repoInfo.getStorageKey(), packageName, version, pageable);

    return this.restResponseFactory.success("releasesFetched", new PagedModel<>(releases));
  }

  @GetMapping({
    "/{repoName}/{packageName}",
    "/{repoName}/{packageName}/releases/{releaseVersion}",
  })
  public @NonNull RestResponse<ReleaseDetail> getRelease(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull String packageName,
      @PathVariable final @Nullable String releaseVersion) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.PYPI);

    this.pypiAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var details =
        this.pypiApiFacade.getReleaseDetail(repoInfo.getStorageKey(), packageName, releaseVersion);

    return this.restResponseFactory.success("releaseDetailFetched", details);
  }

  public void updateUsage(final @NonNull RepoInfo repoInfo, final @NonNull BaseUsages usages) {
    final var usageUpdatedInfo = new UsageChangedInfo(repoInfo.getStorageKey(), usages);

    this.usageUpdateService.updateUsage(usageUpdatedInfo);
  }
}
