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
package io.repsy.os.shared.repo.repositories;

import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface RepoRepository extends JpaRepository<Repo, UUID> {

  @Override
  @NonNull Optional<Repo> findById(@NonNull UUID repoId);

  boolean existsByName(@NonNull String name);

  @NonNull List<Repo> findAllByTypeOrderByCreatedAtDescNameAsc(@NonNull RepoType type);

  @Modifying
  @Query(
      """
      update Repo r
      set r.diskUsage = r.diskUsage + :diskUsageDiff
      where r.id = :repoId""")
  int updateDiskUsage(@NonNull UUID repoId, long diskUsageDiff);

  /**
   * Adds the diff in one statement, unless that would take the usage below zero (RPS-2113).
   *
   * <p>The guard and the addition are evaluated against the same row version under the row lock the
   * {@code UPDATE} takes, so concurrent updates cannot interleave between a read and a write. A
   * result of 0 means the repo is gone or the diff does not fit; the caller tells them apart.
   *
   * @return the number of rows updated, 0 or 1
   */
  @Modifying
  @Query(
      """
      update Repo r
      set r.diskUsage = r.diskUsage + :diskUsageDiff
      where r.id = :repoId and r.diskUsage + :diskUsageDiff >= 0""")
  int addDiskUsageUnlessNegative(@NonNull UUID repoId, long diskUsageDiff);

  /**
   * Reads the disk usage and locks the row until the surrounding transaction ends, so a concurrent
   * {@link #updateDiskUsage} on the same repo waits and the value read here stays current.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r.diskUsage from Repo r where r.id = :repoId")
  @NonNull Optional<Long> findDiskUsageByIdForUpdate(@NonNull UUID repoId);

  /**
   * The committed value of the repo's {@code pgpVerifyAllSignaturesEnabled}, empty if it is gone.
   */
  @Query("select r.pgpVerifyAllSignaturesEnabled from Repo r where r.id = :repoId")
  @NonNull Optional<Boolean> findPgpVerifyAllSignaturesEnabledById(@NonNull UUID repoId);

  @NonNull Optional<Repo> findByNameAndType(@NonNull String name, @NonNull RepoType type);

  Optional<Repo> findByName(@NonNull String name);

  /** The sum is {@code null} when there is no repo, so the type is {@code @Nullable} (RPS-2077). */
  @Query("select sum(r.diskUsage) from Repo r")
  @Nullable Long getTotalDiskUsage();

  @Query("select r.name from Repo r")
  @NonNull List<String> findAllRepoNames();

  long countAllByType(@NonNull RepoType type);

  /**
   * The repos whose name matches {@code pattern}, a lower-cased {@code LIKE} pattern that escapes
   * its wildcards with a backslash ({@code LikePatterns}), narrowed to one type when {@code type}
   * is given.
   */
  @Query(
      """
      select r from Repo r
      where (:type is null or r.type = :type)
        and lower(r.name) like :pattern escape '\\'
      """)
  @NonNull Page<Repo> search(
      @Nullable RepoType type, @NonNull String pattern, @NonNull Pageable pageable);

  /** The number of repos of each type, as {@code [RepoType, Long]} rows. */
  @Query("select r.type, count(r) from Repo r group by r.type")
  @NonNull List<Object[]> countGroupedByType();
}
