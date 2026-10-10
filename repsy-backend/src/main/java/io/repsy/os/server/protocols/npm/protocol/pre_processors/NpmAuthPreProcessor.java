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

import static io.repsy.os.shared.auth.utils.AuthUtils.AUTH_BASIC;
import static io.repsy.os.shared.auth.utils.AuthUtils.AUTH_BEARER;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProcessor;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.server.protocols.npm.shared.auth.services.NpmAuthenticatorImpl;
import io.repsy.os.server.shared.auth.AuthChallenges;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.shared.auth.BasicAuthChallenge;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.handlers.HandlerPropertyKeys;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@NullMarked
public class NpmAuthPreProcessor extends ProtocolProcessor {

  private static final int PRIORITY = 100;
  private static final String CHALLENGE = BasicAuthChallenge.REPSY;

  /**
   * The challenge of a refused Bearer credential (RPS-1209). The npm client reads only the first
   * value of {@code WWW-Authenticate}, splits it and takes the first scheme it knows: {@code
   * Bearer} makes it print "your authentication token seems to be invalid ... npm login", while a
   * lone {@code Basic} makes it tell a token user "Incorrect or missing password".
   * registry.npmjs.org sends both schemes as well.
   */
  private static final String BEARER_CHALLENGE =
      "Bearer realm=\"" + BasicAuthChallenge.REALM + "\", " + CHALLENGE;

  private static final String URL_PROPERTIES_KEY = "urlProperties";
  private static final String REQUIRE_AUTHENTICATION_KEY =
      HandlerPropertyKeys.REQUIRE_AUTHENTICATION;

  private final NpmAuthenticatorImpl authenticator;
  private final NpmProtocolProvider provider;

  @PostConstruct
  public void register() {
    this.provider.registerPreProcessor(this);
  }

  @Override
  protected int getPriority() {
    return PRIORITY;
  }

  @Override
  protected ProcessorResult process(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Map<String, Object> properties) {

    final var repoInfo = this.getRepoInfo(context);

    if (this.shouldSkipAuthentication(repoInfo, properties)) {
      return ProcessorResult.next();
    }

    final var permission = (Permission) properties.get(HandlerPropertyKeys.PERMISSION);

    final var authHeader = this.authenticator.emulateAuthHeader(request);

    try {
      this.authenticate(authHeader, repoInfo.getStorageKey(), permission);
    } catch (final UnAuthorizedException ex) {
      throw AuthChallenges.challenged(ex, challengeFor(authHeader));
    }

    return ProcessorResult.next();
  }

  /** The challenge for a refused request: both schemes when it carried a Bearer credential. */
  private static String challengeFor(final @Nullable String authHeader) {

    return authHeader != null && authHeader.startsWith(AUTH_BEARER) ? BEARER_CHALLENGE : CHALLENGE;
  }

  private void authenticate(
      final @Nullable String authHeader, final UUID repoId, final Permission permission) {

    if (authHeader == null) {
      throw new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
    }

    this.authenticateRequest(authHeader, repoId, permission);
  }

  private void authenticateRequest(
      final String authHeader, final UUID repoId, final Permission permission) {

    switch (authHeader) {
      case final String header when header.startsWith(AUTH_BASIC) ->
          this.authenticator.handleBasicAuth(header, permission, repoId);
      case final String header when header.startsWith(AUTH_BEARER) ->
          this.authenticator.handleBearerAuth(header, repoId, permission);
      default -> throw new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
    }
  }

  private boolean shouldSkipAuthentication(
      final RepoInfo repoInfo, final Map<String, Object> properties) {

    final var skipPreProcessor =
        (boolean) properties.getOrDefault(HandlerPropertyKeys.SKIP_PRE_PROCESSOR, false);

    if (skipPreProcessor) {
      return true;
    }

    // whoami is about who is asking, so it needs credentials on a public repo too.
    final var requireAuthentication =
        (boolean) properties.getOrDefault(REQUIRE_AUTHENTICATION_KEY, false);

    if (requireAuthentication) {
      return false;
    }

    final var writeOperation = (boolean) properties.get(HandlerPropertyKeys.WRITE_OPERATION);

    return !repoInfo.isPrivateRepo() && !writeOperation;
  }

  private RepoInfo getRepoInfo(final ProtocolContext context) {

    return context.<UrlParserProperties>getProperty(URL_PROPERTIES_KEY).getRepoInfo();
  }
}
