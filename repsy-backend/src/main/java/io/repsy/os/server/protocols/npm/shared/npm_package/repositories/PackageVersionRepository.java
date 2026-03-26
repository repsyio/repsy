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
package io.repsy.os.server.protocols.npm.shared.npm_package.repositories;

import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageVersionListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.PackageVersion;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
@NullMarked
public interface PackageVersionRepository extends JpaRepository<PackageVersion, UUID> {

  long countByNpmPackageRepoIdAndNpmPackageScopeAndNpmPackageName(
      UUID repoId, @Nullable String scopeName, String packageName);

  Optional<PackageVersion> findByNpmPackageIdAndVersion(UUID packageId, String version);

  @Query(
      """
      select pv from PackageVersion pv
      join pv.npmPackage p
      where pv.version like %:version%
      and p.id = :packageId""")
  Page<PackageVersionListItem> findAllByNpmPackageIdContainsVersion(
      UUID packageId, String version, Pageable pageable);
}
