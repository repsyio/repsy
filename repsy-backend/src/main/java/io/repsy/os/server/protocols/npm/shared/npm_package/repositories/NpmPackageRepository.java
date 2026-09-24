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

import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.NpmSearchCandidate;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.NpmPackage;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
@NullMarked
public interface NpmPackageRepository extends JpaRepository<NpmPackage, UUID> {

  @Override
  Optional<NpmPackage> findById(UUID packageId);

  Optional<NpmPackage> findByRepoIdAndScopeAndName(
      UUID repoId, @Nullable String scopeName, String packageName);

  /**
   * Same as {@link #findByRepoIdAndScopeAndName}, holding a row lock until the transaction ends, so
   * publishes of one package run one after another.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<NpmPackage> findWithLockByRepoIdAndScopeAndName(
      UUID repoId, @Nullable String scopeName, String packageName);

  /**
   * Inserts the package unless it exists. A row a concurrent transaction has inserted but not
   * committed yet makes the statement wait for that transaction, and then insert nothing.
   *
   * @return 1 when the package was inserted, 0 when it existed
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          insert into "public"."npm_package" ("id", "repo_id", "scope", "name", "latest", "created_at")
            values (:id, :repoId, cast(:scope as varchar(214)), :name, :latest, :now)
            on conflict do nothing
          """,
      nativeQuery = true)
  int insertIfAbsent(
      UUID id, UUID repoId, @Nullable String scope, String name, String latest, Instant now);

  @Query(
      """
      select p.scope as scope, p.name as name, p.latest as latest, pv.createdAt as updatedAt
      from NpmPackage p
      join p.packageVersions pv
      join p.repo r
      where r.id = :repoId and p.scope is null and p.latest = pv.version and p.name like %:name%""")
  Page<PackageListItem> findAllByRepoIdAndLatestVersionAndScopeIsNullContainsName(
      UUID repoId, String name, Pageable pageable);

  /**
   * The name term is matched against the whole {@code @scope/name} key the list shows, so it finds
   * a package by its name, its scope or the pair.
   */
  @Query(
      """
      select p.scope as scope, p.name as name, p.latest as latest, pv.createdAt as updatedAt
      from NpmPackage p
      join p.packageVersions pv
      join p.repo r
      where r.id = :repoId and p.scope = :scope and p.latest = pv.version
      and concat(p.scope, '/', p.name) like %:name%""")
  Page<PackageListItem> findAllByRepoIdAndLatestVersionAndScopeContainsName(
      UUID repoId, String scope, String name, Pageable pageable);

  /**
   * The search term is matched against the whole {@code @scope/name} key the list shows (the bare
   * name of an unscoped package), so it finds a package by its name, its scope or the pair.
   */
  @Query(
      """
      select p.scope as scope, p.name as name, p.latest as latest, pv.createdAt as updatedAt
      from NpmPackage p
      join p.packageVersions pv
      join p.repo r
      where r.id = :repoId and p.latest = pv.version
      and (:scope is null
        or (case when p.scope is null then p.name else concat(p.scope, '/', p.name) end)
          like %:scope%)""")
  Page<PackageListItem> findAllByRepoIdAndLatestVersionContainsScope(
      UUID repoId, @Nullable String scope, Pageable pageable);

  /**
   * The latest version of the packages of a repo that a search may match, by name. {@code pattern}
   * is a {@code like} pattern in lowercase whose {@code %}, {@code _} and {@code !} are escaped
   * with {@code !}; it narrows the packages to those whose key, description or a keyword contains
   * one of the search terms, and the search then applies every term to what this returns.
   */
  @Query(
      """
      select pv.id as versionId, p.scope as scope, p.name as name, p.latest as latest,
        pv.description as description, pv.createdAt as createdAt,
        pv.authorName as authorName, pv.authorEmail as authorEmail, pv.authorUrl as authorUrl,
        pv.homepage as homepage, pv.repositoryUrl as repositoryUrl, pv.bugsUrl as bugsUrl
      from NpmPackage p
      join p.packageVersions pv
      where p.repo.id = :repoId and pv.version = p.latest
      and (:scope is null or lower(p.scope) = :scope)
      and (:pattern is null
        or lower(case when p.scope is null then p.name else concat(p.scope, '/', p.name) end)
          like :pattern escape '!'
        or lower(pv.description) like :pattern escape '!'
        or exists (
          select k.id from PackageKeyword k
          where k.packageVersion = pv and lower(k.keyword) like :pattern escape '!'))
      order by p.name, p.scope""")
  List<NpmSearchCandidate> findSearchCandidates(
      UUID repoId, @Nullable String scope, @Nullable String pattern, Limit limit);
}
