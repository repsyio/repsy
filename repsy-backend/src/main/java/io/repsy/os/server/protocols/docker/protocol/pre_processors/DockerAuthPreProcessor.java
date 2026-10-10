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
import io.repsy.os.server.protocols.docker.shared.auth.services.DockerAuthenticator;
import io.repsy.os.server.shared.auth.BasicOrBearerAuthPreProcessor;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.shared.auth.AuthChallenges;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.handlers.HandlerPropertyKeys;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Docker: only the Bearer token of the registry token flow ({@code /v2/token}). Basic credentials
 * are refused, every refusal names the token endpoint in its challenge, a push or delete needs
 * credentials on a public repository too, and a delete also needs a token issued for it.
 */
@Component
@NullMarked
public class DockerAuthPreProcessor extends BasicOrBearerAuthPreProcessor<DockerAuthenticator> {

  public DockerAuthPreProcessor(
      final DockerProtocolProvider provider, final DockerAuthenticator authenticator) {
    super(authenticator, provider);
  }

  /** The Docker routes say what they need by their permission, not by {@code writeOperation}. */
  @Override
  protected boolean shouldSkipAuthentication(
      final RepoInfo repoInfo, final Map<String, Object> properties) {

    final var skipPreProcessor =
        (boolean) properties.getOrDefault(HandlerPropertyKeys.SKIP_PRE_PROCESSOR, false);

    if (skipPreProcessor) {
      return true;
    }

    final var permission = (Permission) properties.get(HandlerPropertyKeys.PERMISSION);

    if (permission == Permission.MANAGE || permission == Permission.WRITE) {
      return false;
    }

    return !repoInfo.isPrivateRepo();
  }

  @Override
  protected ProcessorResult missingCredential(final HttpServletRequest request) {
    throw new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
  }

  /** Basic credentials are exchanged for a token at {@code /v2/token}, never taken here. */
  @Override
  protected void authenticateBasic(
      final String credential, final UUID repoId, final Permission permission) {

    throw new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
  }

  @Override
  protected ProcessorResult refused(
      final UnAuthorizedException exception,
      final @Nullable String credential,
      final ProtocolContext context,
      final HttpServletRequest request,
      final Map<String, Object> properties) {

    throw AuthChallenges.challenged(
        exception, DockerAuthChallenge.of(context, request, properties));
  }

  /**
   * A token that passed the role check must also have been issued for what the request does
   * (RPS-1434). The answer is a 401 like any other refusal, and its challenge names the scope to
   * ask for, so a client that requested less can request the right one.
   */
  @Override
  protected void afterAuthentication(
      final ProtocolContext context,
      final HttpServletRequest request,
      final RepoInfo repoInfo,
      final Permission permission,
      final @Nullable String credential) {

    if (permission != Permission.MANAGE || credential == null) {
      return;
    }

    final var name =
        Objects.requireNonNullElse(
            DockerAuthChallenge.requestedName(context, repoInfo), repoInfo.getName());

    try {
      this.authenticator.authorizeGrantedAccess(credential, name, permission);
    } catch (final UnAuthorizedException ex) {
      throw AuthChallenges.challenged(
          ex, DockerAuthChallenge.insufficientScope(request, "repository:" + name + ":delete"));
    }
  }
}
