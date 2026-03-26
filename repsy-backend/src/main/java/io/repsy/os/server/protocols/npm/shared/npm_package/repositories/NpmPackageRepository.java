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

import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.NpmPackage;
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
public interface NpmPackageRepository extends JpaRepository<NpmPackage, UUID> {

  Optional<NpmPackage> findById(UUID packageId);

  Optional<NpmPackage> findByRepoIdAndScopeAndName(
      UUID repoId, @Nullable String scopeName, String packageName);

  @Query(
      """
      select p.scope as scope, p.name as name, p.latest as latest, pv.createdAt as updatedAt
      from NpmPackage p
      join p.packageVersions pv
      join p.repo r
      where r.id = :repoId and p.scope is null and p.latest = pv.version and p.name like %:name%""")
  Page<PackageListItem> findAllByRepoIdAndLatestVersionAndScopeIsNullContainsName(
      UUID repoId, String name, Pageable pageable);

  @Query(
      """
      select p.scope as scope, p.name as name, p.latest as latest, pv.createdAt as updatedAt
      from NpmPackage p
      join p.packageVersions pv
      join p.repo r
      where r.id = :repoId and p.scope = :scope and p.name like %:name% and p.latest = pv.version""")
  Page<PackageListItem> findAllByRepoIdAndLatestVersionAndScopeContainsName(
      UUID repoId, String scope, String name, Pageable pageable);

  @Query(
      """
      select p.scope as scope, p.name as name, p.latest as latest, pv.createdAt as updatedAt
      from NpmPackage p
      join p.packageVersions pv
      join p.repo r
      where r.id = :repoId and p.latest = pv.version
      and (:scope is null or p.scope like %:scope%)""")
  Page<PackageListItem> findAllByRepoIdAndLatestVersionContainsScope(
      UUID repoId, @Nullable String scope, Pageable pageable);

  @Query(
      """
      select p.scope as scope, p.name as name, p.latest as latest, pv.createdAt as updatedAt
      from NpmPackage p
      join p.packageVersions pv
      join p.repo r
      where r.id = :repoId and p.latest = pv.version and p.name like %:name%""")
  Page<PackageListItem> findAllByRepoIdAndLatestVersionContainsName(
      UUID repoId, String name, Pageable pageable);
}
