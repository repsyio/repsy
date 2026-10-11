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
package io.repsy.protocols.maven.protocol.handlers;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.exceptions.RedirectToSlashEndedLocationException;
import io.repsy.protocols.maven.protocol.MavenProtocolProvider;
import io.repsy.protocols.maven.protocol.facades.contracts.MavenProtocolFacade;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public abstract class AbstractMavenDownloadProtocolMethodHandler<ID>
    extends AbstractFacadeProtocolMethodHandler<MavenProtocolFacade<ID>> {

  public AbstractMavenDownloadProtocolMethodHandler(
      final PathParser pathParser,
      final MavenProtocolFacade<ID> mavenProtocolFacade,
      final MavenProtocolProvider provider) {
    super(
        HandlerRoute.read(HttpMethod.GET).method("download").head(),
        pathParser,
        mavenProtocolFacade,
        provider);
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    final Resource resource;

    try {
      resource = this.facade.download(context);
    } catch (final RedirectToSlashEndedLocationException _) {
      return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
          .location(new URI(request.getServletPath() + "/"))
          .build();
    } catch (final ItemNotFoundException _) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    return MavenResourceResponses.ok(resource).body(resource);
  }

  /**
   * The status and headers of the {@code GET}, with the real file's length. The resource is lazy
   * for a file (nothing is opened), so asking is cheap; the body is never streamed.
   */
  @Override
  public ResponseEntity<Object> handleHead(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    final Resource resource;

    try {
      resource = this.facade.download(context);
    } catch (final RedirectToSlashEndedLocationException _) {
      return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
          .location(new URI(request.getServletPath() + "/"))
          .build();
    } catch (final ItemNotFoundException _) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    return MavenResourceResponses.ok(resource).contentLength(resource.contentLength()).build();
  }
}
