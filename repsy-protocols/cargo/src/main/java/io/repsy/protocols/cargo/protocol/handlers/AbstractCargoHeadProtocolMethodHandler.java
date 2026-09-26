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

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.facades.contract.CargoProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Answers a {@code HEAD} on a crate download or a sparse index file like the {@code GET} of the
 * same path would, without the body (RPS-1465): 200 with the headers of the {@code GET} (and the
 * {@code Content-Length} of a {@code .crate}) or its 404. Before, both were answered by the
 * router's generic {@code unknownPath} 404 (see {@link
 * AbstractCargoConfigHeadProtocolMethodHandler} for {@code config.json}).
 *
 * <p>The authorization is the one of the {@code GET}: read permission, decided by the same
 * pre-processor. A {@code HEAD} is not a download, so it does not count towards the crate's
 * downloads. The {@code Content-Length} of an index file is not sent: the body is built per request
 * and a {@code HEAD} does not build it.
 */
@Slf4j
@NullMarked
public abstract class AbstractCargoHeadProtocolMethodHandler implements ProtocolMethodHandler {

  private final PathParser basePathParser;
  private final CargoProtocolFacade facade;

  protected AbstractCargoHeadProtocolMethodHandler(
      final PathParser basePathParser,
      final CargoProtocolFacade facade,
      final CargoProtocolProvider provider) {

    this.basePathParser = basePathParser;
    this.facade = facade;

    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.HEAD);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of(
        "permission", Permission.READ, "writeOperation", false, "skipUsagePostProcessor", true);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      final var parsedPathOpt = this.basePathParser.parse(request);
      if (parsedPathOpt.isEmpty()) {
        return Optional.empty();
      }

      final var relativePath = ProtocolContextUtils.getRelativePath(parsedPathOpt.get()).getPath();

      final var isDownload =
          AbstractCargoDownloadProtocolMethodHandler.DOWNLOAD_PATTERN
              .matcher(relativePath)
              .matches();
      final var isIndex =
          !AbstractCargoSparseIndexProtocolMethodHandler.EXCLUDED_PATTERN
                  .matcher(relativePath)
                  .matches()
              && AbstractCargoSparseIndexProtocolMethodHandler.INDEX_PATTERN
                  .matcher(relativePath)
                  .matches();

      return isDownload || isIndex ? parsedPathOpt : Optional.empty();
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();

    if (AbstractCargoDownloadProtocolMethodHandler.DOWNLOAD_PATTERN
        .matcher(relativePath)
        .matches()) {
      return this.handleDownload(context, relativePath);
    }

    return this.handleIndex(context, request);
  }

  private ResponseEntity<Object> handleDownload(
      final ProtocolContext context, final String relativePath) {

    try {
      final var resource = this.facade.getCrate(context);
      final var ok =
          ResponseEntity.ok()
              .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
              .contentLength(resource.contentLength());
      final var contentDisposition =
          AbstractCargoDownloadProtocolMethodHandler.contentDisposition(relativePath);

      if (contentDisposition != null) {
        ok.header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition);
      }

      return ok.build();
    } catch (final ItemNotFoundException | IOException e) {
      log.debug("Cargo HEAD of a crate failed: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
  }

  private ResponseEntity<Object> handleIndex(
      final ProtocolContext context, final HttpServletRequest request) {

    try {
      if (this.facade.getIndexEntries(context).isEmpty()) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
      }

      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
          .build();
    } catch (final Exception e) {
      log.debug(
          "Cargo sparse index HEAD failed for {}: {}", request.getServletPath(), e.getMessage());
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
  }
}
