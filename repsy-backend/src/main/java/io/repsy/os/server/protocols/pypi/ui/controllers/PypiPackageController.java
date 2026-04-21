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

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.server.protocols.pypi.shared.python_package.dtos.PackageListItem;
import io.repsy.os.server.protocols.pypi.shared.python_package.dtos.ReleaseDetail;
import io.repsy.os.server.protocols.pypi.shared.python_package.dtos.ReleaseListItem;
import io.repsy.os.server.protocols.pypi.shared.python_package.services.PypiPackageServiceImpl;
import io.repsy.os.server.protocols.pypi.ui.facades.PypiApiFacade;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.Permission;
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
@RequestMapping("/api/pypi/packages")
@NullMarked
@SuppressWarnings("java:S6856")
public class PypiPackageController {

  private final UsageUpdateService usageUpdateService;
  private final PypiApiFacade pypiApiFacade;
  private final PypiPackageServiceImpl pypiPackageService;
  private final RestResponseFactory restResponseFactory;

  @DeleteMapping("/{repoName}/{packageName}")
  @RepoOperation(permission = Permission.MANAGE)
  public RestResponse<Void> delete(
      final RepoInfo repoInfo, @PathVariable final String packageName) {

    final var usages = this.pypiApiFacade.deletePackage(repoInfo, packageName);

    this.updateUsage(repoInfo, usages);

    return this.restResponseFactory.success("packageDeleted");
  }

  @DeleteMapping("/{repoName}/{packageName}/releases/{releaseVersion}")
  @RepoOperation(permission = Permission.MANAGE)
  public RestResponse<Void> deleteRelease(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @PathVariable final String releaseVersion) {

    final var usages = this.pypiApiFacade.deleteRelease(repoInfo, packageName, releaseVersion);

    this.updateUsage(repoInfo, usages);

    return this.restResponseFactory.success("packageReleaseDeleted");
  }

  @GetMapping("/{repoName}")
  @RepoOperation
  public RestResponse<PagedModel<PackageListItem>> list(
      final RepoInfo repoInfo,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    final var packageList =
        this.pypiPackageService.getPackageList(repoInfo.getStorageKey(), pageable);

    return this.restResponseFactory.success("packagesFetched", new PagedModel<>(packageList));
  }

  @GetMapping(value = "/{repoName}", params = "name")
  @RepoOperation
  public RestResponse<PagedModel<PackageListItem>> listLikeName(
      final RepoInfo repoInfo,
      @RequestParam(required = false, defaultValue = "") final String name,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    final var packageList =
        this.pypiPackageService.getPackagesContainsName(repoInfo.getStorageKey(), name, pageable);

    return this.restResponseFactory.success("packagesFetched", new PagedModel<>(packageList));
  }

  @GetMapping("/{repoName}/{packageName}/releases")
  @RepoOperation
  public RestResponse<PagedModel<ReleaseListItem>> listReleases(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    final var releases =
        this.pypiPackageService.getReleaseList(repoInfo.getStorageKey(), packageName, pageable);

    return this.restResponseFactory.success("releasesFetched", new PagedModel<>(releases));
  }

  @GetMapping(value = "/{repoName}/{packageName}/releases", params = "version")
  @RepoOperation
  public RestResponse<PagedModel<ReleaseListItem>> listReleasesLikeVersion(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @RequestParam(required = false, defaultValue = "") final String version,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    final var releases =
        this.pypiPackageService.getReleasesContainsVersion(
            repoInfo.getStorageKey(), packageName, version, pageable);

    return this.restResponseFactory.success("releasesFetched", new PagedModel<>(releases));
  }

  @GetMapping({
    "/{repoName}/{packageName}",
    "/{repoName}/{packageName}/releases/{releaseVersion}",
  })
  @RepoOperation
  public RestResponse<ReleaseDetail> getRelease(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @PathVariable final @Nullable String releaseVersion) {

    final var details =
        this.pypiApiFacade.getReleaseDetail(repoInfo.getStorageKey(), packageName, releaseVersion);

    return this.restResponseFactory.success("releaseDetailFetched", details);
  }

  private void updateUsage(final RepoInfo repoInfo, final BaseUsages usages) {

    final var usageUpdatedInfo = new UsageChangedInfo(repoInfo.getStorageKey(), usages);

    this.usageUpdateService.updateUsage(usageUpdatedInfo);
  }
}
