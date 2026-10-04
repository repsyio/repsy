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
package io.repsy.os.server.protocols.nuget.ui.controllers;

import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.NuGetPackageInfo;
import io.repsy.os.generated.model.NuGetPackageListItem;
import io.repsy.os.generated.model.NuGetVersionInfo;
import io.repsy.os.generated.model.NuGetVersionListItem;
import io.repsy.os.server.protocols.nuget.ui.facades.NuGetApiFacade;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.http.ResponseEntities;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.os.shared.utils.SortValidator;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Pageable;
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
@RequestMapping("/api/nuget/packages")
@NullMarked
@SuppressWarnings("java:S6856")
public class NuGetPackageController {

  private static final Set<String> PACKAGE_SORT_PROPERTIES = Set.of("packageId");

  private static final Set<String> VERSION_SORT_PROPERTIES = Set.of("version", "publishedAt");

  private final NuGetApiFacade nugetApiFacade;
  private final UsageUpdateService usageUpdateService;

  @GetMapping("/{repoName}")
  @RepoOperation
  public ResponseEntity<PagedModel<NuGetPackageListItem>> search(
      final RepoInfo repoInfo,
      @RequestParam(name = "q", defaultValue = "") final String query,
      @PageableDefault final Pageable pageable) {

    SortValidator.requireSortableBy(pageable, PACKAGE_SORT_PROPERTIES);

    final var packages = this.nugetApiFacade.search(repoInfo, query, pageable);

    return ResponseEntity.ok(new PagedModel<>(packages));
  }

  @GetMapping("/{repoName}/{packageName}")
  @RepoOperation
  public ResponseEntity<NuGetPackageInfo> getPackage(
      final RepoInfo repoInfo, @PathVariable final String packageName) {

    final var pkg = this.nugetApiFacade.getPackage(repoInfo, packageName);

    return ResponseEntity.ok(pkg);
  }

  @GetMapping("/{repoName}/{packageName}/versions")
  @RepoOperation
  public ResponseEntity<PagedModel<NuGetVersionListItem>> listVersions(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @RequestParam(name = "q", defaultValue = "") final String query,
      @PageableDefault final Pageable pageable) {

    SortValidator.requireSortableBy(pageable, VERSION_SORT_PROPERTIES);

    final var versions = this.nugetApiFacade.getVersions(repoInfo, packageName, query, pageable);

    return ResponseEntity.ok(new PagedModel<>(versions));
  }

  @GetMapping("/{repoName}/{packageName}/versions/{version}")
  @RepoOperation
  public ResponseEntity<NuGetVersionInfo> getVersion(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @PathVariable final String version) {

    final var versionInfo = this.nugetApiFacade.getVersion(repoInfo, packageName, version);

    return ResponseEntity.ok(versionInfo);
  }

  @DeleteMapping("/{repoName}/{packageName}")
  @RepoOperation(permission = Permission.MANAGE)
  public ResponseEntity<Void> deletePackage(
      final RepoInfo repoInfo, @PathVariable final String packageName) {

    final var usages = this.nugetApiFacade.deletePackage(repoInfo, packageName);

    this.usageUpdateService.updateUsage(new UsageChangedInfo(repoInfo.getId(), usages));

    return ResponseEntities.noContent();
  }

  @DeleteMapping("/{repoName}/{packageName}/versions/{version}")
  @RepoOperation(permission = Permission.MANAGE)
  public ResponseEntity<Void> deleteVersion(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @PathVariable final String version) {

    final var deletedResult = this.nugetApiFacade.deleteVersion(repoInfo, packageName, version);

    this.usageUpdateService.updateUsage(
        new UsageChangedInfo(repoInfo.getId(), deletedResult.usages()));

    return ResponseEntities.noContent();
  }
}
