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
import io.repsy.os.AbstractIT;
import io.repsy.os.server.protocols.shared.controllers.RepoOperationRoutes.Route;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.List;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * RPS-1558: every panel route that carries {@code @RepoOperation}, of any permission, is guarded.
 * {@code GET /api/mvn/groups/{repoName}/{groupName}} was annotated but not covered by the auth
 * interceptor's path patterns, so an anonymous caller read the statistics of a private repository,
 * and got a 404 (not a 401) for a repository or group that does not exist.
 *
 * <p>The routes are not listed here: they are every {@code @RepoOperation} handler of the API port,
 * so a route added later is covered without touching the test. The authorization runs in {@code
 * ProtocolAuthInterceptor} before a handler is invoked, so the path variables other than the
 * repository are placeholders and no request changes anything. {@link ManageRoutesStatusIT} tells
 * the callers of the MANAGE routes apart; this class is about who may not get in at all.
 */
@DisplayName("@RepoOperation routes: an anonymous caller is answered 401 (RPS-1558)")
class RepoOperationRoutesStatusIT extends AbstractIT {

  /** A floor, so an enumeration that silently finds nothing cannot pass. */
  private static final int EXPECTED_AT_LEAST = 100;

  @Autowired private RequestMappingHandlerMapping handlerMapping;

  private List<Route> routes() {
    return RepoOperationRoutes.all(this.handlerMapping);
  }

  private void expectLoginRequired(final SoftAssertions softly, final Route route, final String url)
      throws Exception {

    final var response = this.perform(request(route.method(), url)).andReturn().getResponse();
    final var description = route + " -> " + url;

    softly.assertThat(response.getStatus()).as("status of %s", description).isEqualTo(401);

    if (response.getStatus() == 401) {
      softly
          .assertThat((String) JsonPath.read(response.getContentAsString(), "$.code"))
          .as("msgId of %s", description)
          .isEqualTo("loginRequired");
    }
  }

  @Test
  @DisplayName("the enumeration finds the routes of every operation, permission and family")
  void findsTheRoutes() {
    final var routes = this.routes();

    assertThat(routes).hasSizeGreaterThanOrEqualTo(EXPECTED_AT_LEAST);
    assertThat(routes)
        .extracting(Route::permission)
        .as("permissions with a @RepoOperation route")
        .contains(Permission.READ, Permission.WRITE, Permission.MANAGE);
    assertThat(routes)
        .extracting(Route::template)
        .contains(
            "/api/mvn/groups/{repoName}/{groupName}",
            "/api/repos/{repoName}",
            "/api/repos/{repoName}/deploy-tokens");
  }

  @Test
  @DisplayName("no @RepoOperation route lies outside /api/, where the auth interceptor applies")
  void everyRouteIsUnderTheInterceptedPrefix() {
    assertThat(RepoOperationRoutes.outsideThePanelPrefix(this.handlerMapping))
        .as("@RepoOperation routes the auth interceptor does not cover")
        .isEmpty();
  }

  @Test
  @DisplayName("no credential on a PRIVATE repo of any type: 401 loginRequired on every route")
  void anonymousIsUnauthorizedOnAPrivateRepo() throws Exception {
    final var softly = new SoftAssertions();

    for (final var type : RepoType.values()) {
      final var repo = this.seedRepo(type, uniqueRepoName("priv"), true, null);

      for (final var route : this.routes()) {
        this.expectLoginRequired(softly, route, route.urlFor(repo.getName()));
      }
    }

    softly.assertAll();
  }

  @Test
  @DisplayName("no credential on a repo that does not exist: 401, not 404, on every route")
  void anonymousIsUnauthorizedOnAMissingRepo() throws Exception {
    final var softly = new SoftAssertions();

    for (final var route : this.routes()) {
      this.expectLoginRequired(softly, route, route.urlFor(uniqueRepoName("missing")));
    }

    softly.assertAll();
  }

  @Test
  @DisplayName("no credential on a PUBLIC repo: 401 on every route that is not a read")
  void anonymousMayOnlyReadAPublicRepo() throws Exception {
    final var repo = this.seedRepo(RepoType.MAVEN, uniqueRepoName("pub"));
    final var softly = new SoftAssertions();

    for (final var route : this.routes()) {
      if (route.permission() != Permission.READ) {
        this.expectLoginRequired(softly, route, route.urlFor(repo.getName()));
      }
    }

    softly.assertAll();
  }

  @Test
  @DisplayName("a signed-in user is let through to the handler of every read on a PRIVATE repo")
  void aSignedInUserIsNotBlanketRefused() throws Exception {
    final var repo = this.seedRepo(RepoType.MAVEN, uniqueRepoName("priv"), true, null);
    final var token = this.userBearerToken();
    final var softly = new SoftAssertions();
    var checked = 0;

    for (final var route : this.routes()) {
      if (route.permission() != Permission.READ || route.method() != HttpMethod.GET) {
        continue;
      }

      final var status =
          this.perform(
                  request(route.method(), route.urlFor(repo.getName()))
                      .header(AUTHORIZATION, token))
              .andReturn()
              .getResponse()
              .getStatus();

      softly.assertThat(status).as("status of %s", route).isNotIn(401, 403);
      checked++;
    }

    softly.assertAll();
    assertThat(checked).as("GET read routes tried").isGreaterThan(20);
  }
}
