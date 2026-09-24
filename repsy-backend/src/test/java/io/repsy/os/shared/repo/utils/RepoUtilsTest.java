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
package io.repsy.os.shared.repo.utils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("RepoUtils")
class RepoUtilsTest {

  @Nested
  @DisplayName("validateRepoName")
  class ValidateRepoName {

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"login", "profile", "users", "security", "api", "assets"})
    @DisplayName("does not reject a reserved name: it only checks the character set (RPS-1158)")
    void doesNotRejectReservedNames(final String name) {
      assertThatCode(() -> RepoUtils.validateRepoName(name)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"has space", "a/b", "dot.name", "café", ".."})
    @DisplayName("rejects a name outside the allowed character set")
    void rejectsDisallowedCharacters(final String name) {
      assertThatThrownBy(() -> RepoUtils.validateRepoName(name))
          .isInstanceOf(AccessNotAllowedException.class)
          .hasMessage("invalidRequest");
    }
  }

  @Nested
  @DisplayName("validateNewRepoName")
  class ValidateNewRepoName {

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(
        strings = {
          "login",
          "profile",
          "repositories",
          "users",
          "security",
          "not-found",
          "api",
          "assets",
          "counts",
          "security-summary"
        })
    @DisplayName("rejects every reserved name that is also a legal character set (RPS-1158)")
    void rejectsReservedNames(final String name) {
      assertThatThrownBy(() -> RepoUtils.validateNewRepoName(name))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("repoNameReserved");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(
        strings = {
          "LOGIN",
          "Profile",
          "USERS",
          "Not-Found",
          "API",
          "ASSETS",
          "Counts",
          "SECURITY-SUMMARY"
        })
    @DisplayName("compares reserved names case-insensitively")
    void rejectsReservedNamesRegardlessOfCase(final String name) {
      assertThatThrownBy(() -> RepoUtils.validateNewRepoName(name))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("repoNameReserved");
    }

    @Test
    @DisplayName(
        "rejects 'favicon.ico' too, but for its dot: the character-set check runs first and never"
            + " lets it reach the reserved-name check")
    void faviconIsAlreadyRejectedByTheCharacterSet() {
      assertThatThrownBy(() -> RepoUtils.validateNewRepoName("favicon.ico"))
          .isInstanceOf(AccessNotAllowedException.class)
          .hasMessage("invalidRequest");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"my-repo", "logins", "not-found-2", "apis", "loginx"})
    @DisplayName("accepts a name that is not exactly a reserved one")
    void acceptsNonReservedNames(final String name) {
      assertThatCode(() -> RepoUtils.validateNewRepoName(name)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"has space", "a/b", "café"})
    @DisplayName("still rejects a disallowed character before checking the reserved list")
    void stillChecksCharacterSet(final String name) {
      assertThatThrownBy(() -> RepoUtils.validateNewRepoName(name))
          .isInstanceOf(AccessNotAllowedException.class)
          .hasMessage("invalidRequest");
    }
  }
}
