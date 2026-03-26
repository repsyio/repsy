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
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.libs.storage.core.exceptions.RedirectToSlashEndedLocationException;
import io.repsy.protocols.maven.protocol.MavenProtocolProvider;
import io.repsy.protocols.maven.protocol.facades.contracts.MavenProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractMavenDownloadProtocolMethodHandler<ID>
    implements ProtocolMethodHandler {

  private final PathParser pathParser;
  private final MavenProtocolFacade<ID> mavenProtocolFacade;

  public AbstractMavenDownloadProtocolMethodHandler(
      final PathParser pathParser,
      final MavenProtocolFacade<ID> mavenProtocolFacade,
      final MavenProtocolProvider provider) {

    this.mavenProtocolFacade = mavenProtocolFacade;
    this.pathParser = pathParser;

    provider.registerMethodHandler(this);
  }

  @Override
  public Map<String, Object> getProperties() {

    return Map.of("permission", Permission.READ, "writeOperation", false, "method", "download");
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {

    return List.of(HttpMethod.GET);
  }

  @Override
  public PathParser getPathParser() {

    return this.pathParser;
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    final Resource resource;

    try {
      resource = this.mavenProtocolFacade.download(context);
    } catch (final RedirectToSlashEndedLocationException _) {
      return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
          .location(new URI(request.getServletPath() + "/"))
          .build();
    } catch (final ItemNotFoundException _) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    return this.buildSuccessResponse(resource);
  }

  private ResponseEntity<Object> buildSuccessResponse(final Resource resource) {

    final var builder = ResponseEntity.ok();

    if (resource instanceof ByteArrayResource) {
      builder.contentType(MediaType.TEXT_HTML);
    } else {
      builder.contentType(MediaType.APPLICATION_OCTET_STREAM);
      builder.header(
          HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + resource.getFilename());
    }

    return builder.body(resource);
  }
}
