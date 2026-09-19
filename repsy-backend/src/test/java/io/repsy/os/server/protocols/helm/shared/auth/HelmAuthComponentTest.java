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
package io.repsy.os.server.protocols.helm.shared.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("HelmAuthComponent")
class HelmAuthComponentTest {

  private final UserTxService userTxService = Mockito.mock(UserTxService.class);
  private final JwtUtils jwtUtils = Mockito.mock(JwtUtils.class);

  private final HelmAuthComponent authComponent =
      new HelmAuthComponent(
          this.userTxService, this.jwtUtils, Mockito.mock(DeployTokenService.class));

  /** RPS-986: an anonymous token is minted by Docker; its username claim is only a label. */
  @Test
  @DisplayName("handleBearerAuth refuses an anonymous token without looking up its username")
  void anonymousTokenIsRefused() {
    when(this.jwtUtils.extractAuthenticationType(anyString(), any(TokenRealm.class)))
        .thenReturn(AuthenticationType.ANONYMOUS);

    assertThatThrownBy(
            () ->
                this.authComponent.handleBearerAuth(
                    "Bearer signed.jwt.token", UUID.randomUUID(), Permission.READ))
        .isExactlyInstanceOf(UnAuthorizedException.class)
        .hasMessage(ErrorConstants.UN_AUTHORIZED);
    verify(this.userTxService, never()).getUserByUsernameOptional(anyString());
  }
}
