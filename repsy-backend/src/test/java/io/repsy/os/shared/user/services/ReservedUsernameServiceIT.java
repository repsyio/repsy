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
package io.repsy.os.shared.user.services;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.os.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * RPS-1050: the reserved-name lookup against the seeded {@code reserved_username} table (Flyway),
 * which holds lower-case names. The panel forms only accept lower case, so this is where the
 * case-insensitivity is exercised end to end, independent of the request validation.
 */
@DisplayName("ReservedUsernameService")
class ReservedUsernameServiceIT extends AbstractIntegrationTest {

  @Autowired private ReservedUsernameService reservedUsernameService;

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "anonymous",
        "repsy",
        "docker",
        "Anonymous",
        "REPSY",
        "Docker",
        "dOcKeR",
        "GoLang"
      })
  @DisplayName("requireNotReserved answers usernameInUse for a seeded name in any case")
  void reservedInAnyCase(final String username) {
    assertThatThrownBy(() -> this.reservedUsernameService.requireNotReserved(username))
        .isExactlyInstanceOf(BadRequestException.class)
        .hasMessage("usernameInUse");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"alice", "Alice", "repsy-fan", "anonymous1"})
  @DisplayName("requireNotReserved accepts a name that is not reserved")
  void notReserved(final String username) {
    assertThatCode(() -> this.reservedUsernameService.requireNotReserved(username))
        .doesNotThrowAnyException();
  }
}
