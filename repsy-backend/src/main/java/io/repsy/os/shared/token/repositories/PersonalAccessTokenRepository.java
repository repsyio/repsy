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

import io.repsy.os.shared.token.dtos.PersonalAccessTokenKind;
import io.repsy.os.shared.token.dtos.PersonalAccessTokenListItem;
import io.repsy.os.shared.token.entities.PersonalAccessToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
  Optional<PersonalAccessToken> findByTokenHash(String tokenHash);

  /**
   * The token of {@code userId} with this id and kind, user included. A token of another user, or
   * of another kind, is not found: the personal token routes cannot reach a temporary token.
   */
  @EntityGraph(attributePaths = "user")
  Optional<PersonalAccessToken> findByUserIdAndIdAndKind(
      UUID userId, UUID id, PersonalAccessTokenKind kind);

  Page<PersonalAccessTokenListItem> findAllByUserIdAndKind(
      UUID userId, PersonalAccessTokenKind kind, Pageable pageable);

  long countByUserId(UUID userId);

  long countByUserIdAndKind(UUID userId, PersonalAccessTokenKind kind);

  /**
   * How many tokens of the user and kind have not expired at {@code now}: those whose expiration
   * date is after it. A token expires at its date, so one that expires exactly at {@code now} is
   * expired (see {@code PersonalAccessTokenInfo#isExpired}).
   */
  long countByUserIdAndKindAndExpirationDateAfter(
      UUID userId, PersonalAccessTokenKind kind, Instant now);

  /**
   * Deletes the temporary tokens of the user that were created before {@code createdAt}, which is
   * the newer token's own creation time: a newer token is never revoked by an older one.
   *
   * @return how many were deleted
   */
  @Modifying
  @Query(
      "delete from PersonalAccessToken pat where pat.user.id = :userId"
          + " and pat.kind = :kind and pat.createdAt < :createdAt")
  int deleteByUserIdAndKindCreatedBefore(
      UUID userId, PersonalAccessTokenKind kind, Instant createdAt);

  @Modifying
  @Query("update PersonalAccessToken pat set pat.lastUsedAt = :now where pat.id = :tokenId")
  void updateLastUsedTime(UUID tokenId, Instant now);
}
