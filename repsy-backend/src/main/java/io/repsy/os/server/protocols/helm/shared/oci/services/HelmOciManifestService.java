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
package io.repsy.os.server.protocols.helm.shared.oci.services;

import io.repsy.os.server.protocols.helm.shared.chart.entities.HelmChart;
import io.repsy.os.server.protocols.helm.shared.oci.entities.HelmOciManifest;
import io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciManifestRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestInfo;
import io.repsy.protocols.helm.shared.oci.services.OciManifestService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@NullMarked
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class HelmOciManifestService implements OciManifestService<UUID> {

  private final HelmOciManifestRepository helmOciManifestRepository;

  @Override
  @Transactional
  public HelmOciManifestInfo save(final HelmOciManifestForm form, final UUID repoId) {
    return this.helmOciManifestRepository
        .findByRepoIdAndNameAndReference(repoId, form.getName(), form.getReference())
        .map(
            existing -> {
              existing.setDigest(form.getDigest());
              existing.setMediaType(form.getMediaType());
              existing.setContent(form.getContent());
              return this.toDetail(this.helmOciManifestRepository.save(existing));
            })
        .orElseGet(
            () ->
                this.toDetail(this.helmOciManifestRepository.save(this.buildEntity(form, repoId))));
  }

  @Override
  public Optional<HelmOciManifestInfo> findByNameAndReference(
      final UUID repoId, final String name, final String reference) {
    return this.helmOciManifestRepository
        .findByRepoIdAndNameAndReference(repoId, name, reference)
        .map(this::toDetail);
  }

  @Override
  public Optional<HelmOciManifestInfo> findById(final UUID manifestId) {
    return this.helmOciManifestRepository.findById(manifestId).map(this::toDetail);
  }

  @Override
  @Transactional
  public void deleteById(final UUID manifestId) {
    this.helmOciManifestRepository.deleteById(manifestId);
  }

  @Override
  public List<HelmOciManifestInfo> findAllByChartId(final UUID chartId) {
    return this.helmOciManifestRepository.findAllByChartId(chartId).stream()
        .<HelmOciManifestInfo>map(this::toDetail)
        .toList();
  }

  @Override
  public List<String> listTagsByName(final UUID repoId, final String name) {
    return this.helmOciManifestRepository.findReferencesByRepoIdAndName(repoId, name);
  }

  private HelmOciManifest buildEntity(final HelmOciManifestForm form, final UUID repoId) {
    final var repo = new Repo();
    repo.setId(repoId);

    final var chart = new HelmChart();
    chart.setId(form.getChartId());

    final var manifest = new HelmOciManifest();
    manifest.setRepo(repo);
    manifest.setChart(chart);
    manifest.setName(form.getName());
    manifest.setReference(form.getReference());
    manifest.setDigest(form.getDigest());
    manifest.setMediaType(form.getMediaType());
    manifest.setContent(form.getContent());
    return manifest;
  }

  private ManifestDetail toDetail(final HelmOciManifest manifest) {
    return ManifestDetail.builder()
        .id(manifest.getId())
        .chartId(manifest.getChart().getId())
        .name(manifest.getName())
        .reference(manifest.getReference())
        .digest(manifest.getDigest())
        .mediaType(manifest.getMediaType())
        .content(manifest.getContent())
        .build();
  }

  @Value
  @Builder
  @NullMarked
  private static final class ManifestDetail implements HelmOciManifestInfo {
    UUID id;
    UUID chartId;
    String name;
    String reference;
    String digest;
    String mediaType;
    String content;
  }
}
