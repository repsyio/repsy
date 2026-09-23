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
package io.repsy.os.server.protocols.pypi.shared.python_package.repositories;

import io.repsy.os.server.protocols.pypi.shared.python_package.dtos.PackageIndexListItem;
import io.repsy.os.server.protocols.pypi.shared.python_package.dtos.PackageListItem;
import io.repsy.os.server.protocols.pypi.shared.python_package.entities.PypiPackage;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
@NullMarked
public interface PypiPackageRepository extends JpaRepository<PypiPackage, UUID> {

  Optional<PypiPackage> findByRepoIdAndNormalizedName(UUID repoId, String normalizedName);

  /**
   * Finds the package and locks its row until the transaction ends, so a concurrent upload of the
   * same package waits here for the one that holds the lock.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
          select p from PypiPackage p
          where p.repo.id = :repoId and p.normalizedName = :normalizedName
          """)
  Optional<PypiPackage> findLockedByRepoIdAndNormalizedName(UUID repoId, String normalizedName);

  /**
   * Inserts the package unless one with the same (repo, normalized name) already exists. It does
   * not raise the unique-index violation, which would abort the caller's PostgreSQL transaction,
   * which also holds the release row and the file write. When a concurrent first upload has
   * inserted the package but not committed yet, the statement waits for it.
   *
   * @return 1 when the row was inserted, 0 when it already existed
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          insert into "public"."pypi_package" ("id", "repo_id", "name", "normalized_name", "created_at")
            values (:id, :repoId, :name, :normalizedName, :now)
            on conflict do nothing
          """,
      nativeQuery = true)
  int insertIfAbsent(UUID id, UUID repoId, String name, String normalizedName, Instant now);

  boolean existsByRepoIdAndNormalizedName(UUID repoId, String normalizedName);

  @Query(
      """
          select p from PypiPackage p
          join p.repo r
          where r.id = :repoId
          """)
  List<PackageIndexListItem> findAllByRepoIdAsListItem(UUID repoId);

  @Query(
      """
          select p.name as name, p.latestVersion as latestVersion, p.stableVersion as stableVersion,
          r.createdAt as updatedAt
          from PypiPackage p
          join p.releases r
          join p.repo re
          where re.id = :repoId and p.latestVersion = r.version""")
  Page<PackageListItem> findAllByRepoId(UUID repoId, Pageable pageable);

  @Query(
      """
          select p.name as name, p.latestVersion as latestVersion, p.stableVersion as stableVersion,
          r.createdAt as updatedAt
          from PypiPackage p
          join p.releases r
          join p.repo re
          where re.id = :repoId and p.latestVersion = r.version and p.name like %:name%""")
  Page<PackageListItem> findAllByRepoIdContainsName(UUID repoId, String name, Pageable pageable);

  @Query(
      """
          update PypiPackage p
          set p.latestVersion = :latestVersion, p.stableVersion = :stableVersion
          where p.id = :packageId""")
  @Modifying
  void updatePackageLatestVersionAndStableVersion(
      UUID packageId, String latestVersion, @Nullable String stableVersion);
}
