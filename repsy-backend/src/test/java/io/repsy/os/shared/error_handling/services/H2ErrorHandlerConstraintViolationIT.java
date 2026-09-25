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

import static io.repsy.os.shared.error_handling.services.ConstraintViolationChecks.USERNAME_LENGTH;
import static io.repsy.os.shared.error_handling.services.ConstraintViolationChecks.expectError;
import static io.repsy.os.shared.error_handling.services.ConstraintViolationChecks.uniqueUsername;
import static io.repsy.os.shared.error_handling.services.ConstraintViolationChecks.user;
import static io.repsy.os.shared.error_handling.services.ConstraintViolationChecks.violationOf;

import io.repsy.os.H2IntegrationTest;
import io.repsy.os.shared.user.repositories.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The H2 counterpart of {@link ErrorHandlerConstraintViolationIT} (RPS-1079). {@link ErrorHandler}
 * maps a constraint violation by the SQL state of the driver exception at the bottom of the cause
 * chain, and H2 is a supported database, so this feeds it the exceptions a real H2 violation turns
 * into after Hibernate and Spring have translated them. If H2 reported another state, or hid it
 * behind a wrapper, the 4xx answers would silently be 500s on an H2 install. The test rolls back
 * with the rest of {@link H2IntegrationTest}, so it commits no rows.
 */
@DisplayName("ErrorHandler maps constraint violations on the embedded H2 database (RPS-1079)")
class H2ErrorHandlerConstraintViolationIT extends H2IntegrationTest {

  @Autowired private ErrorHandler errorHandler;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("answers 400 validationError for a username longer than its column")
  void valueTooLongForColumn() {
    final var tooLong = "u".repeat(USERNAME_LENGTH + 1);

    final var violation = violationOf(() -> this.userRepository.saveAndFlush(user(tooLong)));

    expectError(this.errorHandler, violation, HttpStatus.BAD_REQUEST, "validationError");
  }

  @Test
  @DisplayName("answers 409 itemAlreadyExists for a username that is already taken")
  void duplicateUsername() {
    final var taken = uniqueUsername("dup");
    this.userRepository.saveAndFlush(user(taken));

    final var violation = violationOf(() -> this.userRepository.saveAndFlush(user(taken)));

    expectError(this.errorHandler, violation, HttpStatus.CONFLICT, "itemAlreadyExists");
  }

  @Test
  @DisplayName("keeps 500 errorOccurred for a check violation, which the client did not cause")
  void checkViolation() {
    final var stored = this.userRepository.saveAndFlush(user(uniqueUsername("chk")));

    final var violation =
        violationOf(
            () ->
                this.jdbcTemplate.update(
                    "update \"public\".\"users\" set \"role\" = 'NOT_A_ROLE' where \"id\" = ?",
                    stored.getId()));

    expectError(this.errorHandler, violation, HttpStatus.INTERNAL_SERVER_ERROR, "errorOccurred");
  }

  @Test
  @DisplayName("keeps 500 errorOccurred for a not-null violation, which the client did not cause")
  void notNullViolation() {
    final var stored = this.userRepository.saveAndFlush(user(uniqueUsername("nn")));

    final var violation =
        violationOf(
            () ->
                this.jdbcTemplate.update(
                    "update \"public\".\"users\" set \"username\" = null where \"id\" = ?",
                    stored.getId()));

    expectError(this.errorHandler, violation, HttpStatus.INTERNAL_SERVER_ERROR, "errorOccurred");
  }
}
