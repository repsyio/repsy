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

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.HelmChartDetail;
import io.repsy.os.generated.model.HelmChartListItem;
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
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
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
@RequestMapping("/api/helm/charts")
@NullMarked
@SuppressWarnings("java:S6856")
public class HelmChartController {

  private final HelmApiFacade helmApiFacade;
  private final RestResponseFactory restResponseFactory;
  private final UsageUpdateService usageUpdateService;

  @GetMapping("/{repoName}")
  @RepoOperation
  public RestResponse<PagedModel<HelmChartListItem>> searchHelmCharts(
      final RepoInfo repoInfo,
      @RequestParam(defaultValue = "") final String query,
      @PageableDefault(sort = "lastUpdatedAt", direction = Sort.Direction.DESC)
          final Pageable pageable) {

    final var charts = this.helmApiFacade.search(repoInfo, query, pageable);

    return this.restResponseFactory.success("chartsFetched", new PagedModel<>(charts));
  }

  @GetMapping("/{repoName}/{name}")
  @RepoOperation
  public RestResponse<List<HelmChartVersionItem>> getHelmChartVersions(
      final RepoInfo repoInfo, @PathVariable final String name) {

    final var versions = this.helmApiFacade.getVersions(repoInfo, name);

    return this.restResponseFactory.success("chartVersionsFetched", versions);
  }

  @GetMapping("/{repoName}/{name}/{version}")
  @RepoOperation
  public RestResponse<HelmChartDetail> getHelmChartDetail(
      final RepoInfo repoInfo,
      @PathVariable final String name,
      @PathVariable final String version) {

    final var detail = this.helmApiFacade.getDetail(repoInfo, name, version);

    return this.restResponseFactory.success("chartDetailFetched", detail);
  }

  @DeleteMapping("/{repoName}/{name}/{version}")
  @RepoOperation(permission = Permission.MANAGE)
  public RestResponse<Void> deleteHelmChart(
      final RepoInfo repoInfo, @PathVariable final String name, @PathVariable final String version)
      throws IOException {

    final var usages = this.helmApiFacade.delete(repoInfo, name, version);
    this.usageUpdateService.updateUsage(new UsageChangedInfo(repoInfo.getId(), usages));

    return this.restResponseFactory.success("chartDeleted");
  }

  @GetMapping("/{repoName}/{name}/tags")
  @RepoOperation
  public RestResponse<List<String>> getHelmChartOciTags(
      final RepoInfo repoInfo, @PathVariable final String name) {

    final var tags = this.helmApiFacade.getOciTags(repoInfo, name);

    return this.restResponseFactory.success("chartTagsFetched", tags);
  }
}
