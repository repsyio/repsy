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
package io.repsy.os.server.protocols.helm.protocol.pre_processors;

import static io.repsy.os.shared.auth.utils.AuthUtils.AUTH_BASIC;
import static io.repsy.os.shared.auth.utils.AuthUtils.AUTH_BEARER;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProcessor;
import io.repsy.os.server.protocols.helm.shared.auth.HelmAuthComponent;
import io.repsy.os.server.shared.utils.ProtocolContextUtils;
import io.repsy.os.shared.error_handling.utils.OciErrors;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@NullMarked
public class HelmAuthPreProcessor extends ProtocolProcessor {

  private static final int PRIORITY = 100;
  private static final String SKIP_PRE_PROCESSOR_KEY = "skipPreProcessor";
  private static final String PERMISSION_KEY = "permission";
  private static final String WRITE_OPERATION_KEY = "writeOperation";
  private static final String WWW_AUTHENTICATE_VALUE = "Basic realm=\"Repsy\"";

  private final HelmProtocolProvider provider;
  private final RestResponseFactory resp;
  private final HelmAuthComponent authComponent;

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

    final var authHeader = this.authComponent.emulateAuthHeader(request);

    if (authHeader == null) {
      return ProcessorResult.of(OciErrors.challenge(request, WWW_AUTHENTICATE_VALUE, this.resp));
    }

    this.authenticateRequest(authHeader, repoInfo.getId(), properties);

    return ProcessorResult.next();
  }

  private void authenticateRequest(
      final String authHeader, final UUID repoId, final Map<String, Object> properties) {

    final var permission = (Permission) properties.get(PERMISSION_KEY);

    switch (authHeader) {
      case final String h when h.startsWith(AUTH_BASIC) ->
          this.authComponent.handleBasicAuth(h, permission, repoId);
      case final String h when h.startsWith(AUTH_BEARER) ->
          this.authComponent.handleBearerAuth(h, repoId, permission);
      default -> throw new UnAuthorizedException("unAuthorized");
    }
  }

  private boolean shouldSkipAuthentication(
      final RepoInfo repoInfo, final Map<String, Object> properties) {

    final var skipPreProcessor = (boolean) properties.getOrDefault(SKIP_PRE_PROCESSOR_KEY, false);

    if (skipPreProcessor) {
      return true;
    }

    final var writeOperation = (boolean) properties.getOrDefault(WRITE_OPERATION_KEY, false);

    return !repoInfo.isPrivateRepo() && !writeOperation;
  }
}
