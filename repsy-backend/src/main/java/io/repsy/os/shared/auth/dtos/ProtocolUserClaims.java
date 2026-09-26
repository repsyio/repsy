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
package io.repsy.os.shared.auth.dtos;

import io.repsy.os.shared.user.dtos.UserInfo;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What a verified protocol token says about the user it was issued to.
 *
 * @param userId The {@code sub} claim, the id of the user the token was issued to, or {@code null}
 *     if it is not an id, which no token of a user ever was (RPS-1604)
 * @param username The user's name at the time of the login
 * @param tokenVersion The user's token version at the time of the login, or {@code null} for a
 *     token minted before the claim existed, which is accepted until it expires (RPS-1552)
 */
public record ProtocolUserClaims(
    @Nullable UUID userId, @NonNull String username, @Nullable Integer tokenVersion) {

  /**
   * Whether the token was issued to exactly this user row: the same id, so that a username that was
   * freed and registered again does not inherit the token of its former owner (RPS-1604), and the
   * same version, unless the token has none (see {@link #tokenVersion}).
   */
  public boolean issuedTo(final @NonNull UserInfo user) {
    return user.getId().equals(this.userId)
        && (this.tokenVersion == null || this.tokenVersion == user.getTokenVersion());
  }
}
