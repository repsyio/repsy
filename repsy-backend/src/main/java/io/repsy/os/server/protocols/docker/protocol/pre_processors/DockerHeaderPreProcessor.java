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

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;

import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProcessor;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@NullMarked
public class DockerHeaderPreProcessor extends ProtocolProcessor {

  private static final int PRIORITY = 50;
  private static final String AUTH_BEARER = "Bearer ";
  private static final String SKIP_HEADER_PRE_PROCESSOR_KEY = "skipHeaderPreProcessor";

  @Value("${os.app.repo-base-url}")
  private String repoBaseUrl;

  private final DockerProtocolProvider provider;

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

    if (this.isPreProcessorNotEnabled(request, properties)) {
      return ProcessorResult.next();
    }

    final var responseRealm =
        "Bearer realm=\"%s/v2/token\",service=\"repsy\",scope=\"repository:*:pull\""
            .formatted(this.repoBaseUrl);

    final var result =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header(WWW_AUTHENTICATE, responseRealm)
            .build();

    return ProcessorResult.of(result);
  }

  private boolean isPreProcessorNotEnabled(
      final HttpServletRequest request, final Map<String, Object> properties) {

    return this.shouldSkipAuthentication(properties) || this.isAuthHeaderBearer(request);
  }

  private boolean shouldSkipAuthentication(final Map<String, Object> properties) {

    final var skipPreProcessor = properties.getOrDefault(SKIP_HEADER_PRE_PROCESSOR_KEY, false);

    return (boolean) skipPreProcessor;
  }

  private boolean isAuthHeaderBearer(final HttpServletRequest request) {

    final var authHeader = request.getHeader(AUTHORIZATION);

    return authHeader != null && authHeader.startsWith(AUTH_BEARER);
  }
}
