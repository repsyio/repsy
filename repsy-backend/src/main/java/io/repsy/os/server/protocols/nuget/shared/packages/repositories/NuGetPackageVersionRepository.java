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

import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackageVersion;
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
public interface NuGetPackageVersionRepository extends JpaRepository<NuGetPackageVersion, UUID> {

  Optional<NuGetPackageVersion> findByNugetPackageIdAndVersion(UUID packageId, String version);

  Optional<NuGetPackageVersion> findByNugetPackageIdAndVersionIgnoreCase(
      UUID packageId, String version);

  boolean existsByNugetPackageIdAndVersionIgnoreCase(UUID packageId, String version);

  /**
   * The versions stored with build metadata. The newest come first, so that when several of them
   * canonicalize to the same version the latest push is the one that keeps it.
   */
  @Query(
      """
      select new io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetBuildMetadataVersion(
          v.id, p.id, p.repo.id, p.repo.name, p.packageId, v.version)
      from NuGetPackageVersion v
        join v.nugetPackage p
      where v.version like '%+%'
      order by v.publishedAt desc, v.id
      """)
  List<NuGetBuildMetadataVersion> findAllWithBuildMetadata();

  @Modifying
  @Query("UPDATE NuGetPackageVersion v SET v.downloadCount = v.downloadCount + 1 WHERE v.id = :id")
  void incrementDownloadCount(@Param("id") UUID id);

  List<NuGetPackageVersion> findByNugetPackageIdOrderByPublishedAtDesc(UUID packageId);

  List<NuGetPackageVersion> findByNugetPackageIdAndIsListedTrueOrderByPublishedAtDesc(
      UUID packageId);

  /**
   * The versions of the package whose version matches {@code pattern}, a lower-cased {@code LIKE}
   * pattern that escapes its wildcards with a backslash. The match runs before paging, so it spans
   * every page.
   */
  @Query(
      """
      select v from NuGetPackageVersion v
      where v.nugetPackage.id = :packageId
        and lower(v.version) like :pattern escape '\\'
      """)
  Page<NuGetPackageVersion> searchByNugetPackageId(
      @Param("packageId") UUID packageId, @Param("pattern") String pattern, Pageable pageable);

  boolean existsByNugetPackageId(UUID packageId);
}
