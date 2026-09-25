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
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Answers a {@code HEAD} like the {@code GET} of the same path would, without the body (RPS-1368):
 * 200 with the file's headers and its {@code Content-Length} when the path exists, 404 when it does
 * not, and the same 307 to the slash-ended location for a directory. Ivy and sbt ask with a {@code
 * HEAD} whether a file exists before they publish it (unless overwriting is on) and before they
 * resolve it, so a 200 for a path that was never published made them refuse the first publish of
 * every release.
 *
 * <p>The authorization is the one of the {@code GET}: read permission, decided by the same
 * pre-processor. A {@code HEAD} is not a download, so it is not counted as one.
 */
@NullMarked
public abstract class AbstractMavenHeadProtocolMethodHandler<ID> implements ProtocolMethodHandler {

  private final PathParser pathParser;
  private final MavenProtocolFacade<ID> mavenProtocolFacade;

  public AbstractMavenHeadProtocolMethodHandler(
      final PathParser pathParser,
      final MavenProtocolFacade<ID> mavenProtocolFacade,
      final MavenProtocolProvider provider) {

    provider.registerMethodHandler(this);

    this.pathParser = pathParser;
    this.mavenProtocolFacade = mavenProtocolFacade;
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of(
        "permission", Permission.READ, "writeOperation", false, "skipUsagePostProcessor", true);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.HEAD);
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

    // lazy for a file (nothing is opened), so asking is cheap; the body is never streamed
    try {
      resource = this.mavenProtocolFacade.download(context);
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
