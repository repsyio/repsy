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

import io.repsy.os.server.protocols.ruby.shared.ruby_gem.dtos.GemVersionCompactItem;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.dtos.GemVersionListItem;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.entities.RubyGemVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RubyGemVersionRepository extends JpaRepository<RubyGemVersion, UUID> {

  Optional<RubyGemVersion> findByGemIdAndVersionAndPlatform(
      UUID gemId, String version, String platform);

  List<RubyGemVersion> findByGemIdAndVersion(UUID gemId, String version);

  Optional<RubyGemVersion> findFirstByGemIdAndYankedFalseOrderByCreatedAtDesc(UUID gemId);

  long countByGemIdAndYankedFalse(UUID gemId);

  @Query(
      """
      select gv.version as version, gv.platform as platform,
        gv.yanked as yanked, gv.createdAt as createdAt
      from RubyGemVersion gv
      where gv.gem.id = :gemId
        and (:version is null or gv.version like %:version%)
      """)
  Page<GemVersionListItem> findAllByGemId(UUID gemId, String version, Pageable pageable);

  @Query(
      """
      select gv.id as gemVersionId, g.name as gemName,
        gv.version as version, gv.platform as platform,
        gv.checksum as checksum, gv.yanked as yanked,
        gv.createdAt as createdAt
      from RubyGemVersion gv join gv.gem g
      where gv.gem.id = :gemId
      order by gv.createdAt
      """)
  List<GemVersionCompactItem> findAllCompactByGemId(UUID gemId);

  @Query(
      """
      select gv.id as gemVersionId, g.name as gemName,
        gv.version as version, gv.platform as platform,
        gv.checksum as checksum, gv.yanked as yanked,
        gv.createdAt as createdAt
      from RubyGemVersion gv join gv.gem g join g.repo r
      where r.id = :repoId
      order by g.name, gv.createdAt
      """)
  List<GemVersionCompactItem> findAllCompactByRepoId(UUID repoId);

  @Query(
      """
      select gv.id as gemVersionId, g.name as gemName,
        gv.version as version, gv.platform as platform,
        gv.checksum as checksum, gv.yanked as yanked,
        gv.createdAt as createdAt
      from RubyGemVersion gv join gv.gem g join g.repo r
      where r.id = :repoId and gv.yanked = false
      order by g.name, gv.createdAt
      """)
  List<GemVersionCompactItem> findAllNonYankedCompactByRepoId(UUID repoId);
}
