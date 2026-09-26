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
import io.repsy.protocols.shared.auth.BasicAuthChallenge;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractGoUploadProtocolMethodHandler<ID> implements ProtocolMethodHandler {

  private static final String DEFAULT_UNAUTHORIZED_MSG_ID = "unAuthorized";

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
    } catch (final UnAuthorizedException e) {
      // The go command prints the body of a failed answer only when it is text/plain, and curl or
      // a custom client would see nothing at all with an empty one (RPS-1450, as RPS-1435 did for
      // the auth pre-processor).
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .header(WWW_AUTHENTICATE, BasicAuthChallenge.REPSY)
          .contentType(MediaType.TEXT_PLAIN)
          .body(this.unauthorizedText(e.getMessage()));
    }

    return ResponseEntity.ok().build();
  }

  /**
   * The text of the 401 body for the message id the exception carries. The id itself is the
   * fallback; the application overrides it to resolve the id into its message, the same text {@code
   * GolangAuthPreProcessor} answers.
   */
  protected String unauthorizedText(final @Nullable String msgId) {
    return msgId == null ? DEFAULT_UNAUTHORIZED_MSG_ID : msgId;
  }
}
