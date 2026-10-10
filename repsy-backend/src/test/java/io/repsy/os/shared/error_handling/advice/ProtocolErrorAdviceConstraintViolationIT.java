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
package io.repsy.os.shared.error_handling.advice;

import static io.repsy.os.shared.error_handling.advice.ConstraintViolationChecks.USERNAME_LENGTH;
import static io.repsy.os.shared.error_handling.advice.ConstraintViolationChecks.user;
import static io.repsy.os.shared.error_handling.advice.ConstraintViolationChecks.violationOf;

import io.repsy.os.AbstractIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Feeds {@link ProtocolErrorAdvice} the exceptions a real PostgreSQL constraint violation turns
 * into after Hibernate and Spring have translated it, so the SQL state it reads is the one the
 * driver reports and not one a unit test made up. The {@code users} table is the fixture: a {@code
 * varchar(25)} username, a unique username index and a check on the role.
 */
class ProtocolErrorAdviceConstraintViolationIT extends AbstractIT {

  @Autowired private ProtocolErrorAdvice advice;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("answers 400 validationError for a username longer than its column")
  void valueTooLongForColumn() {
    final var tooLong = "u".repeat(USERNAME_LENGTH + 1);

    final var violation = violationOf(() -> this.userRepository.saveAndFlush(user(tooLong)));

    ConstraintViolationChecks.expectError(
        this.advice, violation, HttpStatus.BAD_REQUEST, "validationError");
  }

  @Test
  @DisplayName("answers 409 itemAlreadyExists for a username that is already taken")
  void duplicateUsername() {
    final var taken = uniqueUsername("dup");
    this.userRepository.saveAndFlush(user(taken));

    final var violation = violationOf(() -> this.userRepository.saveAndFlush(user(taken)));

    ConstraintViolationChecks.expectError(
        this.advice, violation, HttpStatus.CONFLICT, "itemAlreadyExists");
  }

  @Test
  @DisplayName("keeps 500 errorOccurred for a check violation, which the client did not cause")
  void checkViolation() {
    final var stored = this.userRepository.saveAndFlush(user(uniqueUsername("chk")));

    final var violation =
        violationOf(
            () ->
                this.jdbcTemplate.update(
                    "update users set role = 'NOT_A_ROLE' where id = ?", stored.getId()));

    ConstraintViolationChecks.expectError(
        this.advice, violation, HttpStatus.INTERNAL_SERVER_ERROR, "errorOccurred");
  }
}
