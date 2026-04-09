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
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.golang.protocol.GolangProtocolProvider;
import io.repsy.protocols.golang.protocol.facades.contracts.GoProtocolFacade;
import io.repsy.protocols.golang.shared.exceptions.GoVersionGoneException;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractGoDownloadProtocolMethodHandler<ID> implements ProtocolMethodHandler {

  private final PathParser pathParser;
  private final GoProtocolFacade<ID> goProtocolFacade;

  public AbstractGoDownloadProtocolMethodHandler(
      final PathParser pathParser,
      final GoProtocolFacade<ID> goProtocolFacade,
      final GolangProtocolProvider provider) {

    this.pathParser = pathParser;
    this.goProtocolFacade = goProtocolFacade;
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
      final HttpServletResponse response) {

    try {
      final var resource = this.goProtocolFacade.download(context);

      if (!resource.exists()) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
      }

      return ResponseEntity.ok().contentType(this.resolveContentType(context)).body(resource);
    } catch (final GoVersionGoneException _) {
      return ResponseEntity.status(HttpStatus.GONE).build();
    } catch (final ItemNotFoundException _) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
  }

  private MediaType resolveContentType(final ProtocolContext context) {
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();

    if (path.endsWith("/@v/list") || path.endsWith(".mod")) {
      return MediaType.TEXT_PLAIN;
    }

    if (path.endsWith(".info") || path.endsWith("/@latest")) {
      return MediaType.APPLICATION_JSON;
    }

    return MediaType.APPLICATION_OCTET_STREAM;
  }
}
