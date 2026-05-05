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

  public BaseUsages delete(final RepoInfo repoInfo, final String name, final String version)
      throws IOException {
    final var chartInfo =
        this.helmChartService.findByRepoIdAndNameAndVersion(
            repoInfo.getStorageKey(), name, version);

    final var manifests = this.helmOciManifestService.findAllByChartId(chartInfo.getId());

    this.helmChartService.delete(repoInfo.getStorageKey(), name, version);

    final var filename = name + "-" + version + HelmConstants.TGZ_EXTENSION;
    final var freed =
        this.helmStorageService.deleteChartFile(
            repoInfo.getStorageKey(), filename, chartInfo.getDigest(), repoInfo.getName());

    for (final var manifest : manifests) {
      this.helmStorageService.deleteManifestFile(
          repoInfo.getStorageKey(),
          manifest.getName(),
          manifest.getReference(),
          repoInfo.getName());
    }

    if (!manifests.isEmpty()
        && !this.helmChartService.existsByRepoIdAndDigest(
            repoInfo.getStorageKey(), chartInfo.getDigest())) {
      this.helmOciBlobService.deleteByRepoIdAndDigest(
          repoInfo.getStorageKey(), chartInfo.getDigest());
    }

    return BaseUsages.ofDisk(-freed);
  }

  @Transactional(readOnly = true)
  public List<String> getOciTags(final RepoInfo repoInfo, final String name) {
    return this.helmOciManifestService.listTagsByName(repoInfo.getStorageKey(), name);
  }
}
