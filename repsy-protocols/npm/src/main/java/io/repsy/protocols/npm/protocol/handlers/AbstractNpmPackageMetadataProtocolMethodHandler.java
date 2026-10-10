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
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.facades.NpmProtocolFacade;
import io.repsy.protocols.npm.shared.utils.ExtractPath;
import io.repsy.protocols.npm.shared.utils.NpmPackageUtils;
import io.repsy.protocols.shared.auth.BasicAuthChallenge;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
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
    extends AbstractFacadeProtocolMethodHandler<NpmProtocolFacade> {

  private static final Pattern METADATA_PATTERN = Pattern.compile("^/(.+?)$");
  private static final MediaType ABBREVIATED_METADATA_TYPE =
      MediaType.parseMediaType("application/vnd.npm.install-v1+json");

  public AbstractNpmPackageMetadataProtocolMethodHandler(
      @Qualifier("osNpmPathParser") final PathParser basePathParser,
      final NpmProtocolFacade npmProtocolFacade,
      final NpmProtocolProvider provider) {
    super(
        HandlerRoute.read(HttpMethod.GET)
            .path(
                path ->
                    METADATA_PATTERN.matcher(path).matches()
                        && !path.contains("/-/")
                        && !path.contains("dist-tags")),
        basePathParser,
        npmProtocolFacade,
        provider);
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
          this.facade.getPackageMetadata(
              protocolContext, pathVars.scopeName(), pathVars.packageName(), acceptHeader);

      // The abbreviated and the full document share one address, so what a cache may reuse depends
      // on Accept, and each has its own entity tag. A conditional request that still holds the
      // current document is answered with 304 by Spring, from the ETag and Last-Modified set here
      // (RPS-1359).
      final var abbreviated = NpmPackageUtils.isRequestedAbbreviatedMetadata(acceptHeader);
      final var builder =
          ResponseEntity.ok()
              .contentType(abbreviated ? ABBREVIATED_METADATA_TYPE : MediaType.APPLICATION_JSON)
              .eTag(NpmPackageUtils.computeEtag(metadata))
              .varyBy(HttpHeaders.ACCEPT);
      final var lastModified = NpmPackageUtils.lastModifiedOf(metadata);

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
