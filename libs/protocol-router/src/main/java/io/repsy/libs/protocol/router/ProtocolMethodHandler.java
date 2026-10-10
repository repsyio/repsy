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
package io.repsy.libs.protocol.router;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

public interface ProtocolMethodHandler {

  List<HttpMethod> getSupportedMethods();

  Map<String, Object> getProperties();

  PathParser getPathParser();

  ResponseEntity<Object> handle(
      ProtocolContext parsedPath, HttpServletRequest request, HttpServletResponse response)
      throws Exception;

  /**
   * Whether this handler also answers a {@code HEAD} for the paths it parses for its own method
   * (the router's HEAD fallback). A {@code HEAD} is first offered to the handlers registered for
   * {@code HEAD} itself; only when none of them parses it, the handlers that answer {@code HEAD}
   * for their own route are asked, in registration order.
   */
  default boolean answersHead() {
    return false;
  }

  /**
   * The properties the processors see for a {@code HEAD} answered by {@link #handleHead}. A {@code
   * HEAD} is not a download, so these usually differ from {@link #getProperties()}.
   */
  default Map<String, Object> getHeadProperties() {
    return this.getProperties();
  }

  /**
   * Answers the {@code HEAD} of a path this handler parsed: the status and the headers of the
   * {@code GET}, without a body (the body is never streamed).
   */
  default ResponseEntity<Object> handleHead(
      final ProtocolContext parsedPath,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {
    throw new UnsupportedOperationException("This handler does not answer HEAD");
  }
}
