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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.jayway.jsonpath.JsonPath;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * RPS-1284: what every panel route that needs {@code MANAGE} answers, told apart by who asks.
 *
 * <ul>
 *   <li>a signed-in USER (no ADMIN role): 403 {@code accessDenied}, whether the repository exists
 *       or not, so a missing repository is not revealed either;
 *   <li>no credential: 401 {@code unAuthorized};
 *   <li>a token that does not verify: 401 {@code accessNotAllowed};
 *   <li>a valid token of an account that is gone: 401 {@code unAuthorized}.
 * </ul>
 *
 * <p>The routes are not listed here: they are every panel handler of
 * {@code @RepoOperation(MANAGE)}, so a route added later is covered without touching the test. The
 * authorization runs in {@code ProtocolAuthInterceptor} before a handler is invoked, so the path
 * variables other than the repository (and its type) are placeholders and no request changes
 * anything.
 */
@DisplayName("MANAGE routes: 403 for a user who is not allowed, 401 for a bad credential")
class ManageRoutesStatusIT extends AbstractIntegrationTest {

  private static final String PANEL_PREFIX = "/api/";
  private static final Pattern VARIABLE = Pattern.compile("\\{([^}:/]+)(?::[^}]*)?}");

  /** A floor, so an enumeration that silently finds nothing cannot pass. */
  private static final int EXPECTED_AT_LEAST = 30;

  @Autowired private RequestMappingHandlerMapping handlerMapping;

  private record Route(HttpMethod method, String template) {

    String urlFor(final String repoName) {
      return VARIABLE
          .matcher(this.template)
          .replaceAll(
              match ->
                  switch (match.group(1)) {
                    case "repoName" -> repoName;
                    case "repoType" -> RepoType.MAVEN.name();
                    default -> "placeholder";
                  });
    }

    @Override
    public String toString() {
      return this.method + " " + this.template;
    }
  }

  private List<Route> manageRoutes() {
    final var routes = new ArrayList<Route>();

    for (final var entry : this.handlerMapping.getHandlerMethods().entrySet()) {
      final var handler = entry.getValue();
      final var operation = handler.getMethodAnnotation(RepoOperation.class);

      if (operation == null
          || operation.permission() != Permission.MANAGE
          || !isOnTheApiPort(handler)) {
        continue;
      }

      for (final var pattern : entry.getKey().getPatternValues()) {
        if (!pattern.startsWith(PANEL_PREFIX)) {
          continue;
        }

        for (final var method : entry.getKey().getMethodsCondition().getMethods()) {
          routes.add(new Route(HttpMethod.valueOf(method.name()), pattern));
        }
      }
    }

    return routes;
  }

  private static boolean isOnTheApiPort(final HandlerMethod handler) {
    return AnnotationUtils.findAnnotation(handler.getMethod(), RestApiPort.class) != null
        || AnnotationUtils.findAnnotation(handler.getBeanType(), RestApiPort.class) != null;
  }

  private void expectError(
      final SoftAssertions softly,
      final Route route,
      final String url,
      final String authorization,
      final int status,
      final String msgId)
      throws Exception {

    var builder = request(route.method(), url);

    if (authorization != null) {
      builder = builder.header(AUTHORIZATION, authorization);
    }

    final var response = this.perform(builder).andReturn().getResponse();
    final var description = route + " -> " + url;

    softly.assertThat(response.getStatus()).as("status of %s", description).isEqualTo(status);

    if (response.getStatus() == status) {
      softly
          .assertThat((String) JsonPath.read(response.getContentAsString(), "$.msgId"))
          .as("msgId of %s", description)
          .isEqualTo(msgId);
    }
  }

  @Test
  @DisplayName("the enumeration finds the MANAGE routes of every route family")
  void findsTheRoutes() {
    final var routes = this.manageRoutes();
    final var families = new TreeSet<String>();

    routes.forEach(
        route ->
            families.add(route.template().split("/")[2] + "/" + route.template().split("/")[3]));

    assertThat(routes).hasSizeGreaterThanOrEqualTo(EXPECTED_AT_LEAST);
    assertThat(families)
        .as("route families with a MANAGE operation")
        .contains(
            "repos/{repoName}",
            "repos/{repoType}",
            "mvn/key-stores",
            "mvn/artifacts",
            "npm/packages",
            "pypi/packages",
            "docker/images",
            "helm/charts",
            "cargo/crates",
            "go/modules",
            "nuget/packages",
            "ruby/gems");
  }

  @Test
  @DisplayName("a signed-in USER is answered 403 accessDenied, for a missing repository too")
  void userIsForbidden() throws Exception {
    final var repo = this.seedRepo(RepoType.MAVEN, uniqueRepoName("manage"));
    final var token = this.userBearerToken();
    final var softly = new SoftAssertions();

    for (final var route : this.manageRoutes()) {
      this.expectError(softly, route, route.urlFor(repo.getName()), token, 403, "accessDenied");
      this.expectError(
          softly, route, route.urlFor(uniqueRepoName("missing")), token, 403, "accessDenied");
    }

    softly.assertAll();
  }

  @Test
  @DisplayName("no credential, or one that is not valid, is a 401 with a msgId of its own")
  void badCredentialsAreUnauthorized() throws Exception {
    final var repo = this.seedRepo(RepoType.MAVEN, uniqueRepoName("manage"));
    final var valid = this.userBearerToken();
    final var ghost = this.bearerTokenFor(UUID.randomUUID(), uniqueUsername("ghost"));
    final var softly = new SoftAssertions();

    for (final var route : this.manageRoutes()) {
      final var url = route.urlFor(repo.getName());

      this.expectError(softly, route, url, null, 401, "unAuthorized");
      this.expectError(softly, route, url, valid + "x", 401, "accessNotAllowed");
      this.expectError(softly, route, url, ghost, 401, "unAuthorized");
    }

    softly.assertAll();
  }
}
