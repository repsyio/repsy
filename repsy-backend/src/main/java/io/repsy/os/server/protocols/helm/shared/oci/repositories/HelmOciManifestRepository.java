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
package io.repsy.os.server.protocols.helm.shared.oci.repositories;

import io.repsy.os.server.protocols.helm.shared.oci.entities.HelmOciManifest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
@NullMarked
public interface HelmOciManifestRepository extends JpaRepository<HelmOciManifest, UUID> {

  Optional<HelmOciManifest> findByRepoIdAndNameAndReference(
      UUID repoId, String name, String reference);

  List<HelmOciManifest> findAllByChartVersionId(UUID chartVersionId);

  /**
   * The JSON of every manifest of the repo, read here and searched in memory rather than with
   * {@code like} in SQL.
   */
  @Query("select m.content from HelmOciManifest m where m.repo.id = :repoId")
  Stream<String> streamContentByRepoId(UUID repoId);

  @Modifying
  @Query("delete from HelmOciManifest m where m.chartVersion.id = :chartVersionId")
  void deleteAllByChartVersionId(@Param("chartVersionId") UUID chartVersionId);

  @Query(
      """
      select m.reference from HelmOciManifest m
      where m.repo.id = :repoId
        and m.name = :name
      order by m.createdAt desc
      """)
  List<String> findReferencesByRepoIdAndName(UUID repoId, String name);

  /**
   * The manifests stored under a name other than the name of their chart. The oldest come first, so
   * that when several of them would take the same name the earliest push is the one that keeps it.
   */
  @Query(
      """
      select new io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciManifestMismatch(
          m.id, m.repo.id, m.repo.name, m.name, m.reference, m.digest, c.name)
      from HelmOciManifest m
        join m.chartVersion v
        join v.chart c
      where m.name <> c.name
      order by m.createdAt, m.id
      """)
  List<HelmOciManifestMismatch> findAllNamedDifferentlyFromChart();
}
