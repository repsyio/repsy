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
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.shared.constants.CargoConstants;
import io.repsy.protocols.shared.handlers.AbstractRoutedProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.http.PublicUrls;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Handles {@code GET /{repo}/config.json}, which is served without authentication even on private
 * repos. Cargo fetches {@code config.json} before it knows whether the registry requires
 * authentication (RFC 3139, {@code auth-required: true}), so the route is deliberately kept open to
 * all clients. The exposed information is limited to the {@code dl} and {@code api} base URLs and
 * the {@code auth-required} flag, which confirm that the registry exists; they do not reveal
 * contents or grant any write access. RPS-2109: documented and pinned (keep open on purpose).
 */
public abstract class AbstractCargoConfigProtocolMethodHandler
    extends AbstractRoutedProtocolMethodHandler {

  public AbstractCargoConfigProtocolMethodHandler(
      final PathParser pathParser, final CargoProtocolProvider provider) {

    super(
        HandlerRoute.of(Permission.READ, HttpMethod.GET)
            .skipHeaderPreProcessor(true)
            .skipUsagePostProcessor(true)
            .skipPreProcessor(true)
            .head(
                HandlerRoute.of(Permission.READ, HttpMethod.HEAD)
                    .skipUsagePostProcessor(true)
                    .skipPreProcessor(true)),
        pathParser,
        provider);
  }

  @Override
  protected boolean accepts(final HttpMethod method, final HttpServletRequest request) {
    return super.accepts(method, request) && request.getServletPath().endsWith("/config.json");
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    // A failure is not caught here: ProtocolErrorAdvice picks the status and, because of this
    // mark, CargoErrorBodyAdvice writes Cargo's error body (RPS-2060).
    request.setAttribute(CargoConstants.ERROR_BODY_ATTRIBUTE, true);

    final var path = request.getServletPath();
    final var basePath = path.substring(0, path.lastIndexOf("/config.json"));
    final var baseUrl = this.resolveBaseUrl(request, basePath);

    final var jsonConfig = this.getJsonConfig(context, baseUrl);

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .body(jsonConfig);
  }

  /**
   * The base URL of {@code config.json}'s {@code dl}/{@code api} fields, built from the current
   * request, with the public port a reverse proxy named (RPS-1515, see {@link PublicUrls}).
   */
  private String resolveBaseUrl(final HttpServletRequest request, final String basePath) {

    return PublicUrls.contextRoot(request) + basePath;
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

  /** The headers of the config, without the body (and without a repo-dependent lookup). */
  @Override
  public ResponseEntity<Object> handleHead(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();
  }
}
