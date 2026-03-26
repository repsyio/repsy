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
import io.repsy.core.events.UserLoginEvent;
import io.repsy.os.panel.auth.dtos.LoginForm;
import io.repsy.os.panel.shared.auth.dtos.LoginInfo;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.services.UserTxService;
import java.util.UUID;
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

  private final @NonNull UserTxService userTxService;
  private final @NonNull JwtUtils jwtUtils;
  private final @NonNull ApplicationEventPublisher eventPublisher;

  @Transactional
  public @NonNull LoginInfo login(final @NonNull LoginForm form) {

    final var user = this.userTxService.getUserByUsername(form.getUsername());

    this.checkPassword(user, form);

    // Publish login event for lastLoginAt update
    this.eventPublisher.publishEvent(new UserLoginEvent(user.getUsername()));

    return this.createLoginInfo(user);
  }

  public @NonNull LoginInfo refreshToken(final @NonNull UUID userId) {

    final var user = this.userTxService.getUserById(userId);

    return this.createLoginInfo(user);
  }

  private void checkPassword(final @NonNull UserInfo user, final @NonNull LoginForm form) {

    final var hash = DigestUtils.sha256Hex(form.getPassword() + user.getSalt());

    if (!hash.equals(user.getHash())) {
      throw new AccessNotAllowedException("wrongPassword");
    }
  }

  private @NonNull LoginInfo createLoginInfo(final @NonNull UserInfo user) {

    final var accessToken =
        this.jwtUtils.createTokenWithDuration(
            user.getId(), user.getUsername(), AuthUtils.TIMEOUT_ACCESS_TOKEN);

    final var refreshToken =
        this.jwtUtils.createTokenWithDuration(
            user.getId(), user.getUsername(), AuthUtils.TIMEOUT_REFRESH_TOKEN);

    return LoginInfo.builder()
        .username(user.getUsername())
        .token(accessToken)
        .refreshToken(refreshToken)
        .build();
  }
}
