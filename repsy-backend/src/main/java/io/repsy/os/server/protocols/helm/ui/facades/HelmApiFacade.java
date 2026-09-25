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
package io.repsy.os.server.protocols.helm.ui.facades;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.events.ArtifactVersionDeletedEvent;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.generated.model.HelmChartDetail;
import io.repsy.os.generated.model.HelmChartListItem;
import io.repsy.os.generated.model.HelmChartVersionItem;
import io.repsy.os.server.protocols.helm.shared.chart.services.HelmChartFilesService;
import io.repsy.os.server.protocols.helm.shared.chart.services.HelmChartService;
import io.repsy.os.server.protocols.helm.shared.oci.services.HelmOciManifestService;
import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.os.server.protocols.helm.ui.mappers.HelmChartMapper;
import io.repsy.os.server.protocols.shared.services.ProtocolApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.helm.shared.chart.services.AbstractHelmChartFilesService.DeletedChart;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service("helmApiFacade")
@Transactional
@RequiredArgsConstructor
@NullMarked
public class HelmApiFacade implements ProtocolApiFacade {

  private final HelmStorageService helmStorageService;
  private final HelmChartService helmChartService;
  private final HelmOciManifestService helmOciManifestService;
  private final HelmChartFilesService helmChartFilesService;
  private final HelmChartMapper helmChartMapper;
  private final ApplicationEventPublisher eventPublisher;

  @Override
  public void createRepo(final UUID repoId) {
    this.helmStorageService.createRepo(repoId);
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @Override
  public void deleteRepo(final RepoInfo repoInfo) {
    this.helmStorageService.deleteRepo(repoInfo.getStorageKey());
  }

  @Transactional(readOnly = true)
  public Page<HelmChartListItem> search(
      final RepoInfo repoInfo, final String query, final Pageable pageable) {
    return this.helmChartService
        .search(repoInfo.getStorageKey(), query, pageable)
        .map(this.helmChartMapper::toListItem);
  }

  @Transactional(readOnly = true)
  public List<HelmChartVersionItem> getVersions(final RepoInfo repoInfo, final String name) {
    final var versions =
        this.helmChartService.findAllVersionsByName(repoInfo.getStorageKey(), name);

    // A chart is removed together with its last version, so no versions means no such chart.
    if (versions.isEmpty()) {
      throw new ItemNotFoundException("chartNotFound");
    }

    return versions.stream().map(this.helmChartMapper::toVersionItem).toList();
  }

  @Transactional(readOnly = true)
  public HelmChartDetail getDetail(
      final RepoInfo repoInfo, final String name, final String version) {
    final var info =
        this.helmChartService.findByRepoIdAndNameAndVersion(
            repoInfo.getStorageKey(), name, version);
    return this.helmChartMapper.toDetail(info);
  }

  @Transactional(rollbackFor = IOException.class)
  public BaseUsages deleteAllVersions(final RepoInfo repoInfo, final String name)
      throws IOException {

    // The chart first, as a push takes it (RPS-1365), and before the versions and their manifests
    // are read: what a push that was running commits is then part of what is deleted.
    this.helmChartService.lockChart(repoInfo.getStorageKey(), name);

    final var versions =
        this.helmChartService.findAllVersionsByName(repoInfo.getStorageKey(), name);

    if (versions.isEmpty()) {
      throw new ItemNotFoundException("chartNotFound");
    }

    final var deleted =
        versions.stream()
            .map(
                v ->
                    new DeletedChart(
                        name,
                        v.version(),
                        v.digest(),
                        this.helmOciManifestService.findAllByChartId(v.id())))
            .toList();

    for (final var version : versions) {
      this.helmOciManifestService.deleteAllByChartId(version.id());
    }

    this.helmChartService.deleteChart(repoInfo.getStorageKey(), name);

    final var freed =
        this.helmChartFilesService.deleteFiles(
            repoInfo.getStorageKey(), repoInfo.getStorageKey(), repoInfo.getName(), deleted);

    return BaseUsages.ofDisk(-freed);
  }

  @Transactional(rollbackFor = IOException.class)
  public BaseUsages delete(final RepoInfo repoInfo, final String name, final String version)
      throws IOException {
    // The chart first, as a push takes it (RPS-1365), and before the version and its manifests are
    // read: what a push that was running commits is then part of what is deleted.
    this.helmChartService.lockChart(repoInfo.getStorageKey(), name);

    final var chartInfo =
        this.helmChartService.findByRepoIdAndNameAndVersion(
            repoInfo.getStorageKey(), name, version);

    final var manifests = this.helmOciManifestService.findAllByChartId(chartInfo.id());

    this.helmChartService.deleteVersion(repoInfo.getStorageKey(), name, version);

    this.eventPublisher.publishEvent(
        new ArtifactVersionDeletedEvent(
            repoInfo.getStorageKey(),
            repoInfo.getType().name(),
            repoInfo.getName(),
            name,
            version));

    final var freed =
        this.helmChartFilesService.deleteFiles(
            repoInfo.getStorageKey(),
            repoInfo.getStorageKey(),
            repoInfo.getName(),
            List.of(new DeletedChart(name, version, chartInfo.digest(), manifests)));

    return BaseUsages.ofDisk(-freed);
  }

  @Transactional(readOnly = true)
  public List<String> getOciTags(final RepoInfo repoInfo, final String name) {
    // A chart without tags (one uploaded the classic way) is an empty list, an unknown chart is a
    // 404.
    if (!this.helmChartService.existsByRepoIdAndName(repoInfo.getStorageKey(), name)) {
      throw new ItemNotFoundException("chartNotFound");
    }

    return this.helmOciManifestService.listTagsByName(repoInfo.getStorageKey(), name);
  }
}
