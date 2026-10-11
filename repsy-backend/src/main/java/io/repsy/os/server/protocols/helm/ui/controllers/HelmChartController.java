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
package io.repsy.os.server.protocols.helm.ui.controllers;

import io.repsy.core.web.http.ResponseEntities;
import io.repsy.core.web.paging.SortValidator;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.HelmChartDetail;
import io.repsy.os.generated.model.HelmChartListItem;
import io.repsy.os.generated.model.HelmChartSummary;
import io.repsy.os.generated.model.HelmChartVersionItem;
import io.repsy.os.server.protocols.helm.ui.facades.HelmApiFacade;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.io.IOException;
import java.util.List;
import java.util.Map;
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
@RequestMapping("/api/helm/charts")
@SuppressWarnings("java:S6856")
public class HelmChartController {

  /**
   * The sort keys of the chart search, mapped to the {@code HelmChartVersion} paths the query sorts
   * by. The list item's own names ({@code name}, {@code latestVersion}, {@code updatedAt}) come
   * first; {@code createdAt} and {@code lastUpdatedAt} are kept because the panel and the default
   * order already send them.
   */
  private static final Map<String, String> CHART_SORT_PATHS =
      Map.of(
          "name", "chart.name",
          "latestVersion", "version",
          "updatedAt", "lastUpdatedAt",
          "createdAt", "createdAt",
          "lastUpdatedAt", "lastUpdatedAt");

  private static final Set<String> VERSION_SORT_PROPERTIES = Set.of("version", "createdAt");

  private final HelmApiFacade helmApiFacade;
  private final UsageUpdateService usageUpdateService;

  @GetMapping("/{repoName}")
  @RepoOperation
  public ResponseEntity<PagedModel<HelmChartListItem>> searchHelmCharts(
      final RepoInfo repoInfo,
      @RequestParam(name = "q", defaultValue = "") final String query,
      @PageableDefault(sort = "lastUpdatedAt", direction = Sort.Direction.DESC)
          final Pageable pageable) {

    final var charts =
        this.helmApiFacade.search(
            repoInfo, query, SortValidator.resolveSortPaths(pageable, CHART_SORT_PATHS));

    return ResponseEntity.ok(new PagedModel<>(charts));
  }

  @GetMapping("/{repoName}/{packageName}")
  @RepoOperation
  public ResponseEntity<HelmChartSummary> getHelmChart(
      final RepoInfo repoInfo, @PathVariable final String packageName) {

    return ResponseEntity.ok(this.helmApiFacade.getChart(repoInfo, packageName));
  }

  @GetMapping("/{repoName}/{packageName}/versions")
  @RepoOperation
  public ResponseEntity<PagedModel<HelmChartVersionItem>> listHelmChartVersions(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @RequestParam(name = "q", defaultValue = "") final String query,
      @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC)
          final Pageable pageable) {

    SortValidator.requireSortableBy(pageable, VERSION_SORT_PROPERTIES);

    final var versions = this.helmApiFacade.getVersions(repoInfo, packageName, query, pageable);

    return ResponseEntity.ok(new PagedModel<>(versions));
  }

  @GetMapping("/{repoName}/{packageName}/versions/{version}")
  @RepoOperation
  public ResponseEntity<HelmChartDetail> getHelmChartDetail(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @PathVariable final String version) {

    final var detail = this.helmApiFacade.getDetail(repoInfo, packageName, version);

    return ResponseEntity.ok(detail);
  }

  @DeleteMapping("/{repoName}/{packageName}")
  @RepoOperation(permission = Permission.MANAGE)
  public ResponseEntity<Void> deleteAllHelmChartVersions(
      final RepoInfo repoInfo, @PathVariable final String packageName) throws IOException {

    final var usages = this.helmApiFacade.deleteAllVersions(repoInfo, packageName);
    this.usageUpdateService.updateUsage(new UsageChangedInfo(repoInfo.getId(), usages));

    return ResponseEntities.noContent();
  }

  @DeleteMapping("/{repoName}/{packageName}/versions/{version}")
  @RepoOperation(permission = Permission.MANAGE)
  public ResponseEntity<Void> deleteHelmChart(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @PathVariable final String version)
      throws IOException {

    final var usages = this.helmApiFacade.delete(repoInfo, packageName, version);
    this.usageUpdateService.updateUsage(new UsageChangedInfo(repoInfo.getId(), usages));

    return ResponseEntities.noContent();
  }

  @GetMapping("/{repoName}/{packageName}/tags")
  @RepoOperation
  public ResponseEntity<List<String>> getHelmChartOciTags(
      final RepoInfo repoInfo, @PathVariable final String packageName) {

    final var tags = this.helmApiFacade.getOciTags(repoInfo, packageName);

    return ResponseEntity.ok(tags);
  }
}
