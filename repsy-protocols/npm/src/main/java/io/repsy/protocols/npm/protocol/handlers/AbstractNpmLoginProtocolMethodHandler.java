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
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.shared.auth.dtos.LoginRequest;
import io.repsy.protocols.npm.shared.auth.dtos.LoginResponse;
import io.repsy.protocols.npm.shared.auth.services.NpmAuthComponent;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

@NullMarked
public abstract class AbstractNpmLoginProtocolMethodHandler<ID> implements ProtocolMethodHandler {

  private static final Pattern LOGIN_PATTERN = Pattern.compile("^/-/user/.*");

  private final PathParser basePathParser;
  private final NpmAuthComponent<ID> authComponent;
  private final ObjectMapper objectMapper;

  public AbstractNpmLoginProtocolMethodHandler(
      @Qualifier("npmPathParser") final PathParser basePathParser,
      final NpmAuthComponent<ID> authComponent,
      final ObjectMapper objectMapper,
      final NpmProtocolProvider provider) {

    this.basePathParser = basePathParser;
    this.authComponent = authComponent;
    this.objectMapper = objectMapper;

    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.PUT);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of(
        "permission", Permission.NONE,
        "writeOperation", false,
        "skipPreProcessor", true);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.PUT.equals(HttpMethod.valueOf(request.getMethod()))) {
        return Optional.empty();
      }

      final var parsedPathOpt = this.basePathParser.parse(request);
      if (parsedPathOpt.isEmpty()) {
        return Optional.empty();
      }

      final var relativePath = ProtocolContextUtils.getRelativePath(parsedPathOpt.get()).getPath();

      if (!LOGIN_PATTERN.matcher(relativePath).matches()) {
        return Optional.empty();
      }

      return parsedPathOpt;
    };
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
          this.authComponent.authenticateRepoUser(
              repoInfo, loginRequest.getName(), loginRequest.getPassword());

      final var loginResponse =
          LoginResponse.builder()
              .rev("_we_dont_use_revs_any_more")
              .id("org.couchdb.user:undefined")
              .ok(true)
              .token(sessionToken)
              .build();

      return ResponseEntity.status(HttpStatus.CREATED).body(loginResponse);

    } catch (final UnAuthorizedException e) {
      final var loginResponse = LoginResponse.builder().ok(false).build();

      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .header(WWW_AUTHENTICATE, "Basic realm=\"Repsy Managed Registry\"")
          .body(loginResponse);
    }
  }
}
