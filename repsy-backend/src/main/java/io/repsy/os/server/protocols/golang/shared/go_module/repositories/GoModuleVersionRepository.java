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
package io.repsy.os.server.protocols.golang.shared.go_module.repositories;

import io.repsy.os.server.protocols.golang.shared.go_module.dtos.GoModuleVersionListItem;
import io.repsy.os.server.protocols.golang.shared.go_module.entities.GoModuleVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GoModuleVersionRepository extends JpaRepository<GoModuleVersion, UUID> {

  Optional<GoModuleVersion> findByGoModuleIdAndVersion(UUID moduleId, String version);

  Optional<GoModuleVersion> findFirstByGoModuleIdOrderByCreatedAtDesc(UUID moduleId);

  @Query(
      """
      SELECT new io.repsy.os.server.protocols.golang.shared.go_module.dtos.GoModuleVersionListItem(
          v.id, v.version, v.goVersion, v.createdAt
      )
      FROM GoModuleVersion v
      WHERE v.goModule.id = :moduleId
      ORDER BY v.createdAt DESC
      """)
  List<GoModuleVersionListItem> findAllByModuleId(@Param("moduleId") UUID moduleId);
}
