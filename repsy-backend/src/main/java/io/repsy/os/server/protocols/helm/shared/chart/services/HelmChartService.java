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
package io.repsy.os.server.protocols.helm.shared.chart.services;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.server.protocols.helm.shared.chart.dtos.HelmChartDetail;
import io.repsy.os.server.protocols.helm.shared.chart.entities.HelmChart;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
import io.repsy.protocols.helm.shared.chart.services.ChartService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@NullMarked
public class HelmChartService implements ChartService<UUID> {

  private final HelmChartRepository helmChartRepository;

  @Override
  @Transactional
  public HelmChartInfo findOrCreate(final HelmChartForm form, final UUID repoId) {
    return this.helmChartRepository
        .findByRepoIdAndNameAndVersion(repoId, form.getName(), form.getVersion())
        .map(this::toDetail)
        .orElseGet(
            () -> this.toDetail(this.helmChartRepository.save(this.buildEntity(form, repoId))));
  }

  @Override
  public Optional<HelmChartInfo> findOptionalByNameAndVersion(
      final UUID repoId, final String name, final String version) {
    return this.helmChartRepository
        .findByRepoIdAndNameAndVersion(repoId, name, version)
        .map(this::toDetail);
  }

  @Override
  @Transactional
  public HelmChartInfo update(final UUID repoId, final HelmChartForm form) {
    final var chart =
        this.helmChartRepository
            .findByRepoIdAndNameAndVersion(repoId, form.getName(), form.getVersion())
            .orElseThrow(() -> new ItemNotFoundException("chartNotFound"));
    chart.setDescription(form.getDescription());
    chart.setAppVersion(form.getAppVersion());
    chart.setType(form.getType());
    chart.setDigest(form.getDigest());
    chart.setSize(form.getSize());
    return this.toDetail(this.helmChartRepository.save(chart));
  }

  @Override
  public HelmChartInfo findByRepoIdAndNameAndVersion(
      final UUID repoId, final String name, final String version) {
    return this.helmChartRepository
        .findByRepoIdAndNameAndVersion(repoId, name, version)
        .map(this::toDetail)
        .orElseThrow(() -> new ItemNotFoundException("chartNotFound"));
  }

  @Override
  public List<HelmChartInfo> findAllByRepoId(final UUID repoId) {
    return this.helmChartRepository.findAllByRepoId(repoId).stream()
        .map(this::toDetail)
        .map(HelmChartInfo.class::cast)
        .toList();
  }

  public Page<HelmChartInfo> search(
      final UUID repoId, final String query, final Pageable pageable) {
    return this.helmChartRepository
        .findAllByRepoIdAndNameContainingIgnoreCase(repoId, query, pageable)
        .map(this::toDetail);
  }

  public List<HelmChartInfo> findAllVersionsByName(final UUID repoId, final String name) {
    return this.helmChartRepository.findAllByRepoIdAndName(repoId, name).stream()
        .map(chart -> (HelmChartInfo) this.toDetail(chart))
        .toList();
  }

  @Override
  @Transactional
  public void delete(final UUID repoId, final String name, final String version) {
    if (this.helmChartRepository.findByRepoIdAndNameAndVersion(repoId, name, version).isEmpty()) {
      throw new ItemNotFoundException("chartNotFound");
    }
    this.helmChartRepository.deleteByRepoIdAndNameAndVersion(repoId, name, version);
  }

  @Override
  public boolean existsByRepoIdAndDigest(final UUID repoId, final String digest) {
    return this.helmChartRepository.existsByRepoIdAndDigest(repoId, digest);
  }

  private HelmChart buildEntity(final HelmChartForm form, final UUID repoId) {
    final var repo = new Repo();
    repo.setId(repoId);

    final var chart = new HelmChart();
    chart.setRepo(repo);
    chart.setName(form.getName());
    chart.setVersion(form.getVersion());
    chart.setDescription(form.getDescription());
    chart.setAppVersion(form.getAppVersion());
    chart.setType(form.getType());
    chart.setDigest(form.getDigest());
    chart.setSize(form.getSize());
    return chart;
  }

  private HelmChartDetail toDetail(final HelmChart chart) {
    return HelmChartDetail.builder()
        .id(chart.getId())
        .name(chart.getName())
        .version(chart.getVersion())
        .description(chart.getDescription())
        .appVersion(chart.getAppVersion())
        .type(chart.getType())
        .digest(chart.getDigest())
        .size(chart.getSize())
        .createdAt(chart.getCreatedAt())
        .lastUpdatedAt(chart.getLastUpdatedAt())
        .build();
  }
}
