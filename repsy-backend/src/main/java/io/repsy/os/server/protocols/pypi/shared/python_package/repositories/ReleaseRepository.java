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

import io.repsy.os.server.protocols.pypi.shared.python_package.dtos.ReleaseListItem;
import io.repsy.os.server.protocols.pypi.shared.python_package.entities.Release;
import io.repsy.protocols.pypi.shared.python_package.dtos.ReleaseVersionRequiresPython;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
@NullMarked
public interface ReleaseRepository extends JpaRepository<Release, UUID> {

  @Query(
      "select r.summary from Release r where r.pypiPackage.id = :packageId and r.version = :version")
  Optional<String> getDescription(UUID packageId, String version);

  Optional<Release> findByPypiPackageIdAndVersion(UUID packageId, String version);

  List<ReleaseVersionRequiresPython> findAllByPypiPackageId(UUID packageId);

  Page<ReleaseListItem> findAllByPypiPackageId(UUID packageId, Pageable pageable);

  @Query(
      """
          select r from Release r
          where r.pypiPackage.id = :packageId
          and r.version like %:name%""")
  Page<ReleaseListItem> findAllByPypiPackageIdContainsName(
      UUID packageId, String name, Pageable pageable);

  List<Release> findAllByPypiPackageIdOrderByCreatedAtDesc(UUID packageId);

  int countAllByPypiPackageId(UUID packageId);
}
