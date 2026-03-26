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

import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.maven.protocol.MavenProtocolProvider;
import io.repsy.protocols.maven.protocol.facades.contracts.MavenProtocolFacade;
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
public abstract class AbstractMavenUploadProtocolMethodHandler<ID>
    implements ProtocolMethodHandler {

  private final PathParser pathParser;
  private final MavenProtocolFacade<ID> mavenProtocolFacade;

  public AbstractMavenUploadProtocolMethodHandler(
      final PathParser pathParser,
      final MavenProtocolFacade<ID> mavenProtocolFacade,
      final MavenProtocolProvider mavenProtocolProvider) {

    this.mavenProtocolFacade = mavenProtocolFacade;
    this.pathParser = pathParser;

    mavenProtocolProvider.registerMethodHandler(this);
  }

  @Override
  public Map<String, Object> getProperties() { // initContext (hashmap)

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

    try {

      this.mavenProtocolFacade.upload(
          context, request.getInputStream(), request.getContentLengthLong());
    } catch (final UnAuthorizedException _) {
      response.addHeader(WWW_AUTHENTICATE, "Basic realm=\"Repsy Managed Repository\"");
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
    }

    return ResponseEntity.ok().build();
  }
}
