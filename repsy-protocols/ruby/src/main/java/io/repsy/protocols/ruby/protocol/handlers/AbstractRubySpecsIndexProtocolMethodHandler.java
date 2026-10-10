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
package io.repsy.protocols.ruby.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.ruby.protocol.RubyProtocolProvider;
import io.repsy.protocols.ruby.protocol.facades.contracts.RubyProtocolFacade;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractRubySpecsIndexProtocolMethodHandler
    extends AbstractFacadeProtocolMethodHandler<RubyProtocolFacade> {

  private static final String SPECS_PATH = "/specs.4.8.gz";
  private static final String LATEST_SPECS_PATH = "/latest_specs.4.8.gz";
  private static final String PRERELEASE_SPECS_PATH = "/prerelease_specs.4.8.gz";
  private static final Set<String> SPECS_PATHS =
      Set.of(SPECS_PATH, LATEST_SPECS_PATH, PRERELEASE_SPECS_PATH);

  protected AbstractRubySpecsIndexProtocolMethodHandler(
      final PathParser basePathParser,
      final RubyProtocolFacade facade,
      final RubyProtocolProvider provider) {

    super(
        HandlerRoute.read(HttpMethod.GET).path(SPECS_PATHS::contains).head(),
        basePathParser,
        facade,
        provider);
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {
    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();
    final var raw = this.resolveSpecs(context, relativePath);
    // Without a header of its own Spring names the download "f.txt" (RPS-1442).
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            Objects.requireNonNull(RubyContentDisposition.forPath(relativePath)))
        .body(gzip(raw));
  }

  private byte[] resolveSpecs(final ProtocolContext context, final String relativePath) {
    if (LATEST_SPECS_PATH.equals(relativePath)) {
      return this.facade.getLatestSpecs(context);
    }
    if (PRERELEASE_SPECS_PATH.equals(relativePath)) {
      return this.facade.getPrereleaseSpecs(context);
    }
    return this.facade.getSpecs(context);
  }

  private static byte[] gzip(final byte[] raw) {
    try {
      final var out = new ByteArrayOutputStream(raw.length);
      try (final var gzip = new GZIPOutputStream(out)) {
        gzip.write(raw);
      }
      return out.toByteArray();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The headers of the {@code GET}; the index always exists and is not built. */
  @Override
  public ResponseEntity<Object> handleHead(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();

    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            Objects.requireNonNull(RubyContentDisposition.forPath(relativePath)))
        .build();
  }
}
