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
package io.repsy.os.server.protocols.cargo.protocol.pre_processors;

import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProcessor;
import io.repsy.os.server.protocols.cargo.shared.auth.services.CargoAuthComponent;
import io.repsy.os.server.shared.utils.ProtocolContextUtils;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@NullMarked
public class CargoAuthPreProcessor extends ProtocolProcessor {

  private static final int PRIORITY = 100;
  private static final String AUTH_BASIC = "Basic ";
  private static final String AUTH_BEARER = "Bearer ";
  private static final String SKIP_PRE_PROCESSOR_KEY = "skipPreProcessor";
  private static final String PERMISSION_KEY = "permission";

  private final CargoAuthComponent authComponent;
  private final CargoProtocolProvider provider;

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
      return ProcessorResult.of(
          ResponseEntity.status(HttpStatus.UNAUTHORIZED)
              .header(WWW_AUTHENTICATE, "Basic realm=\"Repsy Managed Repository\"")
              .build());
    }

    final var permission = (Permission) properties.get(PERMISSION_KEY);

    try {
      this.authenticateRequest(authHeader, repoInfo.getStorageKey(), permission);
    } catch (final AccessNotAllowedException e) {
      return ProcessorResult.of(
          ResponseEntity.status(HttpStatus.UNAUTHORIZED)
              .header(WWW_AUTHENTICATE, "Basic realm=\"Repsy Managed Repository\"")
              .build());
    }

    return ProcessorResult.next();
  }

  private void authenticateRequest(
      final String authHeader, final UUID repoId, final Permission permission) {

    // Cargo CLI sends the token as a raw value with no prefix (e.g. "Authorization: mytoken").
    // Normalise to Bearer so downstream auth methods can handle it uniformly.
    final var normalized = this.getNormalizedName(authHeader);

    switch (normalized) {
      case final String h when h.startsWith(AUTH_BASIC) ->
          this.authComponent.handleBasicAuth(h, permission, repoId);
      case final String h when h.startsWith(AUTH_BEARER) ->
          this.authComponent.handleBearerAuth(h, repoId, permission);
      default -> throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }
  }

  private String getNormalizedName(final String authHeader) {

    return (authHeader.startsWith(AUTH_BASIC) || authHeader.startsWith(AUTH_BEARER))
        ? authHeader
        : AUTH_BEARER + authHeader;
  }

  private boolean shouldSkipAuthentication(
      final RepoInfo repoInfo, final Map<String, Object> properties) {

    final var skip = (boolean) properties.getOrDefault(SKIP_PRE_PROCESSOR_KEY, false);
    if (skip) {
      return true;
    }

    final var permission = (Permission) properties.get(PERMISSION_KEY);
    if (this.isWritePermissionRequired(permission)) {
      return false;
    }

    return !repoInfo.isPrivateRepo();
  }

  private boolean isWritePermissionRequired(final Permission permission) {
    return permission == Permission.MANAGE || permission == Permission.WRITE;
  }
}
