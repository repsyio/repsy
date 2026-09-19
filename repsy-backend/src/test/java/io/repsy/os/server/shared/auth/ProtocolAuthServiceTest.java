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
package io.repsy.os.server.shared.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.user.services.UserTxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("ProtocolAuthService")
class ProtocolAuthServiceTest {

  private final ProtocolAuthService authService =
      new ProtocolAuthService(
          Mockito.mock(UserTxService.class),
          Mockito.mock(JwtUtils.class),
          Mockito.mock(DeployTokenService.class));

  @Test
  @DisplayName("authenticateUser rejects a missing Authorization header with unAuthorized")
  void authenticateUserRejectsNullHeader() {
    assertThatThrownBy(() -> this.authService.authenticateUser(null))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.UN_AUTHORIZED);
  }

  @Test
  @DisplayName("authenticateUser rejects an unsupported Authorization scheme with unAuthorized")
  void authenticateUserRejectsUnknownScheme() {
    assertThatThrownBy(() -> this.authService.authenticateUser("Digest abc"))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessageContaining(ErrorConstants.UN_AUTHORIZED);
  }
}
