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
package io.repsy.os.server.protocols.cargo.ui.controllers;

import io.repsy.core.web.http.ResponseEntities;
import io.repsy.core.web.paging.SortValidator;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.CrateInfo;
import io.repsy.os.generated.model.CrateListItem;
import io.repsy.os.generated.model.CrateVersionInfo;
import io.repsy.os.generated.model.CrateVersionListItem;
import io.repsy.os.server.protocols.cargo.ui.facades.CargoApiFacade;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/api/cargo/crates")
@SuppressWarnings("java:S6856")
public class CargoCrateController {

  /**
   * The sort keys of the crate list, mapped to the {@code CargoCrate} properties the query sorts
   * by. The list item's own JSON names ({@code name}, {@code maxVersion}, {@code downloads}, {@code
   * updatedAt}) are accepted next to the entity property names ({@code totalDownloads}, {@code
   * lastUpdatedAt}). The former snake_case aliases ({@code max_version}, {@code updated_at}) are
   * gone with the snake_case JSON: like any unknown key they answer 400.
   */
  private static final Map<String, String> CRATE_SORT_PATHS =
      Map.of(
          "id", "id",
          "name", "name",
          "maxVersion", "maxVersion",
          "downloads", "totalDownloads",
          "totalDownloads", "totalDownloads",
          "updatedAt", "lastUpdatedAt",
          "lastUpdatedAt", "lastUpdatedAt");

  private static final Set<String> VERSION_SORT_PROPERTIES = Set.of("version", "createdAt");

  private final CargoApiFacade cargoApiFacade;
  private final UsageUpdateService usageUpdateService;

  @GetMapping("/{repoName}")
  @RepoOperation
  public ResponseEntity<PagedModel<CrateListItem>> search(
      final RepoInfo repoInfo,
      @RequestParam(name = "q", defaultValue = "") final String query,
      @PageableDefault final Pageable pageable) {

    final var crates =
        this.cargoApiFacade.search(
            repoInfo, query, SortValidator.resolveSortPaths(pageable, CRATE_SORT_PATHS));

    return ResponseEntity.ok(new PagedModel<>(crates));
  }

  @GetMapping("/{repoName}/{packageName}")
  @RepoOperation
  public ResponseEntity<CrateInfo> get(
      final RepoInfo repoInfo, @PathVariable final String packageName) {

    final var crate = this.cargoApiFacade.getCrate(repoInfo, packageName);

    return ResponseEntity.ok(crate);
  }

  @GetMapping("/{repoName}/{packageName}/versions/{version}")
  @RepoOperation
  public ResponseEntity<CrateVersionInfo> getVersion(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @PathVariable final String version) {

    final var crateVersion = this.cargoApiFacade.getCrateVersion(repoInfo, packageName, version);

    return ResponseEntity.ok(crateVersion);
  }

  @GetMapping("/{repoName}/{packageName}/versions")
  @RepoOperation
  public ResponseEntity<PagedModel<CrateVersionListItem>> listVersions(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @RequestParam(name = "q", defaultValue = "") final String query,
      @PageableDefault final Pageable pageable) {

    SortValidator.requireSortableBy(pageable, VERSION_SORT_PROPERTIES);

    final var versions =
        this.cargoApiFacade.getCrateVersions(repoInfo, packageName, query, pageable);

    return ResponseEntity.ok(new PagedModel<>(versions));
  }

  @DeleteMapping("/{repoName}/{packageName}")
  @RepoOperation(permission = Permission.MANAGE)
  public ResponseEntity<Void> delete(
      final RepoInfo repoInfo, @PathVariable final String packageName) throws IOException {

    final var usages = this.cargoApiFacade.deleteCrate(repoInfo, packageName);

    final var usageChangedInfo = new UsageChangedInfo(repoInfo.getId(), usages);

    this.usageUpdateService.updateUsage(usageChangedInfo);

    return ResponseEntities.noContent();
  }

  @DeleteMapping("/{repoName}/{packageName}/versions/{version}")
  @RepoOperation(permission = Permission.MANAGE)
  public ResponseEntity<Void> deleteVersion(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @PathVariable final String version)
      throws IOException {

    final var usages = this.cargoApiFacade.deleteCrateVersion(repoInfo, packageName, version);

    final var usageChangedInfo = new UsageChangedInfo(repoInfo.getId(), usages);

    this.usageUpdateService.updateUsage(usageChangedInfo);

    return ResponseEntities.noContent();
  }
}
