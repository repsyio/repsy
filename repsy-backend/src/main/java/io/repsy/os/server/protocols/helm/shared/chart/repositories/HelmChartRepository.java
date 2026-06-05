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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
@NullMarked
public interface HelmChartRepository extends JpaRepository<HelmChart, UUID> {

  List<HelmChart> findAllByRepoId(UUID repoId);

  List<HelmChart> findAllByRepoIdAndName(UUID repoId, String name);

  @Query("""
      SELECT h FROM HelmChart h
      WHERE h.repo.id = :repoId
        AND (:query = '' OR LOWER(h.name) LIKE LOWER(CONCAT('%', :query, '%')))
        AND NOT EXISTS (
          SELECT 1 FROM HelmChart h2
          WHERE h2.repo.id = :repoId
            AND h2.name = h.name
            AND h2.lastUpdatedAt > h.lastUpdatedAt
        )
      """)
  Page<HelmChart> findLatestByRepoIdAndQuery(
      @Param("repoId") UUID repoId,
      @Param("query") String query,
      Pageable pageable);

  Optional<HelmChart> findByRepoIdAndNameAndVersion(UUID repoId, String name, String version);

  boolean existsByRepoIdAndDigest(UUID repoId, String digest);

  @Modifying
  @Query(
      """
      delete from HelmChart c
      where c.repo.id = :repoId
        and c.name = :name
        and c.version = :version
      """)
  void deleteByRepoIdAndNameAndVersion(UUID repoId, String name, String version);
}
