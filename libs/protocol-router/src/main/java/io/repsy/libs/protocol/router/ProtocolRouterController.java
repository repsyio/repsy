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

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class ProtocolRouterController {

  private static final String ROBOTS_TXT_CONTENT =
      """
    User-agent: *
    Disallow: /
  """;

  private static final Map<String, Supplier<ResponseEntity<Object>>> STATIC_CONTENT_HANDLERS =
      Map.of(
          "/robots.txt",
          ProtocolRouterController::handleRobotsTxt,
          "/favicon.ico",
          ProtocolRouterController::handleFavicon);

  private final Map<HttpMethod, List<SpecifiedProtocolMethodHandler>> methodHandlersMap =
      new HashMap<>();
  private final Map<String, ProtocolProvider> providerMap = new HashMap<>();

  public ProtocolRouterController(
      final List<ProtocolProvider> protocolProviders,
      final List<ProtocolMethodHandler> ignoredAllHandlers) {

    for (final var provider : protocolProviders) {
      this.providerMap.put(provider.getProtocolType(), provider);
      this.registerHandlers(provider);
    }
  }

  @RequestMapping("/**")
  public ResponseEntity<Object> route(
      final HttpServletRequest request, final HttpServletResponse response) throws Exception {

    // Handle static content (robots.txt, favicon.ico)
    final var staticContentResponseOptional = this.handleStaticContent(request);

    if (staticContentResponseOptional.isPresent()) {
      return staticContentResponseOptional.get();
    }

    final var httpMethod = HttpMethod.valueOf(request.getMethod());
    final var handlers = this.methodHandlersMap.get(httpMethod);

    for (final var specifiedHandler : handlers) {
      final var handler = specifiedHandler.protocolMethodHandler();
      final var context = handler.getPathParser().parse(request);

      if (context.isEmpty()) {
        continue;
      }

      final var provider = this.providerMap.get(specifiedHandler.protocolType());
      final var properties = handler.getProperties();

      final var preProcessorResult =
          provider.preProcess(context.get(), request, response, properties);

      if (!preProcessorResult.isEmpty()) {
        return preProcessorResult.getResult();
      }

      final var result = handler.handle(context.get(), request, response);

      final var postProcessorResult =
          provider.postProcess(context.get(), request, response, properties);

      if (!postProcessorResult.isEmpty()) {
        return postProcessorResult.getResult();
      }

      return result;
    }

    throw new ItemNotFoundException("unknownPath");
  }

  private void registerHandlers(final ProtocolProvider provider) {

    for (final var entry : provider.getMethodHandlersMap().entrySet()) {
      this.methodHandlersMap
          .computeIfAbsent(entry.getKey(), _ -> new ArrayList<>())
          .addAll(entry.getValue());
    }
  }

  private Optional<ResponseEntity<Object>> handleStaticContent(final HttpServletRequest request) {

    final var path = request.getServletPath();
    final var handler = STATIC_CONTENT_HANDLERS.get(path);

    return Optional.ofNullable(handler).map(Supplier::get);
  }

  private static ResponseEntity<Object> handleRobotsTxt() {
    return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(ROBOTS_TXT_CONTENT);
  }

  private static ResponseEntity<Object> handleFavicon() {
    return ResponseEntity.noContent().build();
  }
}
