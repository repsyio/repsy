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
package io.repsy.os.server.protocols.pypi.shared.auth.services;

import static io.repsy.os.shared.auth.utils.AuthUtils.extractCredentialsFromBasicToken;
import static io.repsy.os.shared.auth.utils.AuthUtils.removeBasicPrefix;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.server.shared.auth.ProtocolAuthService;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Service;

@Service
@NullMarked
public class PypiAuthComponent extends ProtocolAuthService {

  public PypiAuthComponent(
      final UserTxService userTxService,
      final JwtUtils jwtUtils,
      final DeployTokenService deployTokenService) {

    super(userTxService, jwtUtils, deployTokenService);
  }

  public void handleBasicAuthWithToken(
      final String authHeader, final Permission permission, final UUID repoId) {

    final var basicToken = removeBasicPrefix(authHeader);

    final var credentials = extractCredentialsFromBasicToken(basicToken);

    if (credentials == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    if (super.tryAuthorizeWithDeployToken(repoId, credentials.getPassword(), permission)) {
      return;
    }

    super.handleUsernamePasswordAuthentication(credentials, permission);
  }
}
