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
package io.repsy.os.server.protocols.pypi.protocol.pre_processors;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.os.server.protocols.pypi.shared.auth.services.PypiAuthenticator;
import io.repsy.os.server.shared.auth.BasicOrBearerAuthPreProcessor;
import io.repsy.protocols.pypi.protocol.PypiProtocolProvider;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/** PyPI: Basic (whose password may be a token, as twine sends it) or Bearer. */
@Component
@NullMarked
public class PypiAuthPreProcessor extends BasicOrBearerAuthPreProcessor<PypiAuthenticator> {

  public PypiAuthPreProcessor(
      final PypiAuthenticator authenticator, final PypiProtocolProvider provider) {
    super(authenticator, provider);
  }

  @Override
  protected ProcessorResult missingCredential(final HttpServletRequest request) {
    throw new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
  }

  @Override
  protected void authenticateBasic(
      final String credential, final UUID repoId, final Permission permission) {

    this.authenticator.handleBasicAuthWithToken(credential, permission, repoId);
  }
}
