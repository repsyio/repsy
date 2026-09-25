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
package io.repsy.os.server.protocols.cargo.shared.crate.repositories;

import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoCrate;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateListItem;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
@NullMarked
public interface CargoCrateRepository extends JpaRepository<CargoCrate, UUID> {

  Optional<CargoCrate> findByRepoIdAndName(UUID repoId, String name);

  Page<CrateListItem> findAllByRepoIdAndNameContaining(UUID repoId, String name, Pageable pageable);

  List<CargoCrate> findAllByRepoId(UUID repoId);

  /**
   * Inserts the crate unless one with the same (repo, name) already exists. It does not raise the
   * unique-index violation, which would abort the caller's PostgreSQL transaction: that transaction
   * also holds the version row while the crate file is written (RPS-1124). When a concurrent first
   * publish has inserted the crate but not committed yet, the statement waits for it and then skips
   * the insert.
   *
   * @return 1 when the row was inserted, 0 when it already existed
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          insert into "public"."cargo_crate"
              ("id", "repo_id", "name", "original_name", "max_version", "total_downloads",
                "description", "homepage", "repository", "created_at", "has_lib", "last_updated_at")
            values (:id, :repoId, :name, :originalName, :maxVersion, 0,
                    :description, :homepage, :repository, :now, :hasLib, :now)
            on conflict do nothing
          """,
      nativeQuery = true)
  int insertIfAbsent(
      UUID id,
      UUID repoId,
      String name,
      String originalName,
      String maxVersion,
      @Nullable String description,
      @Nullable String homepage,
      @Nullable String repository,
      boolean hasLib,
      Instant now);
}
