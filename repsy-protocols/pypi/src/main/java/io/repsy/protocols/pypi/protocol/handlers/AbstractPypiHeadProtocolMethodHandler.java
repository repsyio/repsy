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
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Answers {@code HEAD} on a PyPI path with the same status the matching {@code GET} route would
 * give: {@code 200} for a resolvable resource, {@code 404} otherwise (RPS-1226). The project pages
 * are an existence-only lookup on {@link PypiProtocolFacade}, never {@code getPackageList} (builds
 * the whole body).
 *
 * <p>The answer for a wheel or an sdist carries the headers of its {@code GET}: {@code
 * Content-Length}, {@code Content-Type}, {@code Content-Disposition} and {@code Accept-Ranges:
 * bytes}, which the {@code GET} gets from Spring because it serves a resource and a {@code HEAD}
 * without a body does not (RPS-1562). uv asks with a {@code HEAD} whether the index serves ranges
 * before it reads the metadata of a wheel by range, and streams the whole wheel when it is not told
 * so. The file is resolved through {@code downloadArchiveFile}, which is lazy (nothing is read from
 * storage) and counts no download.
 *
 * <p>The path patterns below intentionally mirror, rather than share, the private patterns in
 * {@link AbstractPypiSimpleProtocolMethodHandler} and {@link
 * AbstractPypiFileDownloadProtocolMethodHandler}: those GET handlers keep their own copies, so this
 * class keeps its own rather than reaching into them.
 *
 * <p>A non-normalized project name (e.g. {@code /simple/My_Pkg/}) mirrors the GET route's {@code
 * 307} redirect to the normalized URI, rather than answering the existence check directly against
 * the raw name: {@code HEAD} should mean the same thing a client's follow-up {@code GET} would.
 */
@NullMarked
public abstract class AbstractPypiHeadProtocolMethodHandler<ID>
    extends AbstractFacadeProtocolMethodHandler<PypiProtocolFacade<ID>> {

  private static final Pattern SIMPLE_PATTERN = Pattern.compile("^/simple(?:/([^/]+))?/?$");
  private static final Pattern DOWNLOAD_PATTERN = Pattern.compile("^/([^/]+)/-/([^/]+)$");

  protected AbstractPypiHeadProtocolMethodHandler(
      final PathParser pathParser,
      final PypiProtocolFacade<ID> facade,
      final PypiProtocolProvider provider) {
    super(
        HandlerRoute.read(HttpMethod.HEAD).skipUsagePostProcessor(true),
        pathParser,
        facade,
        provider);
  }

  protected abstract @Nullable URI getNormalizedUri(
      HttpServletRequest request, @Nullable String packageName);

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws IOException {

    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();

    final var simpleMatcher = SIMPLE_PATTERN.matcher(relativePath);
    if (simpleMatcher.matches()) {
      return this.handleSimple(context, request, simpleMatcher.group(1));
    }

    final var downloadMatcher = DOWNLOAD_PATTERN.matcher(relativePath);
    if (downloadMatcher.matches()) {
      return this.handleArchiveFile(context, downloadMatcher.group(1), downloadMatcher.group(2));
    }

    return ResponseEntity.notFound().build();
  }

  private ResponseEntity<Object> handleArchiveFile(
      final ProtocolContext context, final String packageName, final String fileName)
      throws IOException {

    final Resource resource;

    try {
      resource = this.facade.downloadArchiveFile(context, packageName, fileName);
    } catch (final ItemNotFoundException _) {
      return ResponseEntity.notFound().build();
    }

    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            AbstractPypiFileDownloadProtocolMethodHandler.contentDisposition(fileName))
        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
        .contentLength(resource.contentLength())
        .build();
  }

  private ResponseEntity<Object> handleSimple(
      final ProtocolContext context,
      final HttpServletRequest request,
      final @Nullable String packageName) {

    if (packageName == null) {
      // The path parser already guarantees the repo exists for `/simple/`.
      return ResponseEntity.ok().build();
    }

    final var normalizedUri = this.getNormalizedUri(request, packageName);
    if (normalizedUri != null) {
      return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT).location(normalizedUri).build();
    }

    return this.facade.packageExists(context, packageName)
        ? ResponseEntity.ok().build()
        : ResponseEntity.notFound().build();
  }
}
