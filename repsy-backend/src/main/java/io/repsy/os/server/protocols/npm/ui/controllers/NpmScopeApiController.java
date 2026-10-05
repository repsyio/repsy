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

import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.generated.model.NpmPackageInfo;
import io.repsy.os.generated.model.NpmPackageListItem;
import io.repsy.os.generated.model.PackageVersionDetail;
import io.repsy.os.generated.model.PackageVersionListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.services.NpmPackageServiceImpl;
import io.repsy.os.server.protocols.npm.ui.facades.NpmApiFacade;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.http.ResponseEntities;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.os.shared.utils.SortValidator;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The npm routes of scoped packages, and the lists of the packages of one scope or of the unscoped
 * ones. Scoped packages live under their own {@code /api/npm/scopes} prefix rather than next to the
 * unscoped routes of {@link NpmPackageApiController}: below {@code /api/npm/packages/{repoName}}
 * every segment is a package name, so a scope there would hide the package of that name (RPS-1010,
 * RPS-1781). Below {@code /api/npm/scopes/{repoName}/{scope}} the {@code packages} literal follows
 * a scope, never a repository.
 */
@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/npm/scopes")
@NullMarked
@SuppressWarnings("java:S6856")
public class NpmScopeApiController {

  private final NpmPackageServiceImpl npmPackageService;
  private final NpmApiFacade npmFacade;
  private final UsageUpdateService usageUpdateService;

  @GetMapping({
    "/{repoName}/packages",
    "/{repoName}/{scope}/packages",
  })
  @RepoOperation
  public ResponseEntity<PagedModel<NpmPackageListItem>> listByScope(
      final RepoInfo repoInfo,
      @PathVariable(required = false) final @Nullable String scope,
      @RequestParam(name = "q", required = false, defaultValue = "") final String name,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    final var packages =
        this.npmPackageService.getPackagesByScopeContainsName(
            repoInfo.getStorageKey(),
            scope,
            name,
            SortValidator.resolveSortPaths(pageable, NpmPackageApiController.PACKAGE_SORT_PATHS));

    return ResponseEntity.ok(new PagedModel<>(packages));
  }

  @GetMapping("/{repoName}/{scope}/packages/{packageName}")
  @RepoOperation
  public ResponseEntity<NpmPackageInfo> getPackage(
      final RepoInfo repoInfo,
      @PathVariable final String scope,
      @PathVariable final String packageName) {

    return ResponseEntity.ok(this.npmFacade.getPackage(repoInfo, scope, packageName));
  }

  @DeleteMapping("/{repoName}/{scope}/packages/{packageName}")
  @RepoOperation(permission = Permission.MANAGE)
  public ResponseEntity<Void> deletePackage(
      final RepoInfo repoInfo,
      @PathVariable final String scope,
      @PathVariable final String packageName) {

    this.updateUsage(repoInfo, this.npmFacade.deletePackage(repoInfo, scope, packageName));

    return ResponseEntities.noContent();
  }

  @GetMapping("/{repoName}/{scope}/packages/{packageName}/versions")
  @RepoOperation
  public ResponseEntity<PagedModel<PackageVersionListItem>> listVersions(
      final RepoInfo repoInfo,
      @PathVariable final String scope,
      @PathVariable final String packageName,
      @RequestParam(name = "q", required = false, defaultValue = "") final String version,
      @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC)
          final Pageable pageable) {

    return ResponseEntity.ok(
        new PagedModel<>(
            this.npmFacade.listVersions(repoInfo, scope, packageName, version, pageable)));
  }

  @GetMapping("/{repoName}/{scope}/packages/{packageName}/versions/{version}")
  @RepoOperation
  public ResponseEntity<PackageVersionDetail> getVersion(
      final RepoInfo repoInfo,
      @PathVariable final String scope,
      @PathVariable final String packageName,
      @PathVariable final String version)
      throws IOException {

    return ResponseEntity.ok(this.npmFacade.getVersion(repoInfo, scope, packageName, version));
  }

  @DeleteMapping("/{repoName}/{scope}/packages/{packageName}/versions/{version}")
  @RepoOperation(permission = Permission.MANAGE)
  public ResponseEntity<Void> deleteVersion(
      final RepoInfo repoInfo,
      @PathVariable final String scope,
      @PathVariable final String packageName,
      @PathVariable final String version)
      throws IOException {

    this.updateUsage(
        repoInfo, this.npmFacade.deletePackageVersion(repoInfo, scope, packageName, version));

    return ResponseEntities.noContent();
  }

  @GetMapping("/{repoName}/{scope}/packages/{packageName}/tags")
  @RepoOperation
  public ResponseEntity<List<PackageDistributionTagMapListItem>> listTags(
      final RepoInfo repoInfo,
      @PathVariable final String scope,
      @PathVariable final String packageName) {

    return ResponseEntity.ok(
        this.npmPackageService.getDistributionTags(repoInfo.getStorageKey(), scope, packageName));
  }

  private void updateUsage(final RepoInfo repoInfo, final BaseUsages usages) {

    this.usageUpdateService.updateUsage(new UsageChangedInfo(repoInfo.getStorageKey(), usages));
  }
}
