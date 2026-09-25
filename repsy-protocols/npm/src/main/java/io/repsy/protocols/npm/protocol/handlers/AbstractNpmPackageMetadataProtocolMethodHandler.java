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
package io.repsy.protocols.npm.protocol.handlers;

import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.facades.NpmProtocolFacade;
import io.repsy.protocols.npm.shared.utils.ExtractPath;
import io.repsy.protocols.npm.shared.utils.PackageUtils;
import io.repsy.protocols.shared.auth.BasicAuthChallenge;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractNpmPackageMetadataProtocolMethodHandler
    implements ProtocolMethodHandler {

  private static final Pattern METADATA_PATTERN = Pattern.compile("^/(.+?)$");
  private static final MediaType ABBREVIATED_METADATA_TYPE =
      MediaType.parseMediaType("application/vnd.npm.install-v1+json");

  private final PathParser basePathParser;
  private final NpmProtocolFacade npmProtocolFacade;

  public AbstractNpmPackageMetadataProtocolMethodHandler(
      @Qualifier("osNpmPathParser") final PathParser basePathParser,
      final NpmProtocolFacade npmProtocolFacade,
      final NpmProtocolProvider provider) {
    this.basePathParser = basePathParser;
    this.npmProtocolFacade = npmProtocolFacade;

    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.GET);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.READ, "writeOperation", false);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.GET.equals(HttpMethod.valueOf(request.getMethod()))) {
        return Optional.empty();
      }

      final var parsedPathOpt =
          AbstractNpmPackageMetadataProtocolMethodHandler.this.basePathParser.parse(request);
      if (parsedPathOpt.isEmpty()) {
        return Optional.empty();
      }

      final var relativePath = ProtocolContextUtils.getRelativePath(parsedPathOpt.get()).getPath();

      // Must match metadata pattern and not be download request or dist-tags
      if (!METADATA_PATTERN.matcher(relativePath).matches()
          || relativePath.contains("/-/")
          || // Not download
          relativePath.contains("dist-tags")) { // Not dist-tags
        return Optional.empty();
      }

      return parsedPathOpt;
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext protocolContext,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    final var relativePath = ProtocolContextUtils.getRelativePath(protocolContext).getPath();
    final var matcher = METADATA_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Parser validation failed");
    }

    final var packagePath = matcher.group(1);
    final var acceptHeader = Objects.requireNonNullElse(request.getHeader(HttpHeaders.ACCEPT), "");

    try {
      final var pathVars = ExtractPath.extractPathVars(packagePath);
      final var metadata =
          this.npmProtocolFacade.getPackageMetadata(
              protocolContext, pathVars.scopeName(), pathVars.packageName(), acceptHeader);

      // The abbreviated and the full document share one address, so what a cache may reuse depends
      // on Accept, and each has its own entity tag. A conditional request that still holds the
      // current document is answered with 304 by Spring, from the ETag and Last-Modified set here
      // (RPS-1359).
      final var abbreviated = PackageUtils.isRequestedAbbreviatedMetadata(acceptHeader);
      final var builder =
          ResponseEntity.ok()
              .contentType(abbreviated ? ABBREVIATED_METADATA_TYPE : MediaType.APPLICATION_JSON)
              .eTag(PackageUtils.computeEtag(metadata))
              .varyBy(HttpHeaders.ACCEPT);
      final var lastModified = PackageUtils.lastModifiedOf(metadata);

      if (lastModified != null) {
        builder.lastModified(lastModified);
      }

      return builder.body(metadata);

    } catch (final UnAuthorizedException e) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .header(WWW_AUTHENTICATE, BasicAuthChallenge.REPSY)
          .build();
    }
  }
}
