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

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;

import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProcessor;
import io.repsy.os.server.shared.utils.ProtocolContextUtils;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Returns 401 + WWW-Authenticate: Basic when no credentials are present on private-repo requests,
 * so that OCI clients (helm push/pull) know to retry with Basic auth.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@NullMarked
public class HelmHeaderPreProcessor extends ProtocolProcessor {

  private static final int PRIORITY = 50;
  private static final String SKIP_PRE_PROCESSOR_KEY = "skipPreProcessor";
  private static final String SKIP_HEADER_PRE_PROCESSOR_KEY = "skipHeaderPreProcessor";
  private static final String PERMISSION_KEY = "permission";
  private static final String WWW_AUTHENTICATE_VALUE = "Basic realm=\"Repsy\"";

  private final HelmProtocolProvider provider;

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

    if (this.shouldSkipAuthentication(request, context, properties)) {
      log.warn(
          "[HelmHeader] skipped method={} path={}", request.getMethod(), request.getRequestURI());
      return ProcessorResult.next();
    }

    log.warn(
        "[HelmHeader] returning 401 method={} path={}",
        request.getMethod(),
        request.getRequestURI());
    return ProcessorResult.of(
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header(WWW_AUTHENTICATE, WWW_AUTHENTICATE_VALUE)
            .build());
  }

  private boolean shouldSkipAuthentication(
      final HttpServletRequest request,
      final ProtocolContext context,
      final Map<String, Object> properties) {

    if (this.isSkipFlagSet(properties)) {
      return true;
    }

    if (request.getHeader(AUTHORIZATION) != null) {
      return true;
    }

    final var permission = (Permission) properties.get(PERMISSION_KEY);
    final var repoInfo = ProtocolContextUtils.getRepoInfo(context);

    return !repoInfo.isPrivateRepo() && !this.isWritePermission(permission);
  }

  private boolean isSkipFlagSet(final Map<String, Object> properties) {
    return (boolean) properties.getOrDefault(SKIP_PRE_PROCESSOR_KEY, false)
        || (boolean) properties.getOrDefault(SKIP_HEADER_PRE_PROCESSOR_KEY, false);
  }

  private boolean isWritePermission(final Permission permission) {
    return permission == Permission.MANAGE || permission == Permission.WRITE;
  }
}
