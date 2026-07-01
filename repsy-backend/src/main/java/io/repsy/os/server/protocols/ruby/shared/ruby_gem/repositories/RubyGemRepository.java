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
package io.repsy.os.server.protocols.ruby.shared.ruby_gem.repositories;

import io.repsy.os.server.protocols.ruby.shared.ruby_gem.dtos.GemListItem;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.dtos.GemNameProjection;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.entities.RubyGem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RubyGemRepository extends JpaRepository<RubyGem, UUID> {

  Optional<RubyGem> findByRepoIdAndName(UUID repoId, String name);

  @Query(
      """
      select g.name as name
      from RubyGem g
      where g.repo.id = :repoId
      order by g.name
      """)
  List<GemNameProjection> findAllNamesByRepoId(UUID repoId);

  @Query(
      """
      select g.name as name, g.latest as latest, max(gv.createdAt) as updatedAt
      from RubyGem g join g.versions gv
      where g.repo.id = :repoId and g.latest = gv.version
        and (:name is null or g.name like %:name%)
      group by g.id, g.name, g.latest
      """)
  Page<GemListItem> findAllByRepoIdContainsName(UUID repoId, String name, Pageable pageable);

  @Query("select g from RubyGem g where g.repo.id = :repoId order by g.name")
  List<RubyGem> findAllByRepoId(UUID repoId);

  @Modifying
  @Query(
      """
      update RubyGem g set g.versionsChecksum = :checksum
      where g.repo.id = :repoId and g.name = :gemName
      """)
  void updateVersionsChecksum(UUID repoId, String gemName, String checksum);
}
