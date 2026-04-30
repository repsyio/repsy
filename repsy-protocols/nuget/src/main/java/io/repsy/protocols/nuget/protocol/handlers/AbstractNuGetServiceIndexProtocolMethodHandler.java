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
package io.repsy.protocols.nuget.protocol.handlers;

import static io.repsy.protocols.nuget.shared.utils.NuGetUrlBuilder.buildBaseUrl;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.nuget.protocol.NuGetProtocolProvider;
import io.repsy.protocols.nuget.protocol.facades.contract.NuGetProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Slf4j
@NullMarked
public abstract class AbstractNuGetServiceIndexProtocolMethodHandler
    implements ProtocolMethodHandler {

  private final PathParser basePathParser;
  private final NuGetProtocolFacade facade;

  public AbstractNuGetServiceIndexProtocolMethodHandler(
      final PathParser basePathParser,
      final NuGetProtocolFacade facade,
      final NuGetProtocolProvider provider) {

    this.basePathParser = basePathParser;
    this.facade = facade;

    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.GET);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of(
        "permission", Permission.READ,
        "writeOperation", false,
        "skipPreProcessor", true,
        "skipHeaderPreProcessor", true);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      final var uri = request.getRequestURI();

      if (!HttpMethod.GET.name().equals(request.getMethod())) {
        return Optional.empty();
      }

      if (!uri.toLowerCase().endsWith("/v3/index.json")) {
        return Optional.empty();
      }

      return this.basePathParser.parse(request);
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    try {
      final var repoName = ProtocolContextUtils.<Object>getRepoInfo(context).getName();
      final var baseUrl = buildBaseUrl(request, repoName);
      final var serviceIndex = this.facade.getServiceIndex(context, baseUrl);

      return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(serviceIndex);
    } catch (final Exception e) {
      log.debug("NuGet service index failed: {}", e.getMessage());
      return ResponseEntity.internalServerError().build();
    }
  }
}
