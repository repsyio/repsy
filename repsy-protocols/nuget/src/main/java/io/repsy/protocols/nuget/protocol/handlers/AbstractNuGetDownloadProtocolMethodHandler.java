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
package io.repsy.protocols.nuget.protocol.handlers;

import static org.springframework.http.HttpHeaders.CONTENT_DISPOSITION;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
import static org.springframework.http.MediaType.APPLICATION_XML;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.nuget.protocol.NuGetProtocolProvider;
import io.repsy.protocols.nuget.protocol.facades.contracts.NuGetProtocolFacade;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.http.ResourceResponses;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@Slf4j
public abstract class AbstractNuGetDownloadProtocolMethodHandler
    extends AbstractFacadeProtocolMethodHandler<NuGetProtocolFacade> {

  // Each segment is [^/]+ so the three of them cannot trade characters, which keeps the match
  // linear on a long path a client controls.
  static final Pattern NUPKG_PATTERN = Pattern.compile(".*/v3/package/[^/]+/[^/]+/[^/]+\\.nupkg$");
  static final Pattern NUSPEC_PATTERN =
      Pattern.compile(".*/v3/package/[^/]+/[^/]+/[^/]+\\.nuspec$");

  private final boolean nupkg;

  protected AbstractNuGetDownloadProtocolMethodHandler(
      final PathParser basePathParser,
      final NuGetProtocolFacade facade,
      final NuGetProtocolProvider provider,
      final boolean nupkg) {
    super(HandlerRoute.read(HttpMethod.GET).head(), basePathParser, facade, provider);
    this.nupkg = nupkg;
  }

  /**
   * The file name is the last segment of the URL ({@code <id>.<version>.nupkg}, {@code
   * <id>.nuspec}). Without a header of its own Spring names the download "f.txt" (RPS-1389). The
   * package is an attachment, the nuspec a document a browser may show under its own name.
   */
  static String contentDisposition(final ProtocolContext context, final boolean attach) {
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();
    final var filename = path.substring(path.lastIndexOf('/') + 1);

    return attach ? ResourceResponses.attachment(filename) : ResourceResponses.inline(filename);
  }

  @Override
  protected boolean accepts(final HttpMethod method, final HttpServletRequest request) {
    return super.accepts(method, request)
        && (this.nupkg ? NUPKG_PATTERN : NUSPEC_PATTERN)
            .matcher(request.getServletPath())
            .matches();
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    try {
      if (this.nupkg) {
        final var resource = this.facade.downloadNuPackage(context);
        return ResponseEntity.ok()
            .header(CONTENT_TYPE, APPLICATION_OCTET_STREAM.toString())
            .header(CONTENT_DISPOSITION, contentDisposition(context, true))
            .body(resource);
      }

      final var resource = this.facade.downloadNuspec(context);
      return ResponseEntity.ok()
          .header(CONTENT_TYPE, APPLICATION_XML.toString())
          .header(CONTENT_DISPOSITION, contentDisposition(context, false))
          .body(resource);
    } catch (final ItemNotFoundException e) {
      log.debug("NuGet download not found: {}", e.getMessage());
      return ResponseEntity.notFound().build();
    }
  }

  /** The status and headers of the {@code GET} with the file's length; no body. */
  @Override
  public ResponseEntity<Object> handleHead(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws IOException {

    try {
      if (this.nupkg) {
        final var resource = this.facade.getNuPackage(context);

        return ResponseEntity.ok()
            .header(CONTENT_TYPE, APPLICATION_OCTET_STREAM.toString())
            .header(CONTENT_DISPOSITION, contentDisposition(context, true))
            .contentLength(resource.contentLength())
            .build();
      }

      final var resource = this.facade.downloadNuspec(context);

      return ResponseEntity.ok()
          .header(CONTENT_TYPE, APPLICATION_XML.toString())
          .header(CONTENT_DISPOSITION, contentDisposition(context, false))
          .contentLength(resource.contentLength())
          .build();
    } catch (final ItemNotFoundException | IllegalArgumentException e) {
      log.debug("NuGet HEAD not found: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
  }
}
