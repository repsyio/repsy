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

import io.repsy.os.server.protocols.maven.shared.keystore.dtos.KeyStoreItem;
import io.repsy.os.server.protocols.maven.shared.keystore.entities.KeyStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface KeyStoreRepository extends JpaRepository<KeyStore, UUID> {

  boolean existsByAllowedKeyserverIdAndRepoId(@NonNull UUID allowedKeyserverId, @NonNull UUID repoId);

  @NonNull Optional<KeyStore> findByIdAndRepoId(@NonNull UUID id, @NonNull UUID repoId);

  @Query("""
      SELECT ks.id                     AS id,
             ak.id                     AS allowedKeyserverId,
             ak.host                   AS host,
             ak.displayName            AS displayName
      FROM KeyStore ks
      JOIN ks.allowedKeyserver ak
      WHERE ks.repo.id = :repoId
      """)
  @NonNull Page<KeyStoreItem> findAllByRepoId(@NonNull UUID repoId, @NonNull Pageable pageable);

  @Query("""
      SELECT ks.id                     AS id,
             ak.id                     AS allowedKeyserverId,
             ak.host                   AS host,
             ak.displayName            AS displayName
      FROM KeyStore ks
      JOIN ks.allowedKeyserver ak
      WHERE ks.repo.id = :repoId
      """)
  @NonNull List<KeyStoreItem> findAllByRepoId(@NonNull UUID repoId);
}
