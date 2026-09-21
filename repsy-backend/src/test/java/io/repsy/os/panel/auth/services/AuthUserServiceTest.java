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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.auth.AuthFailureThrottle;
import io.repsy.os.server.shared.auth.AuthThrottleProperties;
import io.repsy.os.shared.auth.dtos.RefreshTokenClaims;
import io.repsy.os.shared.auth.services.LoginInfoFactory;
import io.repsy.os.shared.auth.services.RefreshTokenService;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.user.mappers.UserConverter;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

@DisplayName("AuthUserService")
class AuthUserServiceTest {

  // A real UserTxService over an empty repository: the lookup itself is under test.
  private final AuthUserService service =
      new AuthUserService(
          new UserTxService(Mockito.mock(UserRepository.class), Mockito.mock(UserConverter.class)),
          Mockito.mock(LoginInfoFactory.class),
          Mockito.mock(RefreshTokenService.class),
          Mockito.mock(ApplicationEventPublisher.class),
          new AuthFailureThrottle(AuthThrottleProperties.disabled()));

  /** RPS-962: a refresh token of a deleted user is an authentication failure, not a 404. */
  @Test
  @DisplayName("refreshToken answers unAuthorized for a valid token whose user no longer exists")
  void refreshTokenUserNoLongerExists() {
    final var claims =
        new RefreshTokenClaims(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now(), 0);

    assertThatThrownBy(() -> this.service.refreshToken(claims))
        .isExactlyInstanceOf(UnAuthorizedException.class)
        .hasMessage(ErrorConstants.UN_AUTHORIZED);
  }
}
