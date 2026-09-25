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
package io.repsy.protocols.helm.shared.chart.services;

import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public interface ChartService<ID> {

  HelmChartInfo findOrCreate(HelmChartForm form, ID repoId);

  Optional<HelmChartInfo> findOptionalByNameAndVersion(ID repoId, String name, String version);

  HelmChartInfo update(ID repoId, HelmChartForm form);

  /**
   * Records the chart version of a classic upload and, while that write is still open, stores its
   * file through {@code fileWriter}.
   *
   * <p>The row is written first (and flushed, so a unique-index conflict surfaces here) and the
   * file second, inside one transaction. If the row cannot be written, the file is never touched,
   * so an upload that loses a race for a version cannot replace the winner's file. If the file
   * cannot be written, the row is rolled back.
   *
   * @param allowOverride whether an existing version of the chart may be replaced
   * @throws io.repsy.core.error_handling.exceptions.ItemAlreadyExistException When the version
   *     exists and may not be replaced, or a concurrent upload of it won the race
   */
  HelmChartInfo publish(
      ID repoId, HelmChartForm form, boolean allowOverride, ChartFileWriter fileWriter)
      throws IOException;

  /**
   * Locks the chart's row until the transaction ends. A request that deletes a version or a whole
   * chart takes it first, before it reads or removes anything else of the chart, which is the order
   * a push takes its locks in ({@link #findOrCreate}, {@link #publish}: chart, then version, then
   * manifest). Two requests that take the same locks in opposite orders can deadlock (RPS-1365).
   *
   * @throws io.repsy.core.error_handling.exceptions.ItemNotFoundException When there is no such
   *     chart
   */
  void lockChart(ID repoId, String name);

  HelmChartInfo findByRepoIdAndNameAndVersion(ID repoId, String name, String version);

  List<HelmChartInfo> findAllByRepoId(ID repoId);

  void delete(ID repoId, String name, String version);

  boolean existsByRepoIdAndDigest(ID repoId, String digest);

  /** Stores the file of a version whose row {@link #publish} has just written. */
  @FunctionalInterface
  interface ChartFileWriter {

    /**
     * Writes the file of the version.
     *
     * @param replaced the version being replaced as it was before, or {@code null} for a new
     *     version
     */
    void write(@Nullable HelmChartInfo replaced) throws IOException;
  }
}
