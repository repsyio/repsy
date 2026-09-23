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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.os.server.protocols.helm.shared.chart.entities.HelmChart;
import io.repsy.os.server.protocols.helm.shared.chart.entities.HelmChartVersion;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartRepository;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartVersionRepository;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RPS-1218: an accepted OCI override with different chart bytes used to leave the chart version
 * row's own digest/size stale, because {@code findOrCreateVersion} returned an existing row
 * untouched. It now upserts the same mutable field set {@link HelmChartService#update} already
 * refreshes on the classic override path.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HelmChartService")
class HelmChartServiceTest {

  private static final UUID REPO_ID = UUID.randomUUID();
  private static final String NAME = "payments";
  private static final String VERSION = "1.0.0";

  @Mock private HelmChartRepository helmChartRepository;
  @Mock private HelmChartVersionRepository helmChartVersionRepository;

  private HelmChartService service;

  @BeforeEach
  void setUp() {
    this.service = new HelmChartService(this.helmChartRepository, this.helmChartVersionRepository);
  }

  private static HelmChartForm form(
      final String digest,
      final long size,
      final String description,
      final String appVersion,
      final String type) {
    return HelmChartForm.builder()
        .name(NAME)
        .version(VERSION)
        .description(description)
        .appVersion(appVersion)
        .type(type)
        .digest(digest)
        .size(size)
        .build();
  }

  private static HelmChart chartRow() {
    final var chart = new HelmChart();
    chart.setId(UUID.randomUUID());
    chart.setName(NAME);
    return chart;
  }

  private void stubSaveReturnsItsArgument() {
    when(this.helmChartVersionRepository.save(any(HelmChartVersion.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  @DisplayName("findOrCreate() for an absent version creates a row from every form field")
  void findOrCreateCreatesForAnAbsentVersion() {
    final var chart = chartRow();
    when(this.helmChartRepository.findByRepoIdAndName(REPO_ID, NAME))
        .thenReturn(Optional.of(chart));
    when(this.helmChartVersionRepository.findByChartAndVersion(chart, VERSION))
        .thenReturn(Optional.empty());
    this.stubSaveReturnsItsArgument();

    final var form = form("sha256:" + "a".repeat(64), 100L, "desc", "1.0", "application");
    final var info = this.service.findOrCreate(form, REPO_ID);

    assertThat(info.name()).isEqualTo(NAME);
    assertThat(info.version()).isEqualTo(VERSION);
    assertThat(info.digest()).isEqualTo(form.getDigest());
    assertThat(info.size()).isEqualTo(100L);
    assertThat(info.description()).isEqualTo("desc");
    assertThat(info.appVersion()).isEqualTo("1.0");
    assertThat(info.type()).isEqualTo("application");
    verify(this.helmChartVersionRepository, times(1)).save(any(HelmChartVersion.class));
    verify(this.helmChartRepository, never()).save(any(HelmChart.class));
  }

  @Test
  @DisplayName(
      "findOrCreate() for an existing version refreshes its digest and size (regression pin)")
  void findOrCreateRefreshesDigestAndSizeOnOverride() {
    final var chart = chartRow();
    final var existing = new HelmChartVersion();
    existing.setId(UUID.randomUUID());
    existing.setChart(chart);
    existing.setVersion(VERSION);
    existing.setDigest("sha256:" + "1".repeat(64));
    existing.setSize(10L);

    when(this.helmChartRepository.findByRepoIdAndName(REPO_ID, NAME))
        .thenReturn(Optional.of(chart));
    when(this.helmChartVersionRepository.findByChartAndVersion(chart, VERSION))
        .thenReturn(Optional.of(existing));
    this.stubSaveReturnsItsArgument();

    final var newDigest = "sha256:" + "2".repeat(64);
    final var form = form(newDigest, 999L, "desc", "1.0", "application");
    final var info = this.service.findOrCreate(form, REPO_ID);

    assertThat(info.digest()).isEqualTo(newDigest);
    assertThat(info.size()).isEqualTo(999L);

    final var saved = ArgumentCaptor.forClass(HelmChartVersion.class);
    verify(this.helmChartVersionRepository).save(saved.capture());
    assertThat(saved.getValue().getId()).isEqualTo(existing.getId());
    verify(this.helmChartVersionRepository, times(1)).save(any(HelmChartVersion.class));
  }

  @Test
  @DisplayName("findOrCreate() for an existing version also refreshes description/appVersion/type")
  void findOrCreateRefreshesOtherMetadataOnOverride() {
    final var chart = chartRow();
    final var existing = new HelmChartVersion();
    existing.setId(UUID.randomUUID());
    existing.setChart(chart);
    existing.setVersion(VERSION);
    existing.setDigest("sha256:" + "1".repeat(64));
    existing.setSize(10L);
    existing.setDescription("old");
    existing.setAppVersion("0.1");
    existing.setType("library");

    when(this.helmChartRepository.findByRepoIdAndName(REPO_ID, NAME))
        .thenReturn(Optional.of(chart));
    when(this.helmChartVersionRepository.findByChartAndVersion(chart, VERSION))
        .thenReturn(Optional.of(existing));
    this.stubSaveReturnsItsArgument();

    final var form = form("sha256:" + "2".repeat(64), 20L, "new description", "2.0", "application");
    final var info = this.service.findOrCreate(form, REPO_ID);

    assertThat(info.description()).isEqualTo("new description");
    assertThat(info.appVersion()).isEqualTo("2.0");
    assertThat(info.type()).isEqualTo("application");
  }

  @Test
  @DisplayName("findOrCreate() reuses an existing chart parent instead of creating a duplicate")
  void findOrCreateReusesExistingChartParent() {
    final var chart = chartRow();
    when(this.helmChartRepository.findByRepoIdAndName(REPO_ID, NAME))
        .thenReturn(Optional.of(chart));
    when(this.helmChartVersionRepository.findByChartAndVersion(chart, VERSION))
        .thenReturn(Optional.empty());
    this.stubSaveReturnsItsArgument();

    this.service.findOrCreate(form("sha256:" + "a".repeat(64), 1L, null, null, null), REPO_ID);

    verify(this.helmChartRepository, never()).save(any(HelmChart.class));
  }

  @Test
  @DisplayName("update() still refreshes description, appVersion, type, digest and size")
  void updateStillRefreshesEveryMutableField() {
    final var chart = chartRow();
    final var existing = new HelmChartVersion();
    existing.setId(UUID.randomUUID());
    existing.setChart(chart);
    existing.setVersion(VERSION);
    existing.setDigest("sha256:" + "1".repeat(64));
    existing.setSize(10L);

    when(this.helmChartVersionRepository.findByRepoIdAndNameAndVersion(REPO_ID, NAME, VERSION))
        .thenReturn(Optional.of(existing));
    this.stubSaveReturnsItsArgument();

    final var newDigest = "sha256:" + "3".repeat(64);
    final var form = form(newDigest, 321L, "updated", "3.0", "library");
    final var info = this.service.update(REPO_ID, form);

    assertThat(info.digest()).isEqualTo(newDigest);
    assertThat(info.size()).isEqualTo(321L);
    assertThat(info.description()).isEqualTo("updated");
    assertThat(info.appVersion()).isEqualTo("3.0");
    assertThat(info.type()).isEqualTo("library");
  }
}
