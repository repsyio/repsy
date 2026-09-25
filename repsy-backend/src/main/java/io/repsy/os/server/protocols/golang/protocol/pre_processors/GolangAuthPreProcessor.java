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
package io.repsy.os.server.protocols.golang.protocol.pre_processors;

import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProcessor;
import io.repsy.os.server.protocols.golang.shared.auth.services.GolangAuthComponent;
import io.repsy.os.server.shared.utils.ProtocolContextUtils;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.golang.protocol.GolangProtocolProvider;
import io.repsy.protocols.shared.auth.BasicAuthChallenge;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class GolangAuthPreProcessor extends ProtocolProcessor {

  private static final int PRIORITY = 100;

  private static final String AUTH_BEARER = "Bearer ";
  private static final String AUTH_BASIC = "Basic ";
  private static final @NonNull String PERMISSION_KEY = "permission";
  private static final @NonNull String WRITE_OPERATION_KEY = "writeOperation";

  private final @NonNull GolangAuthComponent authComponent;
  private final @NonNull MessageSource messageSource;

  public GolangAuthPreProcessor(
      final @NonNull GolangAuthComponent authComponent,
      final @NonNull MessageSource messageSource,
      final @NonNull GolangProtocolProvider provider) {

    this.authComponent = authComponent;
    this.messageSource = messageSource;
    provider.registerPreProcessor(this);
  }

  @Override
  protected int getPriority() {
    return PRIORITY;
  }

  @Override
  protected @NonNull ProcessorResult process(
      @NonNull final ProtocolContext context,
      @NonNull final HttpServletRequest request,
      @NonNull final HttpServletResponse response,
      @NonNull final Map<@NonNull String, @NonNull Object> properties) {

    final var repoInfo = ProtocolContextUtils.getRepoInfo(context);

    if (this.shouldSkipAuthentication(repoInfo, properties)) {
      return ProcessorResult.next();
    }

    final var authHeader = this.authComponent.emulateAuthHeader(request);

    if (authHeader == null) {
      return ProcessorResult.of(this.unauthorized("unauthorizedRequest"));
    }

    final var permission = (Permission) properties.get(PERMISSION_KEY);

    try {
      this.authenticateRequest(authHeader, repoInfo.getStorageKey(), permission);
    } catch (final UnAuthorizedException ex) {
      return ProcessorResult.of(
          this.unauthorized(Objects.toString(ex.getMessage(), "unAuthorized")));
    }

    return ProcessorResult.next();
  }

  /**
   * The go command prints the body of a failed answer only when it is {@code text/plain}, and shows
   * nothing but the status for the panel's JSON envelope or an empty body (RPS-1435). The message
   * is the one the credential check chose: the generic {@code unAuthorized}, which does not tell an
   * unknown user from a wrong password, or {@code deployTokenExpired}.
   */
  private ResponseEntity<Object> unauthorized(final String msgId) {

    final var text = this.messageSource.getMessage(msgId, null, msgId, Locale.getDefault());

    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(WWW_AUTHENTICATE, BasicAuthChallenge.REPSY)
        .contentType(MediaType.TEXT_PLAIN)
        .body(text);
  }

  private void authenticateRequest(
      final @NonNull String authHeader,
      final @NonNull UUID repoId,
      final @NonNull Permission permission) {

    switch (authHeader) {
      case final String header when header.startsWith(AUTH_BASIC) ->
          this.authComponent.handleBasicAuth(header, permission, repoId);
      case final String header when header.startsWith(AUTH_BEARER) ->
          this.authComponent.handleBearerAuth(header, repoId, permission);
      default -> throw new UnAuthorizedException("unAuthorized");
    }
  }

  private boolean shouldSkipAuthentication(
      final @NonNull RepoInfo repoInfo,
      final @NonNull Map<@NonNull String, @NonNull Object> properties) {

    final var writeOperation = (boolean) properties.get(WRITE_OPERATION_KEY);
    return !repoInfo.isPrivateRepo() && !writeOperation;
  }
}
