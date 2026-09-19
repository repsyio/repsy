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
package io.repsy.os.shared.auth.repositories;

import io.repsy.os.shared.auth.entities.RefreshToken;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update RefreshToken t set t.usedAt = :now "
          + "where t.id = :id and t.usedAt is null and t.revokedAt is null")
  int markUsed(@Param("id") UUID id, @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update RefreshToken t set t.revokedAt = :now "
          + "where t.familyId = :familyId and t.revokedAt is null")
  int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

  @Modifying
  @Query("delete from RefreshToken t where t.expiresAt < :now")
  int deleteExpired(@Param("now") Instant now);
}
