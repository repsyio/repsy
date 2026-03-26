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
package io.repsy.libs.multiport.configs;

import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.libs.multiport.configs.props.MultiPortProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.webmvc.autoconfigure.WebMvcRegistrations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Configuration
@RequiredArgsConstructor
public class RequestMappingConfiguration {

  private final @NonNull MultiPortProperties multiPortProperties;

  @Bean
  public @NonNull WebMvcRegistrations webMvcRegistrations() {

    return new WebMvcRegistrations() {
      @Override
      public @NonNull RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
        return new PortBasedRequestMappingHandlerMapping();
      }
    };
  }

  private record MatchedHandler(
      @NonNull RequestMappingInfo mappingInfo, @NonNull HandlerMethod handlerMethod) {}

  private final class PortBasedRequestMappingHandlerMapping extends RequestMappingHandlerMapping {

    private final @NonNull Map<@NonNull Integer, @NonNull List<@NonNull MatchedHandler>>
        portHandlerCache = new HashMap<>();

    private volatile boolean cacheReady = false;

    @Override
    protected @Nullable HandlerMethod lookupHandlerMethod(
        final @NonNull String lookupPath, final @NonNull HttpServletRequest request) {

      this.ensureCacheReady();

      final var localPort = request.getLocalPort();
      final var candidates = this.portHandlerCache.getOrDefault(localPort, List.of());

      final var handlerOptional =
          candidates.stream()
              .map(
                  entry -> {
                    this.clearPathAttributes(request);
                    final var matched = entry.mappingInfo().getMatchingCondition(request);
                    return matched != null
                        ? new MatchedHandler(matched, entry.handlerMethod())
                        : null;
                  })
              .filter(Objects::nonNull)
              .min(
                  Comparator.comparing(
                      MatchedHandler::mappingInfo, (a, b) -> a.compareTo(b, request)));

      this.clearPathAttributes(request);

      if (handlerOptional.isEmpty()) {
        return null;
      }

      final var handler = handlerOptional.get();

      this.handleMatch(handler.mappingInfo(), lookupPath, request);

      return handler.handlerMethod();
    }

    private void ensureCacheReady() {

      if (this.cacheReady) {
        return;
      }

      synchronized (this.portHandlerCache) {
        if (this.cacheReady) {
          return;
        }

        this.buildCache();
        this.cacheReady = true;
      }
    }

    private void buildCache() {

      final var mainPort =
          Integer.parseInt(RequestMappingConfiguration.this.multiPortProperties.getMainPort());

      for (final var entry : this.getHandlerMethods().entrySet()) {
        final var mappingInfo = entry.getKey();
        final var handlerMethod = entry.getValue();
        final var port = this.resolvePort(handlerMethod, mainPort);

        this.portHandlerCache
            .computeIfAbsent(port, _ -> new ArrayList<>())
            .add(new MatchedHandler(mappingInfo, handlerMethod));
      }
    }

    private int resolvePort(final @NonNull HandlerMethod handlerMethod, final int mainPort) {

      final var annotation =
          this.findRestApiPort(handlerMethod.getBeanType(), handlerMethod.getMethod());

      if (annotation == null) {
        return mainPort;
      }

      if (annotation.value().isEmpty()) {
        throw new IllegalArgumentException(
            "Missing value in @RestApiPort annotation for " + handlerMethod.getBeanType());
      }

      return RequestMappingConfiguration.this.multiPortProperties.getPortFor(annotation.value());
    }

    private @Nullable RestApiPort findRestApiPort(
        final @NonNull Class<?> controllerClass, final @NonNull Method method) {

      final var methodAnnotation = AnnotationUtils.findAnnotation(method, RestApiPort.class);

      if (methodAnnotation != null) {
        return methodAnnotation;
      }

      return AnnotationUtils.findAnnotation(controllerClass, RestApiPort.class);
    }

    private void clearPathAttributes(final @NonNull HttpServletRequest request) {
      request.removeAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
      request.removeAttribute(HandlerMapping.MATRIX_VARIABLES_ATTRIBUTE);
    }
  }
}
