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
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;

@NullMarked
public interface ChartService<ID> {

  HelmChartInfo findOrCreate(HelmChartForm form, ID repoId);

  Optional<HelmChartInfo> findOptionalByNameAndVersion(ID repoId, String name, String version);

  HelmChartInfo update(ID repoId, HelmChartForm form);

  HelmChartInfo findByRepoIdAndNameAndVersion(ID repoId, String name, String version);

  List<HelmChartInfo> findAllByRepoId(ID repoId);

  void delete(ID repoId, String name, String version);

  boolean existsByRepoIdAndDigest(ID repoId, String digest);
}
