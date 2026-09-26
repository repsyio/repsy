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
package io.repsy.os.server.protocols.shared.controllers;

import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Enumerates the panel routes that carry {@link RepoOperation}, straight from the handler mapping,
 * so a route added later is covered by the tests that use this without touching them
 * (ManageRoutesStatusIT, RepoOperationRoutesStatusIT).
 */
final class RepoOperationRoutes {

  static final String PANEL_PREFIX = "/api/";

  private static final Pattern VARIABLE = Pattern.compile("\\{([^}:/]+)(?::[^}]*)?}");

  private RepoOperationRoutes() {}

  /** One method and path template of a handler annotated with {@link RepoOperation}. */
  record Route(HttpMethod method, String template, String operation, Permission permission) {

    String urlFor(final String repoName) {
      return VARIABLE
          .matcher(this.template)
          .replaceAll(
              match ->
                  switch (match.group(1)) {
                    case "repoName" -> repoName;
                    default -> "placeholder";
                  });
    }

    @Override
    public String toString() {
      return this.method + " " + this.template + " (" + this.operation + ")";
    }
  }

  /** Every {@code @RepoOperation} route of the API port, of any permission. */
  static List<Route> all(final RequestMappingHandlerMapping handlerMapping) {
    return collect(handlerMapping, null);
  }

  /** The {@code @RepoOperation} routes of the API port that need the given permission. */
  static List<Route> needing(
      final RequestMappingHandlerMapping handlerMapping, final Permission permission) {
    return collect(handlerMapping, permission);
  }

  /**
   * The {@code @RepoOperation} routes whose path is not under {@link #PANEL_PREFIX}, on any port.
   * The auth interceptor is registered for {@code /api/**} only, so the list has to be empty.
   */
  static List<String> outsideThePanelPrefix(final RequestMappingHandlerMapping handlerMapping) {
    final var outside = new ArrayList<String>();

    for (final var entry : handlerMapping.getHandlerMethods().entrySet()) {
      if (entry.getValue().getMethodAnnotation(RepoOperation.class) == null) {
        continue;
      }

      for (final var pattern : entry.getKey().getPatternValues()) {
        if (!pattern.startsWith(PANEL_PREFIX)) {
          outside.add(pattern + " -> " + entry.getValue());
        }
      }
    }

    return outside;
  }

  private static List<Route> collect(
      final RequestMappingHandlerMapping handlerMapping, final Permission permission) {

    final var routes = new ArrayList<Route>();

    for (final var entry : handlerMapping.getHandlerMethods().entrySet()) {
      final var handler = entry.getValue();
      final var operation = handler.getMethodAnnotation(RepoOperation.class);

      if (operation == null
          || (permission != null && operation.permission() != permission)
          || !isOnTheApiPort(handler)) {
        continue;
      }

      for (final var pattern : entry.getKey().getPatternValues()) {
        if (!pattern.startsWith(PANEL_PREFIX)) {
          continue;
        }

        for (final var method : entry.getKey().getMethodsCondition().getMethods()) {
          routes.add(
              new Route(
                  HttpMethod.valueOf(method.name()),
                  pattern,
                  handler.getMethod().getName(),
                  operation.permission()));
        }
      }
    }

    return routes;
  }

  private static boolean isOnTheApiPort(final HandlerMethod handler) {
    return AnnotationUtils.findAnnotation(handler.getMethod(), RestApiPort.class) != null
        || AnnotationUtils.findAnnotation(handler.getBeanType(), RestApiPort.class) != null;
  }
}
