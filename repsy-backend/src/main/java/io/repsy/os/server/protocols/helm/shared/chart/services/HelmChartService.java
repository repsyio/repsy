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

import com.github.f4b6a3.uuid.UuidCreator;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.server.protocols.helm.shared.chart.entities.HelmChart;
import io.repsy.os.server.protocols.helm.shared.chart.entities.HelmChartVersion;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartRepository;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartVersionRepository;
import io.repsy.os.shared.error_handling.utils.ConstraintViolations;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
import io.repsy.protocols.helm.shared.chart.services.ChartService;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@NullMarked
public class HelmChartService implements ChartService<UUID> {

  private static final String VERSION_UNIQUE_CONSTRAINT = "ux_helm_chart_version__chart_id_version";

  private final HelmChartRepository helmChartRepository;
  private final HelmChartVersionRepository helmChartVersionRepository;

  @Override
  @Transactional
  public HelmChartInfo findOrCreate(final HelmChartForm form, final UUID repoId) {
    final var chart = this.findOrCreateChart(repoId, form.getName());
    final var version = this.findOrCreateVersion(chart, form);
    return this.toDetail(version);
  }

  @Override
  public Optional<HelmChartInfo> findOptionalByNameAndVersion(
      final UUID repoId, final String name, final String version) {
    return this.helmChartVersionRepository
        .findByRepoIdAndNameAndVersion(repoId, name, version)
        .map(this::toDetail);
  }

  @Override
  @Transactional
  public HelmChartInfo update(final UUID repoId, final HelmChartForm form) {
    final var version =
        this.helmChartVersionRepository
            .findByRepoIdAndNameAndVersion(repoId, form.getName(), form.getVersion())
            .orElseThrow(() -> new ItemNotFoundException("chartNotFound"));
    version.setDescription(form.getDescription());
    version.setAppVersion(form.getAppVersion());
    version.setType(form.getType());
    version.setDigest(form.getDigest());
    version.setSize(form.getSize());
    return this.toDetail(this.helmChartVersionRepository.save(version));
  }

  @Override
  @Transactional(rollbackFor = IOException.class)
  public HelmChartInfo publish(
      final UUID repoId,
      final HelmChartForm form,
      final boolean allowOverride,
      final ChartFileWriter fileWriter)
      throws IOException {
    final HelmChartInfo replaced;
    final HelmChartInfo published;

    try {
      final var chart = this.findOrCreateChart(repoId, form.getName());
      final var existing =
          this.helmChartVersionRepository.findByChartAndVersion(chart, form.getVersion());

      if (existing.isPresent() && !allowOverride) {
        throw new ItemAlreadyExistException("chartAlreadyExists");
      }

      replaced = existing.map(this::toDetail).orElse(null);
      final var saved = this.findOrCreateVersion(chart, form);
      // Flush so a unique-index conflict (a concurrent upload of the same version) fails here,
      // before the file is written. The transaction, and the row lock it holds, stays open while
      // the file is written, so a losing upload waits for the winner instead of replacing its file.
      this.helmChartVersionRepository.flush();
      published = this.toDetail(saved);
    } catch (final DataIntegrityViolationException e) {
      // Only that index means the version exists. Any other violation is not the client's
      // conflict, so it is left to surface as the server error it is.
      if (!ConstraintViolations.violatesConstraint(e, VERSION_UNIQUE_CONSTRAINT)) {
        throw e;
      }

      throw new ItemAlreadyExistException("chartAlreadyExists");
    }

    fileWriter.write(replaced);

    return published;
  }

  @Override
  public HelmChartInfo findByRepoIdAndNameAndVersion(
      final UUID repoId, final String name, final String version) {
    return this.helmChartVersionRepository
        .findByRepoIdAndNameAndVersion(repoId, name, version)
        .map(this::toDetail)
        .orElseThrow(() -> new ItemNotFoundException("chartNotFound"));
  }

  @Override
  public List<HelmChartInfo> findAllByRepoId(final UUID repoId) {
    return this.helmChartVersionRepository.findAllByChartRepoId(repoId).stream()
        .<HelmChartInfo>map(this::toDetail)
        .toList();
  }

  public Page<HelmChartInfo> search(
      final UUID repoId, final String query, final Pageable pageable) {
    return this.helmChartVersionRepository
        .findLatestByRepoIdAndQuery(repoId, query, pageable)
        .map(this::toDetail);
  }

  /** Versions of the chart, newest first by creation time. Empty when the chart does not exist. */
  public List<HelmChartInfo> findAllVersionsByName(final UUID repoId, final String name) {
    return this.helmChartRepository
        .findByRepoIdAndName(repoId, name)
        .map(
            chart ->
                this.helmChartVersionRepository
                    .findAllByChartOrderByCreatedAtDescIdDesc(chart)
                    .stream()
                    .<HelmChartInfo>map(this::toDetail)
                    .toList())
        .orElse(List.of());
  }

  public boolean existsByRepoIdAndName(final UUID repoId, final String name) {
    return this.helmChartRepository.findByRepoIdAndName(repoId, name).isPresent();
  }

  @Override
  @Transactional
  public void delete(final UUID repoId, final String name, final String version) {
    this.deleteVersion(repoId, name, version);
  }

  @Transactional
  public void deleteVersion(final UUID repoId, final String name, final String version) {
    final var chart =
        this.helmChartRepository
            .findByRepoIdAndName(repoId, name)
            .orElseThrow(() -> new ItemNotFoundException("chartNotFound"));
    final var chartVersion =
        this.helmChartVersionRepository
            .findByChartAndVersion(chart, version)
            .orElseThrow(() -> new ItemNotFoundException("chartNotFound"));
    this.helmChartVersionRepository.delete(chartVersion);
    if (this.helmChartVersionRepository.findAllByChart(chart).isEmpty()) {
      this.helmChartRepository.delete(chart);
    }
  }

  @Transactional
  public void deleteChart(final UUID repoId, final String name) {
    final var chart =
        this.helmChartRepository
            .findByRepoIdAndName(repoId, name)
            .orElseThrow(() -> new ItemNotFoundException("chartNotFound"));
    this.helmChartVersionRepository.deleteAllByChart(chart);
    this.helmChartRepository.delete(chart);
  }

  @Override
  public boolean existsByRepoIdAndDigest(final UUID repoId, final String digest) {
    return this.helmChartVersionRepository.existsByChartRepoIdAndDigest(repoId, digest);
  }

  /**
   * Returns the chart row, inserting it when this is the first version of a chart name.
   *
   * <p>The insert skips a row that already exists instead of failing on the unique index: on
   * PostgreSQL a failed statement aborts the transaction, which also holds the version row and the
   * file write. When a concurrent first upload has inserted the chart but not committed yet, the
   * statement waits for it, and then finds the committed row.
   */
  private HelmChart findOrCreateChart(final UUID repoId, final String name) {
    final var existing = this.helmChartRepository.findByRepoIdAndName(repoId, name);

    if (existing.isPresent()) {
      return existing.get();
    }

    this.helmChartRepository.insertIfAbsent(
        UuidCreator.getTimeOrderedEpoch(), repoId, name, Instant.now());

    return this.helmChartRepository
        .findByRepoIdAndName(repoId, name)
        .orElseThrow(() -> new ItemNotFoundException("chartNotFound"));
  }

  private HelmChartVersion findOrCreateVersion(final HelmChart chart, final HelmChartForm form) {
    final var existing =
        this.helmChartVersionRepository.findByChartAndVersion(chart, form.getVersion());
    if (existing.isPresent()) {
      // Genuinely upserts (RPS-1218): the OCI push path
      // (AbstractHelmOciManifestPushProtocolMethodHandler) reaches this method for both a first
      // push and an override of the same (name, version), so a found row must be refreshed the
      // same way ChartService.update() refreshes the classic override path -- otherwise an
      // accepted OCI override with different bytes leaves the row pointing at superseded content.
      // The classic route never reaches this branch: pushChart() pre-checks and calls update()
      // itself before ever calling findOrCreate().
      final var version = existing.get();
      version.setDescription(form.getDescription());
      version.setAppVersion(form.getAppVersion());
      version.setType(form.getType());
      version.setDigest(form.getDigest());
      version.setSize(form.getSize());
      return this.helmChartVersionRepository.save(version);
    }

    final var version = new HelmChartVersion();
    version.setChart(chart);
    version.setVersion(form.getVersion());
    version.setDescription(form.getDescription());
    version.setAppVersion(form.getAppVersion());
    version.setType(form.getType());
    version.setDigest(form.getDigest());
    version.setSize(form.getSize());
    return this.helmChartVersionRepository.save(version);
  }

  private ChartDetail toDetail(final HelmChartVersion version) {
    return ChartDetail.builder()
        .id(version.getId())
        .name(version.getChart().getName())
        .version(version.getVersion())
        .description(version.getDescription())
        .appVersion(version.getAppVersion())
        .type(version.getType())
        .digest(version.getDigest())
        .size(version.getSize())
        .createdAt(version.getCreatedAt())
        .lastUpdatedAt(
            version.getLastUpdatedAt() != null
                ? version.getLastUpdatedAt()
                : version.getCreatedAt())
        .build();
  }

  @Builder
  @NullMarked
  private record ChartDetail(
      UUID id,
      String name,
      String version,
      @Nullable String description,
      @Nullable String appVersion,
      @Nullable String type,
      String digest,
      long size,
      Instant createdAt,
      Instant lastUpdatedAt)
      implements HelmChartInfo {}
}
