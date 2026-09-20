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
package io.repsy.os.shared.auth;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.shared.auth.dtos.PanelSession;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.services.UserTxService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class PanelAuthHelper {

  private static final @NonNull String ACCESS_DENIED = "accessDenied";

  private final @NonNull JwtUtils jwtUtils;
  private final @NonNull UserTxService userTxService;

  public @NonNull UserInfo authenticate(final @NonNull String authHeader) {
    return this.authenticateSession(authHeader).user();
  }

  /**
   * Authenticates like {@link #authenticate} and also returns the session start of the token, for
   * endpoints that mint new tokens. The token is verified and decoded once.
   */
  public @NonNull PanelSession authenticateSession(final @NonNull String authHeader) {
    final var claims = this.jwtUtils.extractPanelClaims(authHeader);
    final var user = this.userTxService.getAuthenticatedUserByUsername(claims.username());

    if (claims.tokenVersion() != user.getTokenVersion()) {
      throw new UnAuthorizedException("sessionExpired");
    }

    return new PanelSession(user, claims.sessionStart());
  }

  public void requireAdmin(final @NonNull UserInfo userInfo) {
    if (userInfo.getRole() != UserRole.ADMIN) {
      throw new AccessNotAllowedException(ACCESS_DENIED);
    }
  }
}
