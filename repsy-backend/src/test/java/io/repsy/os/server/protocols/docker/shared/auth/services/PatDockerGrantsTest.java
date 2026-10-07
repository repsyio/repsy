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
package io.repsy.os.server.protocols.docker.shared.auth.services;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.shared.token.dtos.TokenScope;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * RPS-1903: the grants a personal access token is issued at {@code /v2/token} are what was asked
 * for, the scopes of the token and what its owner may do, whichever of them is the narrowest.
 */
@DisplayName("PatDockerGrants")
class PatDockerGrantsTest {

  private static Set<TokenScope> scopes(final TokenScope... scopes) {
    return TokenScope.withImplicit(List.of(scopes));
  }

  @ParameterizedTest(name = "{0} owner with {1}, asked {2} gets {3}")
  @CsvSource(
      delimiter = '|',
      value = {
        "user  | REPO_READ   | pull,push,delete | pull",
        "admin | REPO_READ   | pull,push,delete | pull",
        "user  | REPO_WRITE  | pull,push,delete | pull,push",
        "admin | REPO_WRITE  | pull,push,delete | pull,push",
        "user  | REPO_MANAGE | pull,push,delete | pull,push",
        "admin | REPO_MANAGE | pull,push,delete | pull,push,delete",
        "admin | REPO_MANAGE | delete           | delete",
        "admin | REPO_MANAGE | pull             | pull",
        "admin | REPO_MANAGE | *                | pull,push,delete",
        "user  | REPO_MANAGE | *                | pull,push",
        "admin | REPO_WRITE  | *                | pull,push",
        "admin | REPO_READ   | *                | pull",
        "admin | REPO_MANAGE | push,pull         | pull,push"
      })
  void narrowsToTheIntersection(
      final String owner, final TokenScope scope, final String asked, final String expected) {
    final var narrowed =
        PatDockerGrants.narrow(List.of("team/app:" + asked), scopes(scope), "admin".equals(owner));

    assertThat(narrowed).containsExactly("team/app:" + expected);
  }

  @Test
  @DisplayName("a grant left without an action is dropped, the others are kept in order")
  void dropsAnEmptyGrant() {
    final var narrowed =
        PatDockerGrants.narrow(
            List.of("a/b:push", "c/d:pull", "e/f:delete"), scopes(TokenScope.REPO_READ), true);

    assertThat(narrowed).containsExactly("c/d:pull");
  }

  @Test
  @DisplayName("a token without a repo scope is granted nothing, whatever it asked for")
  void noRepoScope() {
    assertThat(PatDockerGrants.narrow(List.of("a/b:pull,push,delete,*"), scopes(), true)).isEmpty();
    assertThat(
            PatDockerGrants.narrow(
                List.of("a/b:pull"),
                EnumSet.of(TokenScope.SCAN_READ, TokenScope.PROFILE_READ),
                true))
        .isEmpty();
  }

  @Test
  @DisplayName("an action that is not pull, push or delete is never granted")
  void unknownActions() {
    assertThat(
            PatDockerGrants.narrow(
                List.of("a/b:metadata_read,admin,PULL"), scopes(TokenScope.REPO_MANAGE), true))
        .isEmpty();
  }

  @Test
  @DisplayName("a request for nothing is granted nothing, and a malformed grant is skipped")
  void nothingAsked() {
    assertThat(PatDockerGrants.narrow(List.of(), scopes(TokenScope.REPO_MANAGE), true)).isEmpty();
    assertThat(PatDockerGrants.narrow(List.of("no-colon"), scopes(TokenScope.REPO_MANAGE), true))
        .isEmpty();
  }

  @Test
  @DisplayName("the name of the grant is kept as it was asked for")
  void keepsTheName() {
    assertThat(
            PatDockerGrants.narrow(
                List.of("repo/team/app:pull"), scopes(TokenScope.REPO_READ), false))
        .containsExactly("repo/team/app:pull");
  }
}
