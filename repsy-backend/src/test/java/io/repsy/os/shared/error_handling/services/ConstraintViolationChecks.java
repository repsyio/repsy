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
package io.repsy.os.shared.error_handling.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * What {@link ErrorHandlerConstraintViolationIT} (PostgreSQL) and {@link
 * H2ErrorHandlerConstraintViolationIT} (embedded H2) share: a {@code users} fixture, a way to make
 * the database reject a statement, and the check of what {@link ErrorHandler} answers to the
 * exception that becomes. Both databases must map a violation alike, and running the same
 * assertions against each is what proves it (RPS-1012, RPS-1079).
 *
 * <p>The {@code users} table is the fixture because it has a {@code varchar(25)} username, a unique
 * username index and a check on the role in both schemas.
 */
final class ConstraintViolationChecks {

  /** The length of the {@code users.username} column. */
  static final int USERNAME_LENGTH = 25;

  private static final String PASSWORD = "Password1!";

  private ConstraintViolationChecks() {}

  /** A username no other row has, so the unique index is only hit when a test asks for it. */
  static String uniqueUsername(final String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  static User user(final String username) {
    final var user = new User();
    user.setUsername(username);
    user.setHash(PasswordHasher.hash(PASSWORD));
    user.setRole(UserRole.USER);
    return user;
  }

  /** Runs a statement the database must reject and returns the exception it surfaces as. */
  static DataIntegrityViolationException violationOf(final Runnable statement) {
    final var violation =
        catchThrowableOfType(DataIntegrityViolationException.class, statement::run);

    assertThat(violation).as("the database must reject the statement").isNotNull();
    return violation;
  }

  static void expectError(
      final ErrorHandler errorHandler,
      final DataIntegrityViolationException violation,
      final HttpStatus expectedStatus,
      final String msgId) {

    final var answer =
        errorHandler.handleException(
            violation, new MockHttpServletRequest(), new MockHttpServletResponse());

    assertThat(answer).isNotNull();
    assertThat(answer.getStatusCode()).isEqualTo(expectedStatus);
    assertThat(answer.getBody()).isNotNull();
    assertThat(answer.getBody().getMsgId()).isEqualTo(msgId);
  }
}
