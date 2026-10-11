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

import io.repsy.core.web.http.ResponseEntities;
import io.repsy.core.web.paging.SortValidator;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.generated.model.PypiPackageInfo;
import io.repsy.os.generated.model.PypiPackageListItem;
import io.repsy.os.generated.model.ReleaseDetail;
import io.repsy.os.generated.model.ReleaseListItem;
import io.repsy.os.server.protocols.pypi.shared.python_package.services.PypiPackageService;
import io.repsy.os.server.protocols.pypi.ui.facades.PypiApiFacade;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Set;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/api/pypi/packages")
@SuppressWarnings("java:S6856")
public class PypiPackageController {

  private static final Set<String> PACKAGE_SORT_PROPERTIES =
      Set.of("id", "name", "latestVersion", "updatedAt");

  private static final Set<String> VERSION_SORT_PROPERTIES = Set.of("id", "version", "createdAt");

  private final UsageUpdateService usageUpdateService;
  private final PypiApiFacade pypiApiFacade;
  private final PypiPackageService pypiPackageService;

  @DeleteMapping("/{repoName}/{packageName}")
  @RepoOperation(permission = Permission.MANAGE)
  public ResponseEntity<Void> delete(
      final RepoInfo repoInfo, @PathVariable final String packageName) {

    final var usages = this.pypiApiFacade.deletePackage(repoInfo, packageName);

    this.updateUsage(repoInfo, usages);

    return ResponseEntities.noContent();
  }

  @DeleteMapping("/{repoName}/{packageName}/versions/{version}")
  @RepoOperation(permission = Permission.MANAGE)
  public ResponseEntity<Void> deleteVersion(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @PathVariable final String version) {

    final var usages = this.pypiApiFacade.deleteRelease(repoInfo, packageName, version);

    this.updateUsage(repoInfo, usages);

    return ResponseEntities.noContent();
  }

  @GetMapping("/{repoName}")
  @RepoOperation
  public ResponseEntity<PagedModel<PypiPackageListItem>> list(
      final RepoInfo repoInfo,
      @RequestParam(name = "q", defaultValue = "") final String query,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    SortValidator.requireSortableBy(pageable, PACKAGE_SORT_PROPERTIES);

    final var packageList =
        query.isEmpty()
            ? this.pypiPackageService.getPackageList(repoInfo.getStorageKey(), pageable)
            : this.pypiPackageService.getPackagesContainsName(
                repoInfo.getStorageKey(), query, pageable);

    return ResponseEntity.ok(new PagedModel<>(packageList));
  }

  @GetMapping("/{repoName}/{packageName}")
  @RepoOperation
  public ResponseEntity<PypiPackageInfo> getPackage(
      final RepoInfo repoInfo, @PathVariable final String packageName) {

    return ResponseEntity.ok(this.pypiApiFacade.getPackage(repoInfo.getStorageKey(), packageName));
  }

  @GetMapping("/{repoName}/{packageName}/versions")
  @RepoOperation
  public ResponseEntity<PagedModel<ReleaseListItem>> listVersions(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @RequestParam(name = "q", defaultValue = "") final String query,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    SortValidator.requireSortableBy(pageable, VERSION_SORT_PROPERTIES);

    final var versions =
        query.isEmpty()
            ? this.pypiPackageService.getReleaseList(
                repoInfo.getStorageKey(), packageName, pageable)
            : this.pypiPackageService.getReleasesContainsVersion(
                repoInfo.getStorageKey(), packageName, query, pageable);

    return ResponseEntity.ok(new PagedModel<>(versions));
  }

  @GetMapping("/{repoName}/{packageName}/versions/{version}")
  @RepoOperation
  public ResponseEntity<ReleaseDetail> getVersion(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @PathVariable final String version) {

    return ResponseEntity.ok(
        this.pypiApiFacade.getReleaseDetail(repoInfo.getStorageKey(), packageName, version));
  }

  private void updateUsage(final RepoInfo repoInfo, final BaseUsages usages) {

    final var usageUpdatedInfo = new UsageChangedInfo(repoInfo.getStorageKey(), usages);

    this.usageUpdateService.updateUsage(usageUpdatedInfo);
  }
}
