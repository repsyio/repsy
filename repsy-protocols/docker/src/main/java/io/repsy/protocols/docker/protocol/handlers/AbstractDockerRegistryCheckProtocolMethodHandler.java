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
package io.repsy.protocols.docker.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractDockerRegistryCheckProtocolMethodHandler
    implements ProtocolMethodHandler {

  public AbstractDockerRegistryCheckProtocolMethodHandler(final DockerProtocolProvider provider) {

    provider.registerMethodHandler(this);
  }

  protected abstract Optional<ProtocolContext> createWithEmptyRepo();

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.GET, HttpMethod.HEAD);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of(
        "permission", Permission.NONE, "skipPreProcessor", true, "skipUsagePostProcessor", true);
  }

  @Override
  public PathParser getPathParser() {

    return this::createProtocolContext;
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    return ResponseEntity.ok().build();
  }

  private Optional<ProtocolContext> createProtocolContext(final HttpServletRequest request) {

    final var method = HttpMethod.valueOf(request.getMethod());

    if (!HttpMethod.GET.equals(method) && !HttpMethod.HEAD.equals(method)) {
      return Optional.empty();
    }

    final var path = request.getServletPath();

    if (!"/v2".equals(path) && !"/v2/".equals(path)) {
      return Optional.empty();
    }

    return this.createWithEmptyRepo();
  }
}
