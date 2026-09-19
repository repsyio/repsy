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
package io.repsy.os.panel.profile.controllers;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.os.panel.profile.services.ProfileService;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.user.mappers.UserConverter;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("ProfileController")
class ProfileControllerTest {

  private static final String AUTH_HEADER = "Bearer signed.jwt.token";

  private final JwtUtils jwtUtils = Mockito.mock(JwtUtils.class);
  private final UserRepository userRepository = Mockito.mock(UserRepository.class);

  // A real UserTxService over an empty repository: the lookup itself is under test.
  private final ProfileController controller =
      new ProfileController(
          this.jwtUtils,
          Mockito.mock(ProfileService.class),
          new UserTxService(this.userRepository, Mockito.mock(UserConverter.class)),
          Mockito.mock(RestResponseFactory.class));

  /** RPS-962: a valid token whose user is gone is an authentication failure, not a 404. */
  @Test
  @DisplayName("deleteProfile answers unAuthorized when the token's user no longer exists")
  void deleteProfileTokenUserNoLongerExists() {
    final var ghostId = UUID.randomUUID();
    when(this.jwtUtils.extractUserId(AUTH_HEADER, TokenRealm.PANEL)).thenReturn(ghostId);

    assertThatThrownBy(() -> this.controller.deleteProfile(AUTH_HEADER))
        .isExactlyInstanceOf(UnAuthorizedException.class)
        .hasMessage(ErrorConstants.UN_AUTHORIZED);
    verify(this.userRepository, never()).delete(any());
  }
}
