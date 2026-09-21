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
package io.repsy.os.server.protocols.nuget.shared.packages.repositories;

import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
@NullMarked
public interface NuGetPackageRepository extends JpaRepository<NuGetPackage, UUID> {

  Optional<NuGetPackage> findByRepoIdAndPackageIdIgnoreCase(UUID repoId, String packageId);

  Page<NuGetPackage> findByRepoIdAndPackageIdContainingIgnoreCase(
      UUID repoId, String query, Pageable pageable);

  List<NuGetPackage> findByRepoIdAndPackageIdStartingWithIgnoreCase(
      UUID repoId, String prefix, Pageable pageable);

  /**
   * Inserts the package unless one with the same id already exists in the repo. It does not raise
   * the unique-index violation, which would abort the caller's PostgreSQL transaction.
   *
   * @return 1 when the row was inserted, 0 when it already existed
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          insert into "public"."nuget_package" ("id", "repo_id", "package_id", "created_at", "updated_at")
            values (:id, :repoId, :packageId, :now, :now)
            on conflict do nothing
          """,
      nativeQuery = true)
  int insertIfAbsent(UUID id, UUID repoId, String packageId, Instant now);
}
