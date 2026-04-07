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

import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.golang.protocol.GolangProtocolProvider;
import io.repsy.protocols.golang.protocol.facades.contracts.GoProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractGoUploadProtocolMethodHandler<ID> implements ProtocolMethodHandler {

  private final PathParser pathParser;
  private final GoProtocolFacade<ID> goProtocolFacade;

  public AbstractGoUploadProtocolMethodHandler(
      final PathParser pathParser,
      final GoProtocolFacade<ID> goProtocolFacade,
      final GolangProtocolProvider provider) {

    this.pathParser = pathParser;
    this.goProtocolFacade = goProtocolFacade;
    provider.registerMethodHandler(this);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.WRITE, "writeOperation", true, "method", "upload");
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.PUT);
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

    final var sha256 = request.getHeader("Content-Sha256");
    if (sha256 != null) {
      context.addProperty("contentSha256", sha256);
    }

    try {
      this.goProtocolFacade.upload(
          context, request.getInputStream(), request.getContentLengthLong());
    } catch (final UnAuthorizedException _) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .header(WWW_AUTHENTICATE, "Basic realm=\"Repsy Go Module Proxy\"")
          .build();
    }

    return ResponseEntity.ok().build();
  }
}
