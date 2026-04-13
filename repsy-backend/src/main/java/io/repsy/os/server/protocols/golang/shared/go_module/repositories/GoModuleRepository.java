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

import io.repsy.os.server.protocols.golang.shared.go_module.dtos.GoModuleListItem;
import io.repsy.os.server.protocols.golang.shared.go_module.entities.GoModule;
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
public interface GoModuleRepository extends JpaRepository<GoModule, UUID> {

  Optional<GoModule> findByRepoIdAndModulePath(UUID repoId, String modulePath);

  @Query(
      """
      select m.id as id, m.modulePath as modulePath, m.createdAt as createdAt
      from GoModule m
      where m.repo.id = :repoId
      """)
  Page<GoModuleListItem> findAllByRepoId(UUID repoId, Pageable pageable);

  @Query(
      """
      select m.id as id, m.modulePath as modulePath, m.createdAt as createdAt
      from GoModule m
      where m.repo.id = :repoId
        and lower(m.modulePath) like lower(concat('%', :search, '%'))
      """)
  Page<GoModuleListItem> findAllByRepoIdContainsModulePath(
      UUID repoId, String search, Pageable pageable);
}
