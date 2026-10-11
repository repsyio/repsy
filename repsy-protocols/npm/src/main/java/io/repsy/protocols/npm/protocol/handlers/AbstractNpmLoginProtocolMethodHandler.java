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
package io.repsy.protocols.npm.protocol.handlers;

import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.shared.auth.dtos.LoginRequest;
import io.repsy.protocols.npm.shared.auth.dtos.NpmLoginResponse;
import io.repsy.protocols.npm.shared.auth.services.NpmAuthenticator;
import io.repsy.protocols.shared.auth.BasicAuthChallenge;
import io.repsy.protocols.shared.handlers.AbstractRoutedProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

public abstract class AbstractNpmLoginProtocolMethodHandler<ID>
    extends AbstractRoutedProtocolMethodHandler {

  private static final Pattern LOGIN_PATTERN = Pattern.compile("^/-/user/.*");

  private final NpmAuthenticator<ID> authenticator;
  private final ObjectMapper objectMapper;

  public AbstractNpmLoginProtocolMethodHandler(
      @Qualifier("npmPathParser") final PathParser basePathParser,
      final NpmAuthenticator<ID> authenticator,
      final ObjectMapper objectMapper,
      final NpmProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.NONE, HttpMethod.PUT)
            .writeOperation(false)
            .skipPreProcessor(true)
            .path(LOGIN_PATTERN.asMatchPredicate()),
        basePathParser,
        provider);
    this.authenticator = authenticator;
    this.objectMapper = objectMapper;
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    try {
      final var loginRequest =
          this.objectMapper.readValue(request.getInputStream(), LoginRequest.class);

      final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

      final var sessionToken =
          this.authenticator.authenticateRepoUser(
              repoInfo, loginRequest.getName(), loginRequest.getPassword());

      final var loginResponse =
          NpmLoginResponse.builder()
              .rev("_we_dont_use_revs_any_more")
              .id("org.couchdb.user:undefined")
              .ok(true)
              .token(sessionToken)
              .build();

      return ResponseEntity.status(HttpStatus.CREATED).body(loginResponse);

    } catch (final UnAuthorizedException e) {
      final var loginResponse = NpmLoginResponse.builder().ok(false).build();

      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .header(WWW_AUTHENTICATE, BasicAuthChallenge.REPSY)
          .body(loginResponse);
    }
  }
}
