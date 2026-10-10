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
package io.repsy.os.server.shared.auth;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIT;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * RPS-1604: a panel access token is bound to the user it was issued to, by the {@code
 * token_version} and by the subject (the user id), on every route that takes it. That includes the
 * {@code @RepoOperation} routes ({@code /api/repos/**}, {@code /api/<format>/**}) and the Docker
 * panel routes, which resolve the caller through {@code ProtocolAuthService} and {@code
 * DockerAuthenticator} and used to look at the username claim alone: an access token kept working
 * there for up to 30 minutes after a password change, and a reused username inherited it.
 */
@DisplayName("A panel access token ends with the credentials it was issued for (RPS-1604)")
class PanelJwtUserBindingIT extends AbstractIT {

  private static final String NEW_PASSWORD = "NewPassword2@";

  /** A {@code @RepoOperation} route of every kind of caller resolution, all for an ADMIN. */
  private enum Route {
    /** {@code ProtocolAuthService.authenticateWithBearer}. */
    GENERIC(RepoType.MAVEN) {
      @Override
      String path(final String repoName) {
        return "/api/repos/" + repoName + "/settings";
      }
    },
    /** {@code DockerAuthenticator.authenticateUser}. */
    DOCKER(RepoType.DOCKER) {
      @Override
      String path(final String repoName) {
        return "/api/docker/images/" + repoName;
      }
    },
    /** A route that takes the panel token without any repo. */
    KEYSERVERS(null) {
      @Override
      String path(final String repoName) {
        return "/api/mvn/allowed-key-servers";
      }
    },
    /** {@code PanelAuthHelper}. */
    PROFILE(null) {
      @Override
      String path(final String repoName) {
        return "/api/profile";
      }
    };

    final RepoType repoType;

    Route(final RepoType repoType) {
      this.repoType = repoType;
    }

    abstract String path(String repoName);
  }

  static Stream<Arguments> routes() {
    return Stream.of(Route.values()).map(Arguments::of);
  }

  private ResultActions call(final Route route, final String repoName, final String bearer)
      throws Exception {
    return this.perform(get(route.path(repoName)).header(AUTHORIZATION, bearer));
  }

  private String repoFor(final Route route) {
    return route.repoType == null
        ? "unused"
        : this.seedRepo(route.repoType, uniqueRepoName("rps1604"), true, null).getName();
  }

  private void changePassword(final User user) throws Exception {
    this.perform(
            patch("/api/profile/password")
                .header(AUTHORIZATION, this.bearerTokenFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"%s\"}".formatted(NEW_PASSWORD)))
        .andExpect(status().isOk());
  }

  private void rename(final User user, final String newName) throws Exception {
    this.perform(
            patch("/api/profile/username")
                .header(AUTHORIZATION, this.bearerTokenFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\"}".formatted(newName)))
        .andExpect(status().isOk());
  }

  @ParameterizedTest(name = "{0}: a token works, then a password change ends it")
  @MethodSource("routes")
  void aPasswordChangeEndsThePanelToken(final Route route) throws Exception {
    final var admin = createUser(uniqueUsername("pwadm"), UserRole.ADMIN);
    final var repoName = this.repoFor(route);
    final var token = this.bearerTokenFor(admin);

    this.call(route, repoName, token).andExpect(status().isOk());

    this.changePassword(admin);

    expectError(
        this.call(route, repoName, token),
        HttpStatus.UNAUTHORIZED,
        "sessionExpired",
        "sessionExpired",
        "Session expired.");
  }

  @ParameterizedTest(name = "{0}: a token stays valid when only another user changed")
  @MethodSource("routes")
  void anUnrelatedChangeDoesNotEndThePanelToken(final Route route) throws Exception {
    final var admin = createUser(uniqueUsername("keepadm"), UserRole.ADMIN);
    final var other = createUser(uniqueUsername("keepoth"), UserRole.USER);
    final var repoName = this.repoFor(route);
    final var token = this.bearerTokenFor(admin);

    this.changePassword(other);

    this.call(route, repoName, token).andExpect(status().isOk());
  }

  @ParameterizedTest(name = "{0}: a reused username does not inherit the token of its former owner")
  @MethodSource("routes")
  void aReusedUsernameDoesNotInheritTheToken(final Route route) throws Exception {
    final var former = createUser(uniqueUsername("former"), UserRole.ADMIN);
    final var oldName = former.getUsername();
    final var repoName = this.repoFor(route);
    final var formerToken = this.bearerTokenFor(former);

    this.call(route, repoName, formerToken).andExpect(status().isOk());

    this.rename(former, uniqueUsername("renamed"));

    // Somebody registers the name that was freed. A fresh user starts at token_version 0, which is
    // what the former owner's token carries (it was issued before the rename moved the version).
    final var successor = createUser(oldName, UserRole.ADMIN);

    expectError(
        this.call(route, repoName, formerToken),
        HttpStatus.UNAUTHORIZED,
        "sessionExpired",
        "sessionExpired",
        "Session expired.");

    // The successor's own token is the one that works for the name.
    this.call(route, repoName, this.bearerTokenFor(successor)).andExpect(status().isOk());
  }

  @Test
  @DisplayName("a token that names a user by the id of another user is refused")
  void aTokenWhoseSubjectIsAnotherUserIsRefused() throws Exception {
    final var admin = createUser(uniqueUsername("subadm"), UserRole.ADMIN);
    final var other = createUser(uniqueUsername("subother"), UserRole.ADMIN);
    final var repoName = this.repoFor(Route.GENERIC);

    // Same username and version as the admin, the subject of somebody else.
    final var forged = this.bearerTokenFor(other.getId(), admin.getUsername());

    for (final var route : new Route[] {Route.GENERIC, Route.PROFILE}) {
      expectError(
          this.call(route, repoName, forged),
          HttpStatus.UNAUTHORIZED,
          "sessionExpired",
          "sessionExpired",
          "Session expired.");
    }
  }
}
