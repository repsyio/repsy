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

import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageDistributionTagListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.PackageDistTag;
import io.repsy.protocols.npm.shared.npm_package.dtos.PackageDistributionTagMapListItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
@NullMarked
public interface PackageDistTagRepository extends JpaRepository<PackageDistTag, UUID> {

  @Query(
      """
          select pdt.tagName as tag, pv.version as version
          from PackageDistTag pdt join pdt.packageVersion pv
          where pv.npmPackage in (select p from NpmPackage p where p.id = :packageId)""")
  List<PackageDistributionTagMapListItem> findAllTagsOfPackage(UUID packageId);

  Optional<PackageDistTag> findByPackageVersionNpmPackageIdAndTagName(
      UUID packageId, String tagName);

  List<PackageDistributionTagListItem> findAllByPackageVersionId(UUID packageVersionId);

  @Query(
      """
            select pdt.tagName as tag, pv.version as version
            from PackageDistTag pdt
            join pdt.packageVersion pv
            join pv.npmPackage p
            join p.repo r
            where r.id = :repoId
            and p.scope = :scopeName
            and p.name = :packageName""")
  List<PackageDistributionTagMapListItem> findAllByRepoIdAndScopeAndPackageName(
      UUID repoId, @Nullable String scopeName, String packageName);
}
