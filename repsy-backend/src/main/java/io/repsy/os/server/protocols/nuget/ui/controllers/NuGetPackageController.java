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

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.NuGetDeletedItem;
import io.repsy.os.generated.model.NuGetPackageInfo;
import io.repsy.os.generated.model.NuGetPackageListItem;
import io.repsy.os.generated.model.NuGetVersionListItem;
import io.repsy.os.server.protocols.nuget.ui.facades.NuGetApiFacade;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.Permission;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Pageable;
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
@RequestMapping("/api/nuget/packages")
@NullMarked
@SuppressWarnings("java:S6856")
public class NuGetPackageController {

  private final NuGetApiFacade nugetApiFacade;
  private final RestResponseFactory responseFactory;
  private final UsageUpdateService usageUpdateService;

  @GetMapping("/{repoName}")
  @RepoOperation
  public RestResponse<PagedModel<NuGetPackageListItem>> search(
      final RepoInfo repoInfo,
      @RequestParam(defaultValue = "") final String query,
      final Pageable pageable) {

    final var packages = this.nugetApiFacade.search(repoInfo, query, pageable);

    return this.responseFactory.success("nugetPackagesFetched", new PagedModel<>(packages));
  }

  @GetMapping("/{repoName}/{packageId}")
  @RepoOperation
  public RestResponse<NuGetPackageInfo> getPackage(
      final RepoInfo repoInfo, @PathVariable final String packageId) {

    final var pkg = this.nugetApiFacade.getPackage(repoInfo, packageId);

    return this.responseFactory.success("nugetPackageFetched", pkg);
  }

  @GetMapping("/{repoName}/{packageId}/versions")
  @RepoOperation
  public RestResponse<PagedModel<NuGetVersionListItem>> listVersions(
      final RepoInfo repoInfo, @PathVariable final String packageId, final Pageable pageable) {

    final var versions = this.nugetApiFacade.getVersions(repoInfo, packageId, pageable);

    return this.responseFactory.success("nugetVersionsFetched", new PagedModel<>(versions));
  }

  @GetMapping("/{repoName}/{packageId}/{version}")
  @RepoOperation
  public RestResponse<io.repsy.os.generated.model.NuGetVersionInfo> getVersion(
      final RepoInfo repoInfo,
      @PathVariable final String packageId,
      @PathVariable final String version) {

    final var versionInfo = this.nugetApiFacade.getVersion(repoInfo, packageId, version);

    return this.responseFactory.success("nugetVersionFetched", versionInfo);
  }

  @DeleteMapping("/{repoName}/{packageId}")
  @RepoOperation(permission = Permission.MANAGE)
  public RestResponse<NuGetDeletedItem> deletePackage(
      final RepoInfo repoInfo, @PathVariable final String packageId) {

    final var usages = this.nugetApiFacade.deletePackage(repoInfo, packageId);

    this.usageUpdateService.updateUsage(new UsageChangedInfo(repoInfo.getId(), usages));

    return this.responseFactory.success("nugetPackageDeleted", NuGetDeletedItem.PACKAGE);
  }

  @DeleteMapping("/{repoName}/{packageId}/{version}")
  @RepoOperation(permission = Permission.MANAGE)
  public RestResponse<NuGetDeletedItem> deleteVersion(
      final RepoInfo repoInfo,
      @PathVariable final String packageId,
      @PathVariable final String version) {

    final var deletedResult = this.nugetApiFacade.deleteVersion(repoInfo, packageId, version);

    this.usageUpdateService.updateUsage(
        new UsageChangedInfo(repoInfo.getId(), deletedResult.usages()));

    return this.responseFactory.success("nugetVersionDeleted", deletedResult.deletedItem());
  }
}
