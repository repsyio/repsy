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
package io.repsy.os.server.protocols.ruby.protocol.pre_processors;

import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProcessor;
import io.repsy.os.server.protocols.cargo.protocol.pre_processors.CargoAuthPreProcessor;
import io.repsy.os.server.protocols.ruby.shared.auth.services.RubyAuthComponent;
import io.repsy.os.server.shared.utils.ProtocolContextUtils;
import io.repsy.protocols.ruby.protocol.RubyProtocolProvider;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@NullMarked
public class RubyAuthPreProcessor extends ProtocolProcessor {

  private static final int PRIORITY = 100;
  private static final String AUTH_BASIC = "Basic ";
  private static final String AUTH_BEARER = "Bearer ";
  private static final String SKIP_PRE_PROCESSOR_KEY = "skipPreProcessor";
  private static final String PERMISSION_KEY = "permission";
  private static final String WRITE_OPERATION_KEY = "writeOperation";

  private final RubyAuthComponent authComponent;
  private final RubyProtocolProvider provider;

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

    if (CargoAuthPreProcessor.shouldSkipAuthentication(
        SKIP_PRE_PROCESSOR_KEY, WRITE_OPERATION_KEY, repoInfo, properties)) {
      return ProcessorResult.next();
    }

    final var rawAuthHeader = this.authComponent.emulateAuthHeader(request);

    if (rawAuthHeader == null) {
      return ProcessorResult.of(
          org.springframework.http.ResponseEntity.status(HttpStatus.UNAUTHORIZED)
              .header(WWW_AUTHENTICATE, "Basic realm=\"Repsy Managed Repository\"")
              .build());
    }

    final var authHeader = normalizeAuthHeader(rawAuthHeader);
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

  private static String normalizeAuthHeader(final String authHeader) {
    return (authHeader.startsWith(AUTH_BASIC) || authHeader.startsWith(AUTH_BEARER))
        ? authHeader
        : AUTH_BEARER + authHeader;
  }
}
