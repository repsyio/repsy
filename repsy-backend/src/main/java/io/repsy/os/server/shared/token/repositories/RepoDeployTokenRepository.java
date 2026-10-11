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
package io.repsy.os.server.shared.token.repositories;

import io.repsy.os.server.shared.token.dtos.DeployTokenInfoListItem;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface RepoDeployTokenRepository extends JpaRepository<RepoDeployToken, UUID> {

  Optional<RepoDeployToken> findByRepoIdAndToken(UUID repoId, String token);

  Optional<RepoDeployToken> findByRepoIdAndId(UUID repoId, UUID id);

  Optional<RepoDeployToken> findByTokenAndRepoType(String token, RepoType repoType);

  Optional<RepoDeployToken> findByToken(String token);

  Page<DeployTokenInfoListItem> findAllByRepoId(UUID repoId, Pageable pageable);

  @Modifying
  @Query("update RepoDeployToken rdt set rdt.lastUsedAt = :now where rdt.id = :tokenId")
  void updateLastUsedTime(UUID tokenId, Instant now);
}
