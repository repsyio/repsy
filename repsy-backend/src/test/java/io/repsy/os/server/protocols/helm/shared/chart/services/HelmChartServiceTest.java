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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.os.server.protocols.helm.shared.chart.entities.HelmChart;
import io.repsy.os.server.protocols.helm.shared.chart.entities.HelmChartVersion;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartRepository;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartVersionRepository;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
import io.repsy.protocols.helm.shared.chart.services.ChartService.ChartFileWriter;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

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
    when(this.helmChartRepository.findWithLockByRepoIdAndName(REPO_ID, NAME))
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

    when(this.helmChartRepository.findWithLockByRepoIdAndName(REPO_ID, NAME))
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

    when(this.helmChartRepository.findWithLockByRepoIdAndName(REPO_ID, NAME))
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
    when(this.helmChartRepository.findWithLockByRepoIdAndName(REPO_ID, NAME))
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

  private static final String VERSION_INDEX = "ux_helm_chart_version__chart_id_version";

  private static DataIntegrityViolationException violation(
      final String sqlState, final String message) {
    return new DataIntegrityViolationException(
        "could not execute statement", new SQLException(message, sqlState));
  }

  private static HelmChartVersion versionRow(final HelmChart chart, final long size) {
    final var row = new HelmChartVersion();
    row.setId(UUID.randomUUID());
    row.setChart(chart);
    row.setVersion(VERSION);
    row.setDigest("sha256:" + "1".repeat(64));
    row.setSize(size);
    return row;
  }

  private static HelmChartForm newForm() {
    return form("sha256:" + "2".repeat(64), 20L, "desc", "1.0", "application");
  }

  @Test
  @DisplayName("findOrCreate() inserts an absent chart parent without failing on the unique index")
  void findOrCreateInsertsAnAbsentChartParentIfAbsent() {
    final var chart = chartRow();
    when(this.helmChartRepository.findWithLockByRepoIdAndName(REPO_ID, NAME))
        .thenReturn(Optional.empty(), Optional.of(chart));
    when(this.helmChartVersionRepository.findByChartAndVersion(chart, VERSION))
        .thenReturn(Optional.empty());
    this.stubSaveReturnsItsArgument();

    this.service.findOrCreate(newForm(), REPO_ID);

    verify(this.helmChartRepository).insertIfAbsent(any(), eq(REPO_ID), eq(NAME), any());
    verify(this.helmChartRepository, never()).save(any(HelmChart.class));
  }

  @Test
  @DisplayName("publish() flushes the version row before it hands over to the file writer")
  void publishFlushesTheRowBeforeTheFileIsWritten() throws Exception {
    final var chart = chartRow();
    when(this.helmChartRepository.findWithLockByRepoIdAndName(REPO_ID, NAME))
        .thenReturn(Optional.of(chart));
    when(this.helmChartVersionRepository.findByChartAndVersion(chart, VERSION))
        .thenReturn(Optional.empty());
    this.stubSaveReturnsItsArgument();
    final var events = new ArrayList<String>();
    doAnswer(
            invocation -> {
              events.add("flush");
              return null;
            })
        .when(this.helmChartVersionRepository)
        .flush();
    final var replacedSeen = new ArrayList<HelmChartInfo>();

    final var info =
        this.service.publish(
            REPO_ID,
            newForm(),
            false,
            replaced -> {
              events.add("write");
              replacedSeen.add(replaced);
            });

    assertThat(events).containsExactly("flush", "write");
    assertThat(replacedSeen).containsOnlyNulls().hasSize(1);
    assertThat(info.digest()).isEqualTo(newForm().getDigest());
    assertThat(info.size()).isEqualTo(20L);
  }

  @Test
  @DisplayName("publish() hands the file writer the version it replaces, as it was")
  void publishReportsTheReplacedVersion() throws Exception {
    final var chart = chartRow();
    final var existing = versionRow(chart, 10L);
    when(this.helmChartRepository.findWithLockByRepoIdAndName(REPO_ID, NAME))
        .thenReturn(Optional.of(chart));
    when(this.helmChartVersionRepository.findByChartAndVersion(chart, VERSION))
        .thenReturn(Optional.of(existing));
    this.stubSaveReturnsItsArgument();
    final var replacedSeen = new ArrayList<HelmChartInfo>();

    final var info = this.service.publish(REPO_ID, newForm(), true, replacedSeen::add);

    assertThat(replacedSeen).hasSize(1);
    assertThat(replacedSeen.get(0).size()).isEqualTo(10L);
    assertThat(replacedSeen.get(0).digest()).isEqualTo("sha256:" + "1".repeat(64));
    assertThat(info.size()).isEqualTo(20L);
  }

  @Test
  @DisplayName("publish() refuses an existing version that may not be replaced, writing nothing")
  void publishRefusesAnExistingVersionWithoutOverride() throws Exception {
    final var chart = chartRow();
    when(this.helmChartRepository.findWithLockByRepoIdAndName(REPO_ID, NAME))
        .thenReturn(Optional.of(chart));
    when(this.helmChartVersionRepository.findByChartAndVersion(chart, VERSION))
        .thenReturn(Optional.of(versionRow(chart, 10L)));
    final ChartFileWriter writer = replaced -> fail("written");

    assertThatThrownBy(() -> this.service.publish(REPO_ID, newForm(), false, writer))
        .isInstanceOf(ItemAlreadyExistException.class)
        .hasMessage("chartAlreadyExists");

    verify(this.helmChartVersionRepository, never()).save(any(HelmChartVersion.class));
  }

  @Test
  @DisplayName("publish() answers a conflict for the version's unique index and writes nothing")
  void publishMapsTheVersionIndexToAConflict() {
    final var chart = chartRow();
    when(this.helmChartRepository.findWithLockByRepoIdAndName(REPO_ID, NAME))
        .thenReturn(Optional.of(chart));
    when(this.helmChartVersionRepository.findByChartAndVersion(chart, VERSION))
        .thenReturn(Optional.empty());
    this.stubSaveReturnsItsArgument();
    doThrow(
            violation(
                "23505",
                "duplicate key value violates unique constraint \"" + VERSION_INDEX + "\""))
        .when(this.helmChartVersionRepository)
        .flush();
    final var written = new ArrayList<HelmChartInfo>();

    assertThatThrownBy(() -> this.service.publish(REPO_ID, newForm(), false, written::add))
        .isInstanceOf(ItemAlreadyExistException.class)
        .hasMessage("chartAlreadyExists");

    assertThat(written).isEmpty();
  }

  @Test
  @DisplayName("publish() lets any other database violation surface instead of a conflict")
  void publishLeavesOtherViolationsAlone() {
    final var chart = chartRow();
    when(this.helmChartRepository.findWithLockByRepoIdAndName(REPO_ID, NAME))
        .thenReturn(Optional.of(chart));
    when(this.helmChartVersionRepository.findByChartAndVersion(chart, VERSION))
        .thenReturn(Optional.empty());
    this.stubSaveReturnsItsArgument();
    final var other = violation("23514", "violates check constraint \"ch_other\"");
    doThrow(other).when(this.helmChartVersionRepository).flush();
    final var written = new ArrayList<HelmChartInfo>();

    assertThatThrownBy(() -> this.service.publish(REPO_ID, newForm(), false, written::add))
        .isSameAs(other);

    assertThat(written).isEmpty();
  }

  @Test
  @DisplayName("publish() propagates a failing file writer, after the row was written")
  void publishPropagatesAFailingFileWriter() {
    final var chart = chartRow();
    when(this.helmChartRepository.findWithLockByRepoIdAndName(REPO_ID, NAME))
        .thenReturn(Optional.of(chart));
    when(this.helmChartVersionRepository.findByChartAndVersion(chart, VERSION))
        .thenReturn(Optional.empty());
    this.stubSaveReturnsItsArgument();

    assertThatThrownBy(
            () ->
                this.service.publish(
                    REPO_ID,
                    newForm(),
                    false,
                    replaced -> {
                      throw new IOException("disk full");
                    }))
        .isInstanceOf(IOException.class)
        .hasMessage("disk full");

    verify(this.helmChartVersionRepository).flush();
  }
}
