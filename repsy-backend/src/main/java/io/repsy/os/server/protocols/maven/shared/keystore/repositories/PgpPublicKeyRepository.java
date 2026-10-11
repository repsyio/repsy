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
package io.repsy.os.server.protocols.maven.shared.keystore.repositories;

import io.repsy.os.server.protocols.maven.shared.keystore.entities.PgpPublicKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PgpPublicKeyRepository extends JpaRepository<PgpPublicKey, UUID> {

  boolean existsByRepoIdAndFingerprint(UUID repoId, String fingerprint);

  @Query("select count(k) from PgpPublicKey k where k.repo.id = :repoId")
  long countByRepoId(@Param("repoId") UUID repoId);

  Optional<PgpPublicKey> findByIdAndRepoId(UUID id, UUID repoId);

  Page<PgpPublicKey> findAllByRepoId(UUID repoId, Pageable pageable);

  @Query("select k.armoredKey from PgpPublicKey k where k.repo.id = :repoId")
  List<String> findArmoredKeysByRepoId(@Param("repoId") UUID repoId);
}
