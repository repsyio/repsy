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
import io.repsy.protocols.npm.shared.utils.NpmRevPath;
import io.repsy.protocols.shared.auth.BasicAuthChallenge;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The packument PUT of an {@code npm unpublish} of one version ({@code PUT /<package>/-rev/<rev>},
 * RPS-1289). It removes a version, so it needs {@link Permission#MANAGE} like the panel's delete
 * and like the {@code DELETE .../-rev/<rev>} steps of the same command (RPS-1424). It is split from
 * {@link AbstractNpmPackagePublishOrDeprecateProtocolMethodHandler} because the permission is a
 * property of the handler, which the auth pre-processor reads before {@code handle}.
 */
public abstract class AbstractNpmPackageUnpublishProtocolMethodHandler
    extends AbstractFacadeProtocolMethodHandler<NpmProtocolFacade> {

  private final ObjectMapper objectMapper;

  public AbstractNpmPackageUnpublishProtocolMethodHandler(
      @Qualifier("osNpmPathParser") final PathParser basePathParser,
      final NpmProtocolFacade npmProtocolFacade,
      final ObjectMapper objectMapper,
      final NpmProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.MANAGE, HttpMethod.PUT)
            .writeOperation(true)
            .path(
                path ->
                    NpmRevPath.parse(path).filter(p -> p.tarballFilename() == null).isPresent()),
        basePathParser,
        npmProtocolFacade,
        provider);
    this.objectMapper = objectMapper;
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext protocolContext,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    final var relativePath = ProtocolContextUtils.getRelativePath(protocolContext).getPath();
    final var revPathOpt = NpmRevPath.parse(relativePath);

    if (revPathOpt.isEmpty()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Parser validation failed");
    }

    // The package is what precedes /-rev/<rev>, not the whole path
    final var revPath = revPathOpt.get();

    try {
      final var payload =
          this.objectMapper.readValue(
              request.getInputStream(), new TypeReference<Map<String, Object>>() {});

      this.facade.unPublishPackageVersion(
          protocolContext, revPath.scopeName(), revPath.packageName(), payload);

      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_JSON)
          .body(NpmWriteResponse.of(revPath.scopeName(), revPath.packageName()));
    } catch (final UnAuthorizedException _) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .header(WWW_AUTHENTICATE, BasicAuthChallenge.REPSY)
          .build();
    }
  }
}
