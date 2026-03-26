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
public interface PypiPackageRepository extends JpaRepository<PypiPackage, UUID> {

  Optional<PypiPackage> findByRepoIdAndNormalizedName(UUID repoId, String normalizedName);

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
