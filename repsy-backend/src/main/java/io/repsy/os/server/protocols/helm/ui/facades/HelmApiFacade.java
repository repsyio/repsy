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
import io.repsy.os.server.protocols.helm.shared.chart.services.HelmChartService;
import io.repsy.os.server.protocols.helm.shared.oci.services.HelmOciBlobService;
import io.repsy.os.server.protocols.helm.shared.oci.services.HelmOciManifestService;
import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.os.server.protocols.helm.ui.mappers.HelmChartMapper;
import io.repsy.os.server.protocols.shared.services.ProtocolApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.helm.shared.utils.HelmConstants;
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
  private final HelmOciBlobService helmOciBlobService;
  private final HelmChartMapper helmChartMapper;
  private final ApplicationEventPublisher eventPublisher;

  public void createRepo(final UUID repoId) {
    this.helmStorageService.createRepo(repoId);
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public BaseUsages deleteRepo(final RepoInfo repoInfo) throws IOException {
    final var freed = this.helmStorageService.deleteRepo(repoInfo.getStorageKey());
    return BaseUsages.builder().diskUsage(-1L * freed).build();
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
    return this.helmChartService.findAllVersionsByName(repoInfo.getStorageKey(), name).stream()
        .map(this.helmChartMapper::toVersionItem)
        .toList();
  }

  @Transactional(readOnly = true)
  public HelmChartDetail getDetail(
      final RepoInfo repoInfo, final String name, final String version) {
    final var info =
        this.helmChartService.findByRepoIdAndNameAndVersion(
            repoInfo.getStorageKey(), name, version);
    return this.helmChartMapper.toDetail(info);
  }

  public BaseUsages deleteAllVersions(final RepoInfo repoInfo, final String name)
      throws IOException {

    final var versions =
        this.helmChartService.findAllVersionsByName(repoInfo.getStorageKey(), name);

    if (versions.isEmpty()) {
      throw new ItemNotFoundException("chartNotFound");
    }

    final var allManifests =
        versions.stream()
            .flatMap(v -> this.helmOciManifestService.findAllByChartId(v.id()).stream())
            .toList();

    for (final var version : versions) {
      this.helmOciManifestService.deleteAllByChartId(version.id());
    }

    this.helmChartService.deleteChart(repoInfo.getStorageKey(), name);

    var freed = 0L;
    for (final var version : versions) {
      final var filename = name + "-" + version.version() + HelmConstants.TGZ_EXTENSION;
      freed +=
          this.helmStorageService.deleteChartFile(
              repoInfo.getStorageKey(), filename, version.digest(), repoInfo.getName());
    }

    for (final var manifest : allManifests) {
      this.helmStorageService.deleteManifestFile(
          repoInfo.getStorageKey(), manifest.name(), manifest.reference(), repoInfo.getName());
    }

    return BaseUsages.ofDisk(-freed);
  }

  public BaseUsages delete(final RepoInfo repoInfo, final String name, final String version)
      throws IOException {
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

    final var filename = name + "-" + version + HelmConstants.TGZ_EXTENSION;
    final var freed =
        this.helmStorageService.deleteChartFile(
            repoInfo.getStorageKey(), filename, chartInfo.digest(), repoInfo.getName());

    for (final var manifest : manifests) {
      this.helmStorageService.deleteManifestFile(
          repoInfo.getStorageKey(), manifest.name(), manifest.reference(), repoInfo.getName());
    }

    if (!manifests.isEmpty()
        && !this.helmChartService.existsByRepoIdAndDigest(
            repoInfo.getStorageKey(), chartInfo.digest())) {
      this.helmOciBlobService.deleteByRepoIdAndDigest(repoInfo.getStorageKey(), chartInfo.digest());
    }

    return BaseUsages.ofDisk(-freed);
  }

  @Transactional(readOnly = true)
  public List<String> getOciTags(final RepoInfo repoInfo, final String name) {
    return this.helmOciManifestService.listTagsByName(repoInfo.getStorageKey(), name);
  }
}
