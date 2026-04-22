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
package io.repsy.protocols.cargo.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.dtos.CargoErrorResponse;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@NullMarked
public abstract class AbstractCargoConfigProtocolMethodHandler implements ProtocolMethodHandler {

  private final PathParser basePathParser;

  public AbstractCargoConfigProtocolMethodHandler(
      final PathParser pathParser, final CargoProtocolProvider provider) {

    this.basePathParser = pathParser;

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
        "skipHeaderPreProcessor", true,
        "skipUsagePostProcessor", true,
        "skipPreProcessor", true);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.GET.name().equals(request.getMethod())) {
        return Optional.empty();
      }
      if (!request.getServletPath().endsWith("/config.json")) {
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
      final var path = request.getServletPath();
      final var basePath = path.substring(0, path.lastIndexOf("/config.json"));
      final var baseUrl =
          ServletUriComponentsBuilder.fromCurrentContextPath().path(basePath).toUriString();

      final var jsonConfig = this.getJsonConfig(context, baseUrl);

      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
          .body(jsonConfig);

    } catch (final Exception e) {
      return this.buildCargoErrorResponse(e.getMessage());
    }
  }

  private String getJsonConfig(final ProtocolContext context, final String baseUrl) {

    if (this.isAuthRequired(context)) {
      return String.format(
          """
          {
            "dl": "%s/api/v1/crates/{crate}/{version}/download",
            "api": "%s",
            "auth-required": true
          }
          """,
          baseUrl, baseUrl);
    }

    return String.format(
        """
        {
          "dl": "%s/api/v1/crates/{crate}/{version}/download",
          "api": "%s"
        }
        """,
        baseUrl, baseUrl);
  }

  private boolean isAuthRequired(final ProtocolContext context) {
    return ProtocolContextUtils.getRepoInfo(context).isPrivateRepo();
  }

  private ResponseEntity<Object> buildCargoErrorResponse(final String detail) {

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .body(CargoErrorResponse.of(detail));
  }
}
