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
package io.repsy.os.shared.connector.repositories;

import io.repsy.os.shared.connector.entities.ConnectorAuthorizationCode;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface ConnectorAuthorizationCodeRepository
    extends JpaRepository<ConnectorAuthorizationCode, UUID> {

  /**
   * The code whose secret hashes to {@code codeHash}, locked until the transaction ends, so two
   * exchanges of one code are serialized and only the first finds it.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(attributePaths = "user")
  Optional<ConnectorAuthorizationCode> findByCodeHash(String codeHash);

  /**
   * Deletes the codes that expired before {@code now}: nobody can exchange them any more.
   *
   * @return how many were deleted
   */
  @Modifying
  @Query("delete from ConnectorAuthorizationCode c where c.expiresAt < :now")
  int deleteExpired(Instant now);
}
