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
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
@NullMarked
public interface HelmChartRepository extends JpaRepository<HelmChart, UUID> {

  Optional<HelmChart> findByRepoIdAndName(UUID repoId, String name);

  /**
   * Finds the chart and locks its row until the transaction ends, so a concurrent upload of the
   * same chart waits here for the one that holds the lock, and then sees what it committed.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<HelmChart> findWithLockByRepoIdAndName(UUID repoId, String name);

  /**
   * Inserts the chart unless a row for the (repo, name) pair exists, without failing the
   * transaction on the unique index when a concurrent upload inserted it first.
   *
   * @return the number of rows inserted, 0 when the chart already existed
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          insert into "public"."helm_chart" ("id", "version_lock", "repo_id", "name", "created_at")
            values (:id, 0, :repoId, :name, :now)
            on conflict do nothing
          """,
      nativeQuery = true)
  int insertIfAbsent(UUID id, UUID repoId, String name, Instant now);
}
