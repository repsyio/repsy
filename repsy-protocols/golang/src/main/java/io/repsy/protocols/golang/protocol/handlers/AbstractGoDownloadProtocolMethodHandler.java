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
package io.repsy.protocols.golang.protocol.handlers;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.golang.protocol.GolangProtocolProvider;
import io.repsy.protocols.golang.protocol.facades.contracts.GoProtocolFacade;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

public abstract class AbstractGoDownloadProtocolMethodHandler<ID>
    extends AbstractFacadeProtocolMethodHandler<GoProtocolFacade<ID>> {

  public AbstractGoDownloadProtocolMethodHandler(
      final PathParser pathParser,
      final GoProtocolFacade<ID> goProtocolFacade,
      final GolangProtocolProvider provider) {

    super(
        HandlerRoute.read(HttpMethod.GET).method("download").head(),
        pathParser,
        goProtocolFacade,
        provider);
  }

  /** The parser the backend passes in decides the whole path, so it is used as it is. */
  @Override
  public PathParser getPathParser() {
    return this.basePathParser();
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    try {
      final var resource = this.facade.download(context);

      if (!resource.exists()) {
        return notFound(context);
      }

      return GoDownloadResponses.ok(context).body(resource);
    } catch (final ItemNotFoundException _) {
      return notFound(context);
    }
  }

  private static ResponseEntity<Object> notFound(final ProtocolContext context) {
    return GoDownloadResponses.notFound().body(GoDownloadResponses.notFoundText(context));
  }

  /** The status and headers of the {@code GET} (and a bare 404), with the file's length. */
  @Override
  public ResponseEntity<Object> handleHead(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws IOException {

    try {
      final var resource = this.facade.download(context);

      if (!resource.exists()) {
        return GoDownloadResponses.notFound().build();
      }

      return GoDownloadResponses.ok(context).contentLength(resource.contentLength()).build();
    } catch (final ItemNotFoundException _) {
      return GoDownloadResponses.notFound().build();
    }
  }
}
