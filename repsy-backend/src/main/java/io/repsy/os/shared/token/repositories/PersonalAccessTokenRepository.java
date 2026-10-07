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
package io.repsy.os.shared.token.repositories;

import io.repsy.os.shared.token.dtos.PersonalAccessTokenListItem;
import io.repsy.os.shared.token.entities.PersonalAccessToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface PersonalAccessTokenRepository extends JpaRepository<PersonalAccessToken, UUID> {

  /**
   * The token whose secret hashes to {@code tokenHash}, with its user in the same query: whoever
   * authenticates with a token needs the user next, so the lazy association would cost a second
   * round trip on every such request.
   */
  @EntityGraph(attributePaths = "user")
  @NonNull Optional<PersonalAccessToken> findByTokenHash(@NonNull String tokenHash);

  @EntityGraph(attributePaths = "user")
  @NonNull Optional<PersonalAccessToken> findByUserIdAndId(@NonNull UUID userId, @NonNull UUID id);

  @NonNull Page<PersonalAccessTokenListItem> findAllByUserId(
      @NonNull UUID userId, @NonNull Pageable pageable);

  long countByUserId(@NonNull UUID userId);

  /**
   * How many tokens of the user have not expired at {@code now}: those whose expiration date is
   * after it. A token expires at its date, so one that expires exactly at {@code now} is expired
   * (see {@code PersonalAccessTokenInfo#isExpired}).
   */
  long countByUserIdAndExpirationDateAfter(@NonNull UUID userId, @NonNull Instant now);

  @Modifying
  @Query("update PersonalAccessToken pat set pat.lastUsedAt = :now where pat.id = :tokenId")
  void updateLastUsedTime(@NonNull UUID tokenId, @NonNull Instant now);
}
