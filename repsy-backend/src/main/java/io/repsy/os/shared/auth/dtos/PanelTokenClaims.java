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
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The claims a panel endpoint needs from a verified access token, read from one decode of it.
 *
 * @param userId the {@code sub} claim: the id of the user the token was issued to, or {@code null}
 *     if it is not an id, which no token of ours ever was
 * @param username the user the token was issued to
 * @param tokenVersion the user's {@code token_version} when the token was issued; a later change of
 *     it revokes the token
 * @param sessionStart when the login this token descends from happened; carried unchanged across
 *     refreshes so the session has an absolute lifetime
 */
public record PanelTokenClaims(
    @Nullable UUID userId, String username, int tokenVersion, Instant sessionStart) {

  /**
   * Whether the token was issued to exactly this user row and to the state of it that still stands:
   * same id (a username that was freed and registered again is another user, RPS-1604) and same
   * {@code token_version} (a password change, a username change or an admin edit ends the token,
   * RPS-1552). The user is the one the {@code username} claim named.
   */
  public boolean issuedTo(final UserInfo user) {
    return user.getId().equals(this.userId) && this.tokenVersion == user.getTokenVersion();
  }
}
