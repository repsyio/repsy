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
package io.repsy.os.shared.token.dtos;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A personal access token as the rest of the backend reads it: who it belongs to, what it may do
 * and until when. It carries neither the secret nor the hash.
 *
 * @param id the id of the token
 * @param userId the id of the user it belongs to
 * @param username the username of that user
 * @param name the name the user gave it
 * @param scopes what it may do, in canonical order; the record keeps its own copy, which cannot be
 *     changed, because whoever reads the scopes decides what the token is allowed to do
 * @param expirationDate when it stops working
 * @param lastUsedAt when it last authenticated a request, or {@code null} if it never did
 * @param createdAt when it was created
 */
public record PersonalAccessTokenInfo(
    UUID id,
    UUID userId,
    String username,
    String name,
    Set<TokenScope> scopes,
    Instant expirationDate,
    @Nullable Instant lastUsedAt,
    Instant createdAt) {

  public PersonalAccessTokenInfo {
    final var copy = EnumSet.noneOf(TokenScope.class);

    // No scopes at all is a token that may do nothing: whatever failed to supply them, the
    // answer is never wider than what was given.
    if (scopes != null) {
      copy.addAll(scopes);
    }

    scopes = Collections.unmodifiableSet(copy);
  }

  /** Whether the token has stopped working: an expired one is refused, whatever its scopes. */
  public boolean isExpired() {
    return !Instant.now().isBefore(this.expirationDate);
  }
}
