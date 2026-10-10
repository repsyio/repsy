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
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractRubyGemDownloadProtocolMethodHandler
    extends AbstractFacadeProtocolMethodHandler<RubyProtocolFacade> {

  private static final Pattern DOWNLOAD_PATTERN = Pattern.compile("^/gems/(.+\\.gem)$");

  protected AbstractRubyGemDownloadProtocolMethodHandler(
      final PathParser basePathParser,
      final RubyProtocolFacade facade,
      final RubyProtocolProvider provider) {

    super(
        HandlerRoute.read(HttpMethod.GET).path(DOWNLOAD_PATTERN.asMatchPredicate()),
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
    final var matcher = DOWNLOAD_PATTERN.matcher(relativePath);
    if (!matcher.matches()) {
      return ResponseEntity.notFound().build();
    }
    try {
      final var filename = matcher.group(1);
      final var resource = this.facade.downloadGem(context, filename);
      // Without a header of its own Spring names the download "f.txt" and shows it inline
      // (RPS-1389).
      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
          .header(
              HttpHeaders.CONTENT_DISPOSITION,
              Objects.requireNonNull(RubyContentDisposition.forPath(relativePath)))
          .body(resource);
    } catch (final Exception e) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
  }
}
