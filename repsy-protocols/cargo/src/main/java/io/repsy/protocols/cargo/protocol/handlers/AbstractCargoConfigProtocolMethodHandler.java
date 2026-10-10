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
import io.repsy.protocols.cargo.protocol.dtos.CargoErrorResponse;
import io.repsy.protocols.shared.handlers.AbstractRoutedProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ForwardedHostUtils;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Handles {@code GET /{repo}/config.json}, which is served without authentication even on private
 * repos. Cargo fetches {@code config.json} before it knows whether the registry requires
 * authentication (RFC 3139, {@code auth-required: true}), so the route is deliberately kept open to
 * all clients. The exposed information is limited to the {@code dl} and {@code api} base URLs and
 * the {@code auth-required} flag, which confirm that the registry exists; they do not reveal
 * contents or grant any write access. RPS-2109: documented and pinned (keep open on purpose).
 */
@NullMarked
public abstract class AbstractCargoConfigProtocolMethodHandler
    extends AbstractRoutedProtocolMethodHandler {

  private static final int PORT_HTTP = 80;
  private static final int PORT_HTTPS = 443;

  public AbstractCargoConfigProtocolMethodHandler(
      final PathParser pathParser, final CargoProtocolProvider provider) {

    super(
        HandlerRoute.of(Permission.READ, HttpMethod.GET)
            .skipHeaderPreProcessor(true)
            .skipUsagePostProcessor(true)
            .skipPreProcessor(true),
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

    try {
      final var path = request.getServletPath();
      final var basePath = path.substring(0, path.lastIndexOf("/config.json"));
      final var baseUrl = this.resolveBaseUrl(request, basePath);

      final var jsonConfig = this.getJsonConfig(context, baseUrl);

      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
          .body(jsonConfig);

    } catch (final Exception e) {
      return this.buildCargoErrorResponse(e.getMessage());
    }
  }

  /**
   * The base URL of {@code config.json}'s {@code dl}/{@code api} fields, built from the current
   * request. RPS-1515: {@code ServletUriComponentsBuilder} reads the port off {@code
   * HttpServletRequest#getServerPort()}, which Tomcat's {@code RemoteIpValve} (wired up by {@code
   * server.forward-headers-strategy: native}) leaves at the scheme's default when a reverse proxy
   * sent {@code X-Forwarded-Host} with an embedded port but no separate {@code X-Forwarded-Port}.
   * Recover that port from the raw header before it is dropped.
   */
  private String resolveBaseUrl(final HttpServletRequest request, final String basePath) {

    final var builder = ServletUriComponentsBuilder.fromCurrentContextPath();
    final var scheme = request.getScheme();
    final var port =
        ForwardedHostUtils.resolvePort(
            request.getHeader(ForwardedHostUtils.X_FORWARDED_HOST),
            request.getHeader(ForwardedHostUtils.X_FORWARDED_PORT),
            request.getServerPort());

    if (("http".equals(scheme) && port != PORT_HTTP)
        || ("https".equals(scheme) && port != PORT_HTTPS)) {
      builder.port(port);
    } else {
      builder.port(-1);
    }

    return builder.path(basePath).toUriString();
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
