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
package io.repsy.os.shared.auth.services;

import io.repsy.os.generated.model.LoginInfo;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.user.dtos.UserInfo;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/** Issues the access and refresh token of a panel session. */
@Component
@RequiredArgsConstructor
public class LoginInfoFactory {

  private final @NonNull JwtUtils jwtUtils;

  /**
   * Issues a token pair for {@code user}. Neither token outlives {@link AuthUtils#TIMEOUT_SESSION}
   * after {@code sessionStart}, so refreshing cannot extend a session past that point.
   */
  public @NonNull LoginInfo create(
      final @NonNull UserInfo user, final @NonNull Instant sessionStart) {

    final var accessToken =
        this.jwtUtils.createSessionAccessToken(
            user.getId(),
            user.getUsername(),
            AuthUtils.boundBySession(AuthUtils.TIMEOUT_ACCESS_TOKEN, sessionStart),
            sessionStart);

    final var refreshToken =
        this.jwtUtils.createRefreshToken(
            user.getId(),
            user.getUsername(),
            AuthUtils.boundBySession(AuthUtils.TIMEOUT_REFRESH_TOKEN, sessionStart),
            sessionStart,
            user.getTokenVersion());

    return LoginInfo.builder()
        .username(user.getUsername())
        .token(accessToken)
        .refreshToken(refreshToken)
        .build();
  }
}
