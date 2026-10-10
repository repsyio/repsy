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

import io.repsy.core.web.http.ResponseEntities;
import io.repsy.core.web.paging.SortValidator;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.generated.model.NpmPackageInfo;
import io.repsy.os.generated.model.NpmPackageListItem;
import io.repsy.os.generated.model.PackageVersionDetail;
import io.repsy.os.generated.model.PackageVersionListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.services.NpmPackageService;
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
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
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

  /**
   * The sort keys of the package lists, mapped to the paths the queries sort by. The list item's
   * {@code latestVersion} is accepted although the queries project it as {@code latest}.
   */
  static final Map<String, String> PACKAGE_SORT_PATHS =
      Map.of(
          "id", "id",
          "name", "name",
          "scope", "scope",
          "latestVersion", "latest",
          "updatedAt", "updatedAt");

  private final UsageUpdateService usageUpdateService;
  private final NpmPackageService npmPackageService;
  private final NpmApiFacade npmFacade;

  @DeleteMapping("/{repoName}/{packageName}")
  @RepoOperation(permission = Permission.MANAGE)
  public ResponseEntity<Void> delete(
      final RepoInfo repoInfo, @PathVariable final String packageName) {

    final var usages = this.npmFacade.deletePackage(repoInfo, null, packageName);

    this.updateUsage(repoInfo, usages);

    return ResponseEntities.noContent();
  }

  @DeleteMapping("/{repoName}/{packageName}/versions/{version}")
  @RepoOperation(permission = Permission.MANAGE)
  public ResponseEntity<Void> deleteVersion(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @PathVariable final String version)
      throws IOException {

    final var usages = this.npmFacade.deletePackageVersion(repoInfo, null, packageName, version);

    this.updateUsage(repoInfo, usages);

    return ResponseEntities.noContent();
  }

  @GetMapping("/{repoName}")
  @RepoOperation
  public ResponseEntity<PagedModel<NpmPackageListItem>> list(
      final RepoInfo repoInfo,
      @RequestParam(required = false) final @Nullable String scope,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    final var packages =
        this.npmPackageService.getPackagesContainsScope(
            repoInfo.getStorageKey(),
            scope,
            SortValidator.resolveSortPaths(pageable, PACKAGE_SORT_PATHS));

    return ResponseEntity.ok(new PagedModel<>(packages));
  }

  @GetMapping("/{repoName}/{packageName}")
  @RepoOperation
  public ResponseEntity<NpmPackageInfo> getPackage(
      final RepoInfo repoInfo, @PathVariable final String packageName) {

    return ResponseEntity.ok(this.npmFacade.getPackage(repoInfo, null, packageName));
  }

  @GetMapping("/{repoName}/{packageName}/versions/{version}")
  @RepoOperation
  public ResponseEntity<PackageVersionDetail> getVersion(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @PathVariable final String version)
      throws IOException {

    return ResponseEntity.ok(this.npmFacade.getVersion(repoInfo, null, packageName, version));
  }

  @GetMapping("/{repoName}/{packageName}/versions")
  @RepoOperation
  public ResponseEntity<PagedModel<PackageVersionListItem>> listVersions(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @RequestParam(name = "q", required = false, defaultValue = "") final String version,
      @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC)
          final Pageable pageable) {

    return ResponseEntity.ok(
        new PagedModel<>(
            this.npmFacade.listVersions(repoInfo, null, packageName, version, pageable)));
  }

  @GetMapping("/{repoName}/{packageName}/tags")
  @RepoOperation
  public ResponseEntity<List<PackageDistributionTagMapListItem>> listTags(
      final RepoInfo repoInfo, @PathVariable final String packageName) {

    return ResponseEntity.ok(
        this.npmPackageService.getDistributionTags(repoInfo.getStorageKey(), null, packageName));
  }

  private void updateUsage(final RepoInfo repoInfo, final BaseUsages usages) {

    final var usageUpdatedInfo = new UsageChangedInfo(repoInfo.getStorageKey(), usages);

    this.usageUpdateService.updateUsage(usageUpdatedInfo);
  }
}
