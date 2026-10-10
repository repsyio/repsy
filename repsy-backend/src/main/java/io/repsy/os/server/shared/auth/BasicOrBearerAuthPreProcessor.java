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

import static io.repsy.os.shared.auth.utils.AuthUtils.AUTH_BASIC;
import static io.repsy.os.shared.auth.utils.AuthUtils.AUTH_BEARER;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProcessor;
import io.repsy.libs.protocol.router.ProtocolProvider;
import io.repsy.os.server.shared.utils.UrlPropertiesUtils;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.shared.auth.AuthChallenges;
import io.repsy.protocols.shared.auth.BasicAuthChallenge;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.handlers.HandlerPropertyKeys;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The authentication pre-processor of a protocol (RPS-2062): one template for the nine formats,
 * which used to repeat the same steps with small differences.
 *
 * <ol>
 *   <li>A request the route lets through without credentials passes ({@link
 *       #shouldSkipAuthentication}).
 *   <li>The credential is read ({@link #credential}, the {@code Authorization} header unless a
 *       format also takes another one) and, for the formats whose client sends a bare token, given
 *       the {@code Bearer} scheme ({@link #acceptsBareToken}).
 *   <li>A format may authenticate the request another way first ({@link #authenticateOtherwise}).
 *   <li>No credential: {@link #missingCredential}, by default a 401 without a body and with the
 *       Basic challenge.
 *   <li>A {@code Basic} or {@code Bearer} credential is checked against the repository and the
 *       permission of the route ({@link #authenticateBasic}, {@link #authenticateBearer}); any
 *       other scheme is refused.
 *   <li>A refused credential: {@link #refused}, by default the failure with the challenge of the
 *       format ({@link #challenge}) attached, which {@code ProtocolErrorAdvice} writes.
 *   <li>An accepted credential may have to pass a further check ({@link #afterAuthentication}).
 * </ol>
 *
 * <p>The hooks are what the formats differ in: Docker takes only its registry token and checks the
 * scope the token was issued for, NuGet also reads the {@code X-NuGet-ApiKey} header and answers
 * every refusal the same way, Maven takes a single-path download token, Go writes its refusals as
 * text for the go command, npm adds the Bearer scheme to the challenge of a refused token, Helm
 * answers a request without credentials in the OCI error format, and PyPI accepts a token as the
 * Basic password. Nothing a hook does may make a check weaker than this class's default.
 *
 * @param <A> the authenticator of the format
 */
@NullMarked
public abstract class BasicOrBearerAuthPreProcessor<A extends ProtocolAuthService>
    extends ProtocolProcessor {

  private static final int PRIORITY = 100;

  protected final A authenticator;

  protected BasicOrBearerAuthPreProcessor(final A authenticator, final ProtocolProvider provider) {
    this.authenticator = authenticator;
    provider.registerPreProcessor(this);
  }

  @Override
  protected final int getPriority() {
    return PRIORITY;
  }

  /**
   * Public (the router only needs it protected) so that a format's unit test, which lives in the
   * format's package, can call it on the format's pre-processor.
   */
  @Override
  public final ProcessorResult process(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Map<String, Object> properties) {

    final var repoInfo = UrlPropertiesUtils.getRepoInfo(context);

    if (this.shouldSkipAuthentication(repoInfo, properties)) {
      return ProcessorResult.next();
    }

    final var permission = (Permission) properties.get(HandlerPropertyKeys.PERMISSION);
    final var credential = this.credential(request);

    try {
      if (this.authenticateOtherwise(context, request, repoInfo, permission)) {
        return ProcessorResult.next();
      }

      if (credential == null) {
        return this.missingCredential(request);
      }

      this.authenticate(this.withScheme(credential), repoInfo.getStorageKey(), permission);
    } catch (final UnAuthorizedException ex) {
      return this.refused(ex, credential, context, request, properties);
    }

    this.afterAuthentication(context, request, repoInfo, permission, credential);

    return ProcessorResult.next();
  }

  /**
   * Whether the request passes without credentials: when its route says so ({@code
   * skipPreProcessor}), and otherwise only for a read of a public repository. A route that asks for
   * the caller's identity ({@code requireAuthentication}) is authenticated even on a public
   * repository. A route that does not say whether it writes counts as a write when it needs more
   * than {@link Permission#READ}, so a missing flag never lets a write through.
   */
  protected boolean shouldSkipAuthentication(
      final RepoInfo repoInfo, final Map<String, Object> properties) {

    if (isTrue(properties, HandlerPropertyKeys.SKIP_PRE_PROCESSOR)) {
      return true;
    }

    if (isTrue(properties, HandlerPropertyKeys.REQUIRE_AUTHENTICATION)) {
      return false;
    }

    return !repoInfo.isPrivateRepo() && !isWriteOperation(properties);
  }

  private static boolean isWriteOperation(final Map<String, Object> properties) {

    final var writeOperation = properties.get(HandlerPropertyKeys.WRITE_OPERATION);

    if (writeOperation != null) {
      return (boolean) writeOperation;
    }

    final var permission = properties.get(HandlerPropertyKeys.PERMISSION);

    return permission == Permission.WRITE || permission == Permission.MANAGE;
  }

  private static boolean isTrue(final Map<String, Object> properties, final String key) {
    return (boolean) properties.getOrDefault(key, false);
  }

  /** The credential the request carries: its {@code Authorization} header. */
  protected @Nullable String credential(final HttpServletRequest request) {
    return this.authenticator.emulateAuthHeader(request);
  }

  /**
   * Whether the client of the format sends a token without a scheme, which is then read as a {@code
   * Bearer} token. Off by default: a credential without a known scheme is refused.
   */
  protected boolean acceptsBareToken() {
    return false;
  }

  private String withScheme(final String credential) {

    if (!this.acceptsBareToken()
        || credential.startsWith(AUTH_BASIC)
        || credential.startsWith(AUTH_BEARER)) {
      return credential;
    }

    return AUTH_BEARER + credential;
  }

  /**
   * Authenticates the request by something other than its credential, before the credential is
   * looked at. Returns {@code true} when that succeeded; throws {@link UnAuthorizedException} when
   * it was tried and failed. By default nothing else is accepted.
   */
  protected boolean authenticateOtherwise(
      final ProtocolContext context,
      final HttpServletRequest request,
      final RepoInfo repoInfo,
      final Permission permission) {

    return false;
  }

  /**
   * The answer to a request without credentials: by default a 401 with the challenge and no body.
   * May throw {@link UnAuthorizedException} to have it answered by {@link #refused}.
   */
  protected ProcessorResult missingCredential(final HttpServletRequest request) {

    return ProcessorResult.of(
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, this.challenge(null))
            .build());
  }

  private void authenticate(
      final String credential, final UUID repoId, final Permission permission) {

    switch (credential) {
      case final String basic when basic.startsWith(AUTH_BASIC) ->
          this.authenticateBasic(basic, repoId, permission);
      case final String bearer when bearer.startsWith(AUTH_BEARER) ->
          this.authenticateBearer(bearer, repoId, permission);
      default -> throw new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
    }
  }

  /** Checks a {@code Basic} credential. */
  protected void authenticateBasic(
      final String credential, final UUID repoId, final Permission permission) {

    this.authenticator.handleBasicAuth(credential, permission, repoId);
  }

  /** Checks a {@code Bearer} credential. */
  protected void authenticateBearer(
      final String credential, final UUID repoId, final Permission permission) {

    this.authenticator.handleBearerAuth(credential, repoId, permission);
  }

  /**
   * The answer to a refused credential: by default the failure, thrown with {@link
   * #challenge(String)} attached.
   */
  protected ProcessorResult refused(
      final UnAuthorizedException exception,
      final @Nullable String credential,
      final ProtocolContext context,
      final HttpServletRequest request,
      final Map<String, Object> properties) {

    throw AuthChallenges.challenged(exception, this.challenge(credential));
  }

  /** The {@code WWW-Authenticate} challenge of a 401, given the credential that was refused. */
  protected String challenge(final @Nullable String credential) {
    return BasicAuthChallenge.REPSY;
  }

  /**
   * A further check of a request whose credential was accepted. Throws to refuse it. By default
   * there is none.
   */
  protected void afterAuthentication(
      final ProtocolContext context,
      final HttpServletRequest request,
      final RepoInfo repoInfo,
      final Permission permission,
      final @Nullable String credential) {

    // Nothing further by default.
  }
}
