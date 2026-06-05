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
package io.repsy.os.server.protocols.helm.shared.chart.repositories;

import io.repsy.os.server.protocols.helm.shared.chart.entities.HelmChart;
import io.repsy.os.server.protocols.helm.shared.chart.entities.HelmChartVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
@NullMarked
public interface HelmChartVersionRepository extends JpaRepository<HelmChartVersion, UUID> {

  Optional<HelmChartVersion> findByChartAndVersion(HelmChart chart, String version);

  List<HelmChartVersion> findAllByChart(HelmChart chart);

  List<HelmChartVersion> findAllByChartRepoId(UUID repoId);

  boolean existsByChartRepoIdAndDigest(UUID repoId, String digest);

  void deleteAllByChart(HelmChart chart);

  @Query(
      """
      SELECT v FROM HelmChartVersion v
      WHERE v.chart.repo.id = :repoId
        AND v.chart.name = :name
        AND v.version = :version
      """)
  Optional<HelmChartVersion> findByRepoIdAndNameAndVersion(
      @Param("repoId") UUID repoId,
      @Param("name") String name,
      @Param("version") String version);

  @Query(
      value =
          """
          SELECT v FROM HelmChartVersion v
          WHERE v.chart.repo.id = :repoId
            AND (:query = '' OR LOWER(v.chart.name) LIKE LOWER(CONCAT('%', :query, '%')))
            AND NOT EXISTS (
              SELECT 1 FROM HelmChartVersion v2
              WHERE v2.chart.id = v.chart.id
                AND v2.createdAt > v.createdAt
            )
          """,
      countQuery =
          """
          SELECT COUNT(DISTINCT v.chart.id) FROM HelmChartVersion v
          WHERE v.chart.repo.id = :repoId
            AND (:query = '' OR LOWER(v.chart.name) LIKE LOWER(CONCAT('%', :query, '%')))
          """)
  Page<HelmChartVersion> findLatestByRepoIdAndQuery(
      @Param("repoId") UUID repoId, @Param("query") String query, Pageable pageable);
}
