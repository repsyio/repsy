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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.mappers.UserConverter;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("PanelAuthHelper")
class PanelAuthHelperTest {

  private static final String AUTH_HEADER = "Bearer signed.jwt.token";

  private final JwtUtils jwtUtils = Mockito.mock(JwtUtils.class);
  private final UserRepository userRepository = Mockito.mock(UserRepository.class);
  private final UserConverter userConverter = Mockito.mock(UserConverter.class);

  // A real UserTxService over a mocked repository: the lookup itself is under test.
  private final PanelAuthHelper helper =
      new PanelAuthHelper(
          this.jwtUtils, new UserTxService(this.userRepository, this.userConverter));

  @Test
  @DisplayName("authenticate returns the user of a valid panel token")
  void authenticateReturnsUser() {
    final var user = new User();
    final var userInfo = UserInfo.builder().username("alice").build();
    when(this.jwtUtils.verifyAndExtractUsername(AUTH_HEADER, TokenRealm.PANEL)).thenReturn("alice");
    when(this.userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
    when(this.userConverter.toUserInfo(user)).thenReturn(userInfo);

    assertThat(this.helper.authenticate(AUTH_HEADER)).isSameAs(userInfo);
  }

  /** RPS-962: a valid token whose user is gone is an authentication failure, not a 404. */
  @Test
  @DisplayName("authenticate answers unAuthorized for a valid token whose user no longer exists")
  void authenticateTokenUserNoLongerExists() {
    when(this.jwtUtils.verifyAndExtractUsername(AUTH_HEADER, TokenRealm.PANEL)).thenReturn("ghost");

    assertThatThrownBy(() -> this.helper.authenticate(AUTH_HEADER))
        .isExactlyInstanceOf(UnAuthorizedException.class)
        .hasMessage(ErrorConstants.UN_AUTHORIZED);
  }
}
