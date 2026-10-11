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
package io.repsy.protocols.helm.protocol.facades;

import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
import io.repsy.protocols.helm.shared.constants.HelmConstants;
import io.repsy.protocols.helm.shared.index.dtos.HelmIndexEntryInfo;
import io.repsy.protocols.helm.shared.index.dtos.HelmIndexInfo;
import io.repsy.protocols.helm.shared.utils.HelmVersionComparator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Builds the classic {@code index.yaml} model from the chart rows of a repo. */
@Slf4j
final class HelmIndexUtils {

  /** Charts by name, the versions of a chart highest first (SemVer precedence, then the string). */
  private static final Comparator<HelmChartInfo> INDEX_ORDER =
      Comparator.comparing(HelmChartInfo::name)
          .thenComparing(HelmChartInfo::version, HelmVersionComparator.INSTANCE.reversed());

  private static final ObjectMapper DEPENDENCIES_MAPPER = new ObjectMapper();
  private static final TypeReference<List<Map<String, Object>>> DEPENDENCIES_TYPE =
      new TypeReference<>() {};

  private HelmIndexUtils() {}

  /**
   * The index of the given charts, listed the way {@code helm repo index} writes it (RPS-1614): the
   * charts by name, the versions of a chart highest first. The rows come back in the order the
   * database keeps them, which changes with every update of a row.
   */
  static HelmIndexInfo buildIndex(final List<HelmChartInfo> allCharts) {
    final var charts = allCharts.stream().sorted(INDEX_ORDER).toList();

    final Map<String, List<HelmIndexEntryInfo>> entries = new LinkedHashMap<>();

    for (final var chart : charts) {
      final var url =
          HelmConstants.CHARTS_PATH
              + "/"
              + chart.name()
              + "-"
              + chart.version()
              + HelmConstants.TGZ_EXTENSION;
      final var entry =
          HelmIndexEntryInfo.builder()
              .name(chart.name())
              .version(chart.version())
              .description(chart.description())
              .appVersion(chart.appVersion())
              .type(chart.type())
              .apiVersion(chart.apiVersion())
              .dependencies(decodeDependencies(chart))
              .digest(chart.digest())
              .urls(List.of(url))
              .created(chart.createdAt().toString())
              .build();
      entries.computeIfAbsent(chart.name(), _ -> new ArrayList<>()).add(entry);
    }

    return HelmIndexInfo.builder()
        .apiVersion(HelmConstants.API_VERSION)
        .entries(entries)
        .generated(Instant.now().toString())
        .build();
  }

  /**
   * The dependencies of a chart for its index entry. They were validated and serialised when the
   * chart was pushed, so a value that no longer reads back (a row edited by hand) costs the entry
   * its dependencies, not the whole index.
   */
  private static @Nullable List<Map<String, Object>> decodeDependencies(final HelmChartInfo chart) {
    final var json = chart.dependencies();
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return DEPENDENCIES_MAPPER.readValue(json, DEPENDENCIES_TYPE);
    } catch (final JacksonException e) {
      log.warn(
          "Dependencies of chart {}:{} are not readable and are left out of the index: {}",
          chart.name(),
          chart.version(),
          e.getMessage());
      return null;
    }
  }
}
