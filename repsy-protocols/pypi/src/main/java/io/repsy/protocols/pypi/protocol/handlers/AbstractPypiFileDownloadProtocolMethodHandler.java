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
package io.repsy.protocols.pypi.protocol.handlers;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.pypi.protocol.PypiProtocolProvider;
import io.repsy.protocols.pypi.protocol.facades.PypiProtocolFacade;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.http.ResourceResponses;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public abstract class AbstractPypiFileDownloadProtocolMethodHandler<ID>
    extends AbstractFacadeProtocolMethodHandler<PypiProtocolFacade<ID>> {

  private static final Pattern DOWNLOAD_PATTERN = Pattern.compile("^/([^/]+)/-/([^/]+)$");

  public AbstractPypiFileDownloadProtocolMethodHandler(
      final PypiProtocolFacade<ID> pypiProtocolFacade,
      final PathParser basePathParser,
      final PypiProtocolProvider provider) {
    super(
        HandlerRoute.read(HttpMethod.GET).path(DOWNLOAD_PATTERN.asMatchPredicate()).head(),
        basePathParser,
        pypiProtocolFacade,
        provider);
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    final var relativePath = ProtocolContextUtils.getRelativePath(context);
    final var matcher = DOWNLOAD_PATTERN.matcher(relativePath.getPath());

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var packageName = matcher.group(1);
    final var fileName = matcher.group(2);

    final var resource = this.facade.downloadArchiveFile(context, packageName, fileName);

    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(HttpHeaders.CONTENT_DISPOSITION, ResourceResponses.attachment(fileName))
        .body(resource);
  }

  /** The status and headers of the {@code GET} with the file's length; the file is not streamed. */
  @Override
  public ResponseEntity<Object> handleHead(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws IOException {

    final var matcher =
        DOWNLOAD_PATTERN.matcher(ProtocolContextUtils.getRelativePath(context).getPath());

    if (!matcher.matches()) {
      return ResponseEntity.notFound().build();
    }

    final var fileName = matcher.group(2);
    final Resource resource;

    try {
      resource = this.facade.downloadArchiveFile(context, matcher.group(1), fileName);
    } catch (final ItemNotFoundException _) {
      return ResponseEntity.notFound().build();
    }

    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(HttpHeaders.CONTENT_DISPOSITION, ResourceResponses.attachment(fileName))
        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
        .contentLength(resource.contentLength())
        .build();
  }
}
