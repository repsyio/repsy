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
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NuGetPackageRepository extends JpaRepository<NuGetPackage, UUID> {

  Optional<NuGetPackage> findByRepoIdAndPackageIdIgnoreCase(UUID repoId, String packageId);

  /**
   * The packages of the repo whose id matches {@code pattern}, a lower-cased {@code LIKE} pattern
   * that escapes its wildcards with a backslash.
   *
   * <p>A package is a result when it has at least one listed version the client can use, or no
   * listed version at all. Unless {@code includeSemVer2}, a SemVer 2.0.0-only version (build
   * metadata, or a pre-release label with dot-separated identifiers) is not usable; unless {@code
   * includePrerelease}, a pre-release is not. So a package whose only versions are pre-releases is
   * left out of a search without {@code prerelease=true}, as the NuGet V3 search specification says
   * (RPS-2105).
   */
  @Query(
      """
      select p from NuGetPackage p
      where p.repo.id = :repoId
        and lower(p.packageId) like :pattern escape '\\'
        and (not exists (
            select 1 from NuGetPackageVersion a
            where a.nugetPackage = p and a.listed = true)
          or exists (
            select 1 from NuGetPackageVersion v
            where v.nugetPackage = p and v.listed = true
              and (:includePrerelease = true or v.prerelease = false)
              and (:includeSemVer2 = true
                or (v.version not like '%+%' and v.version not like '%-%.%'))))
      """)
  Page<NuGetPackage> search(
      @Param("repoId") UUID repoId,
      @Param("pattern") String pattern,
      @Param("includePrerelease") boolean includePrerelease,
      @Param("includeSemVer2") boolean includeSemVer2,
      Pageable pageable);

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
