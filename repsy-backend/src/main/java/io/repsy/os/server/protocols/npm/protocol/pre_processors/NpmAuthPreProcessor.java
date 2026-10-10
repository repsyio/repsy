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
package io.repsy.os.server.protocols.npm.protocol.pre_processors;

import static io.repsy.os.shared.auth.utils.AuthUtils.AUTH_BEARER;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.os.server.protocols.npm.shared.auth.services.NpmAuthenticatorImpl;
import io.repsy.os.server.shared.auth.BasicOrBearerAuthPreProcessor;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.shared.auth.BasicAuthChallenge;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * npm: Basic or Bearer. {@code whoami} needs credentials on a public repository too (its route sets
 * {@code requireAuthentication}), and a refused Bearer token is challenged with both schemes.
 */
@Component
@NullMarked
public class NpmAuthPreProcessor extends BasicOrBearerAuthPreProcessor<NpmAuthenticatorImpl> {

  /**
   * The challenge of a refused Bearer credential (RPS-1209). The npm client reads only the first
   * value of {@code WWW-Authenticate}, splits it and takes the first scheme it knows: {@code
   * Bearer} makes it print "your authentication token seems to be invalid ... npm login", while a
   * lone {@code Basic} makes it tell a token user "Incorrect or missing password".
   * registry.npmjs.org sends both schemes as well.
   */
  private static final String BEARER_CHALLENGE =
      "Bearer realm=\"" + BasicAuthChallenge.REALM + "\", " + BasicAuthChallenge.REPSY;

  public NpmAuthPreProcessor(
      final NpmAuthenticatorImpl authenticator, final NpmProtocolProvider provider) {
    super(authenticator, provider);
  }

  @Override
  protected ProcessorResult missingCredential(final HttpServletRequest request) {
    throw new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
  }

  /** The challenge for a refused request: both schemes when it carried a Bearer credential. */
  @Override
  protected String challenge(final @Nullable String credential) {

    return credential != null && credential.startsWith(AUTH_BEARER)
        ? BEARER_CHALLENGE
        : BasicAuthChallenge.REPSY;
  }
}
