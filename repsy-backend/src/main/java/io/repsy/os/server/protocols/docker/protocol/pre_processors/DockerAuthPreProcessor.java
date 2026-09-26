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
package io.repsy.os.server.protocols.docker.protocol.pre_processors;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProcessor;
import io.repsy.os.server.protocols.docker.shared.auth.services.DockerAuthComponent;
import io.repsy.os.server.shared.auth.AuthChallenges;
import io.repsy.os.server.shared.utils.ProtocolContextUtils;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@NullMarked
public class DockerAuthPreProcessor extends ProtocolProcessor {

  private static final int PRIORITY = 100;
  private static final String AUTH_BEARER = "Bearer ";
  private static final String SKIP_PRE_PROCESSOR_KEY = "skipPreProcessor";
  private static final String PERMISSION_KEY = "permission";

  private final DockerProtocolProvider provider;
  private final DockerAuthComponent authComponent;

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

    final var repoInfo = ProtocolContextUtils.getRepoInfo(context);

    if (this.shouldSkipAuthentication(repoInfo, properties)) {
      return ProcessorResult.next();
    }

    try {
      this.authenticate(request, repoInfo.getStorageKey(), properties);
    } catch (final UnAuthorizedException ex) {
      throw AuthChallenges.challenged(ex, DockerAuthChallenge.of(context, request, properties));
    }

    this.authorizeGrantedAccess(context, request, repoInfo, properties);

    return ProcessorResult.next();
  }

  /**
   * A token that passed the role check must also have been issued for what the request does
   * (RPS-1434). The answer is a 401 like any other refusal, and its challenge names the scope to
   * ask for, so a client that requested less can request the right one.
   */
  private void authorizeGrantedAccess(
      final ProtocolContext context,
      final HttpServletRequest request,
      final RepoInfo repoInfo,
      final Map<String, Object> properties) {

    final var permission = (Permission) properties.get(PERMISSION_KEY);
    final var authHeader = this.authComponent.emulateAuthHeader(request);

    if (permission != Permission.MANAGE || authHeader == null) {
      return;
    }

    final var name =
        Objects.requireNonNullElse(
            DockerAuthChallenge.requestedName(context, repoInfo), repoInfo.getName());

    try {
      this.authComponent.authorizeGrantedAccess(authHeader, name, permission);
    } catch (final UnAuthorizedException ex) {
      throw AuthChallenges.challenged(
          ex, DockerAuthChallenge.insufficientScope(request, "repository:" + name + ":delete"));
    }
  }

  private void authenticate(
      final HttpServletRequest request, final UUID repoId, final Map<String, Object> properties) {

    final var authHeader = this.authComponent.emulateAuthHeader(request);

    if (authHeader == null) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    this.authenticateRequest(authHeader, repoId, properties);
  }

  private void authenticateRequest(
      final String authHeader, final UUID repoId, final Map<String, Object> properties) {

    if (!authHeader.startsWith(AUTH_BEARER)) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    final var permission = (Permission) properties.get(PERMISSION_KEY);

    this.authComponent.handleBearerAuth(authHeader, repoId, permission);
  }

  private boolean shouldSkipAuthentication(
      final RepoInfo repoInfo, final Map<String, Object> properties) {

    final var skipPreProcessor = (boolean) properties.getOrDefault(SKIP_PRE_PROCESSOR_KEY, false);

    if (skipPreProcessor) {
      return true;
    }

    final var permission = (Permission) properties.get(PERMISSION_KEY);

    if (permission == Permission.MANAGE || permission == Permission.WRITE) {
      return false;
    }

    return !repoInfo.isPrivateRepo();
  }
}
