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
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.facades.contracts.CargoProtocolFacade;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.http.ResourceResponses;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Slf4j
@NullMarked
public abstract class AbstractCargoDownloadProtocolMethodHandler
    extends AbstractFacadeProtocolMethodHandler<CargoProtocolFacade> {

  static final Pattern DOWNLOAD_PATTERN =
      Pattern.compile(".*/api/v1/crates/([^/]+)/([^/]+)/download$");

  public AbstractCargoDownloadProtocolMethodHandler(
      final PathParser basePathParser,
      final CargoProtocolFacade facade,
      final CargoProtocolProvider provider) {

    super(
        HandlerRoute.read(HttpMethod.GET).path(DOWNLOAD_PATTERN.asMatchPredicate()).head(),
        basePathParser,
        facade,
        provider);
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    try {
      final var resource = this.facade.download(context);

      // The URL ends in "/download", so without a header of its own a browser or a download tool
      // saves the crate as "download" (RPS-1389). cargo itself ignores the header.
      final var ok = ResponseEntity.ok();
      final var contentDisposition =
          contentDisposition(ProtocolContextUtils.getRelativePath(context).getPath());

      if (contentDisposition != null) {
        ok.header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition);
      }

      return ok.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
          .body(resource);
    } catch (final Exception e) {
      log.debug("Cargo download failed: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
  }

  /**
   * The file name of the crate a download URL names, as an attachment; the {@code HEAD} of the same
   * URL answers it too. {@code null} when the path is not a download path.
   */
  static @Nullable String contentDisposition(final String relativePath) {
    final var matcher = DOWNLOAD_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return null;
    }

    return ResourceResponses.attachment(matcher.group(1) + "-" + matcher.group(2) + ".crate");
  }

  /** The status and headers of the {@code GET}; the crate is only measured, never streamed. */
  @Override
  public ResponseEntity<Object> handleHead(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    try {
      final var resource = this.facade.getCrate(context);
      final var ok =
          ResponseEntity.ok()
              .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
              .contentLength(resource.contentLength());
      final var contentDisposition =
          contentDisposition(ProtocolContextUtils.getRelativePath(context).getPath());

      if (contentDisposition != null) {
        ok.header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition);
      }

      return ok.build();
    } catch (final ItemNotFoundException | IOException e) {
      log.debug("Cargo HEAD of a crate failed: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
  }
}
