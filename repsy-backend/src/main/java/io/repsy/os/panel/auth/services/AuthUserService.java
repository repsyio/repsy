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
package io.repsy.os.panel.auth.services;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.core.events.UserLoginEvent;
import io.repsy.os.generated.model.LoginForm;
import io.repsy.os.generated.model.LoginInfo;
import io.repsy.os.shared.auth.dtos.RefreshTokenClaims;
import io.repsy.os.shared.auth.services.LoginInfoFactory;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.services.UserTxService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AuthUserService {

  private static final @NonNull String INVALID_CREDENTIALS = "invalidCredentials";
  private static final @NonNull String REFRESH_TOKEN_EXPIRED = "refreshTokenExpired";
  private static final @NonNull UserInfo DUMMY_USER =
      UserInfo.builder()
          .salt("repsy-login-dummy")
          .hash("0000000000000000000000000000000000000000000000000000000000000000")
          .build();

  private final @NonNull UserTxService userTxService;
  private final @NonNull LoginInfoFactory loginInfoFactory;
  private final @NonNull ApplicationEventPublisher eventPublisher;

  @Transactional
  public @NonNull LoginInfo login(final @NonNull LoginForm form) {

    final UserInfo user;
    try {
      user = this.userTxService.getUserByUsername(form.getUsername());
    } catch (final ItemNotFoundException exception) {
      // Perform the same hash work for unknown usernames to avoid leaking account existence.
      this.checkPassword(DUMMY_USER, form);
      throw new AccessNotAllowedException(INVALID_CREDENTIALS);
    }

    this.checkPassword(user, form);

    // Publish login event for lastLoginAt update
    this.eventPublisher.publishEvent(new UserLoginEvent(user.getUsername()));

    return this.loginInfoFactory.create(user, Instant.now().truncatedTo(ChronoUnit.SECONDS));
  }

  public @NonNull LoginInfo refreshToken(final @NonNull RefreshTokenClaims claims) {

    final var user = this.userTxService.getAuthenticatedUserById(claims.userId());

    // A password or username change bumps the version, which revokes the older refresh tokens.
    if (claims.tokenVersion() != user.getTokenVersion()) {
      throw new UnAuthorizedException(REFRESH_TOKEN_EXPIRED);
    }

    // The tokens' own expiry is capped at the session end; this guards it independently.
    if (!Instant.now().isBefore(claims.sessionStart().plus(AuthUtils.TIMEOUT_SESSION))) {
      throw new UnAuthorizedException(REFRESH_TOKEN_EXPIRED);
    }

    return this.loginInfoFactory.create(user, claims.sessionStart());
  }

  private void checkPassword(final @NonNull UserInfo user, final @NonNull LoginForm form) {

    final var hash = DigestUtils.sha256Hex(form.getPassword() + user.getSalt());

    if (!hash.equals(user.getHash())) {
      throw new AccessNotAllowedException(INVALID_CREDENTIALS);
    }
  }
}
