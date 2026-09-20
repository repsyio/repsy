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

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Feeds {@link ErrorHandler} the exceptions a real PostgreSQL constraint violation turns into after
 * Hibernate and Spring have translated it, so the SQL state it reads is the one the driver reports
 * and not one a unit test made up. The {@code users} table is the fixture: a {@code varchar(25)}
 * username, a unique username index and a check on the role.
 */
class ErrorHandlerConstraintViolationIT extends AbstractIntegrationTest {

  private static final int USERNAME_LENGTH = 25;

  @Autowired private ErrorHandler errorHandler;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("answers 400 validationError for a username longer than its column")
  void valueTooLongForColumn() {
    final var tooLong = "u".repeat(USERNAME_LENGTH + 1);

    final var violation = this.violationOf(() -> this.userRepository.saveAndFlush(user(tooLong)));

    this.expectError(violation, HttpStatus.BAD_REQUEST, "validationError");
  }

  @Test
  @DisplayName("answers 409 itemAlreadyExists for a username that is already taken")
  void duplicateUsername() {
    final var taken = uniqueUsername("dup");
    this.userRepository.saveAndFlush(user(taken));

    final var violation = this.violationOf(() -> this.userRepository.saveAndFlush(user(taken)));

    this.expectError(violation, HttpStatus.CONFLICT, "itemAlreadyExists");
  }

  @Test
  @DisplayName("keeps 500 errorOccurred for a check violation, which the client did not cause")
  void checkViolation() {
    final var stored = this.userRepository.saveAndFlush(user(uniqueUsername("chk")));

    final var violation =
        this.violationOf(
            () ->
                this.jdbcTemplate.update(
                    "update users set role = 'NOT_A_ROLE' where id = ?", stored.getId()));

    this.expectError(violation, HttpStatus.INTERNAL_SERVER_ERROR, "errorOccurred");
  }

  private static User user(final String username) {
    final var user = new User();
    user.setUsername(username);
    user.setHash(PasswordHasher.hash(VALID_PASSWORD));
    user.setRole(UserRole.USER);
    return user;
  }

  /** Runs a statement the database must reject and returns the exception it surfaces as. */
  private DataIntegrityViolationException violationOf(final Runnable statement) {
    final var violation =
        catchThrowableOfType(DataIntegrityViolationException.class, statement::run);

    assertThat(violation).as("the database must reject the statement").isNotNull();
    return violation;
  }

  private void expectError(
      final DataIntegrityViolationException violation,
      final HttpStatus expectedStatus,
      final String msgId) {

    final var answer =
        this.errorHandler.handleException(
            violation, new MockHttpServletRequest(), new MockHttpServletResponse());

    assertThat(answer).isNotNull();
    assertThat(answer.getStatusCode()).isEqualTo(expectedStatus);
    assertThat(answer.getBody()).isNotNull();
    assertThat(answer.getBody().getMsgId()).isEqualTo(msgId);
  }
}
