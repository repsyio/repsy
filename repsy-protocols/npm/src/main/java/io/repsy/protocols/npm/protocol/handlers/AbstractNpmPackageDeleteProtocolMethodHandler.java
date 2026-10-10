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

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.facades.NpmProtocolFacade;
import io.repsy.protocols.npm.shared.utils.NpmRevPath;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The {@code DELETE} steps of an {@code npm unpublish} (a version's tarball, or the whole package).
 * They remove stored files, so they need {@link Permission#MANAGE}, like the panel's delete and the
 * packument PUT of the same command (RPS-1424).
 */
@NullMarked
public abstract class AbstractNpmPackageDeleteProtocolMethodHandler
    extends AbstractFacadeProtocolMethodHandler<NpmProtocolFacade> {

  public AbstractNpmPackageDeleteProtocolMethodHandler(
      @Qualifier("npmPathParser") final PathParser basePathParser,
      final NpmProtocolFacade npmProtocolFacade,
      final NpmProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.MANAGE, HttpMethod.DELETE)
            .writeOperation(true)
            .path(path -> NpmRevPath.parse(path).isPresent() && !path.contains("dist-tags")),
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
    final var revPathOpt = NpmRevPath.parse(relativePath);

    if (revPathOpt.isEmpty()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var revPath = revPathOpt.get();

    if (revPath.tarballFilename() == null) {
      // Unpublish of the only version, or a delete of the whole package
      this.facade.deletePackage(protocolContext, revPath.scopeName(), revPath.packageName());
    } else {
      // The last request of an unpublish of one version, after its packument PUT
      this.facade.deletePackageTarball(
          protocolContext, revPath.scopeName(), revPath.packageName(), revPath.tarballFilename());
    }

    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(NpmWriteResponse.of(revPath.scopeName(), revPath.packageName()));
  }
}
