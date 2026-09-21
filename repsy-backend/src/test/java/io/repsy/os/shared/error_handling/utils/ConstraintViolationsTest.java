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
package io.repsy.os.shared.error_handling.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/** RPS-1081: the one place that reads the SQL state and the constraint name of a violation. */
@DisplayName("ConstraintViolations")
class ConstraintViolationsTest {

  private static final String CONSTRAINT = "ux_maven_artifact__repo_id_group_artifact";
  private static final String POSTGRES_MESSAGE =
      "ERROR: duplicate key value violates unique constraint \""
          + CONSTRAINT
          + "\"\n  Detail: Key (repo_id, group_name, artifact_name)=(a, b, c) already exists.";

  private static DataIntegrityViolationException violation(
      final String sqlState, final String message) {

    return new DataIntegrityViolationException(
        "could not execute statement", new SQLException(message, sqlState));
  }

  @Nested
  @DisplayName("constants")
  class Constants {

    @Test
    @DisplayName("are the standard SQL states")
    void standardStates() {
      assertThat(ConstraintViolations.SQL_STATE_VALUE_TOO_LONG).isEqualTo("22001");
      assertThat(ConstraintViolations.SQL_STATE_UNIQUE_VIOLATION).isEqualTo("23505");
      assertThat(ConstraintViolations.SQL_STATE_FOREIGN_KEY_VIOLATION).isEqualTo("23503");
    }
  }

  @Nested
  @DisplayName("sqlState")
  class SqlState {

    @Test
    @DisplayName("reads the state of the driver exception")
    void readsState() {
      assertThat(ConstraintViolations.sqlState(violation("23505", "x"))).isEqualTo("23505");
    }

    @Test
    @DisplayName("is null when the driver reported no state")
    void noStateReported() {
      assertThat(ConstraintViolations.sqlState(violation(null, "x"))).isNull();
    }

    @Test
    @DisplayName("is null for a null exception")
    void nullException() {
      assertThat(ConstraintViolations.sqlState(null)).isNull();
    }

    @Test
    @DisplayName("is null when there is no cause")
    void noCause() {
      assertThat(ConstraintViolations.sqlState(new DataIntegrityViolationException("x"))).isNull();
    }

    @Test
    @DisplayName("is null when the cause is not a SQLException")
    void causeIsNotSqlException() {
      final var exception =
          new DataIntegrityViolationException("x", new IllegalStateException("23505"));

      assertThat(ConstraintViolations.sqlState(exception)).isNull();
    }

    @Test
    @DisplayName("reads the state from the most specific cause, not from the wrapper")
    void readsMostSpecificCause() {
      final var root = new SQLException("root", "23505");
      final var wrapper = new SQLException("wrapper", "22001", root);
      final var exception = new DuplicateKeyException("x", wrapper);

      assertThat(ConstraintViolations.sqlState(exception)).isEqualTo("23505");
    }
  }

  @Nested
  @DisplayName("state predicates")
  class StatePredicates {

    @Test
    @DisplayName("hasSqlState compares the state exactly")
    void hasSqlState() {
      final var exception = violation("23505", "x");

      assertThat(ConstraintViolations.hasSqlState(exception, "23505")).isTrue();
      assertThat(ConstraintViolations.hasSqlState(exception, "23503")).isFalse();
      assertThat(ConstraintViolations.hasSqlState(null, "23505")).isFalse();
      assertThat(ConstraintViolations.hasSqlState(violation(null, "x"), "23505")).isFalse();
    }

    @Test
    @DisplayName("isUniqueViolation is true only for 23505")
    void isUniqueViolation() {
      assertThat(ConstraintViolations.isUniqueViolation(violation("23505", "x"))).isTrue();
      assertThat(ConstraintViolations.isUniqueViolation(violation("23503", "x"))).isFalse();
      assertThat(ConstraintViolations.isUniqueViolation(violation("22001", "x"))).isFalse();
      assertThat(ConstraintViolations.isUniqueViolation(null)).isFalse();
    }

    @Test
    @DisplayName("isValueTooLong is true only for 22001")
    void isValueTooLong() {
      assertThat(ConstraintViolations.isValueTooLong(violation("22001", "x"))).isTrue();
      assertThat(ConstraintViolations.isValueTooLong(violation("23505", "x"))).isFalse();
      assertThat(ConstraintViolations.isValueTooLong(null)).isFalse();
    }

    @Test
    @DisplayName("isForeignKeyViolation is true only for 23503")
    void isForeignKeyViolation() {
      assertThat(ConstraintViolations.isForeignKeyViolation(violation("23503", "x"))).isTrue();
      assertThat(ConstraintViolations.isForeignKeyViolation(violation("23505", "x"))).isFalse();
      assertThat(ConstraintViolations.isForeignKeyViolation(null)).isFalse();
    }

    @Test
    @DisplayName("none of them holds for a cause that is not a SQLException")
    void nonSqlCause() {
      final var exception = new DataIntegrityViolationException("x", new IllegalStateException());

      assertThat(ConstraintViolations.isUniqueViolation(exception)).isFalse();
      assertThat(ConstraintViolations.isValueTooLong(exception)).isFalse();
      assertThat(ConstraintViolations.isForeignKeyViolation(exception)).isFalse();
    }
  }

  @Nested
  @DisplayName("violatesConstraint")
  class ViolatesConstraint {

    @Test
    @DisplayName("matches a unique violation that names the constraint")
    void matches() {
      assertThat(
              ConstraintViolations.violatesConstraint(
                  violation("23505", POSTGRES_MESSAGE), CONSTRAINT))
          .isTrue();
    }

    @Test
    @DisplayName("does not match a unique violation of another constraint")
    void otherConstraint() {
      assertThat(
              ConstraintViolations.violatesConstraint(
                  violation("23505", POSTGRES_MESSAGE),
                  "ux_nuget_package_version__package_id_version"))
          .isFalse();
    }

    @Test
    @DisplayName("matches the constraint name whatever the case of the message")
    void ignoresCase() {
      final var message =
          "Unique index or primary key violation: \"" + CONSTRAINT.toUpperCase() + "\"";

      assertThat(ConstraintViolations.violatesConstraint(violation("23505", message), CONSTRAINT))
          .isTrue();
    }

    @ParameterizedTest(name = "does not match SQL state {0} even when the message names it")
    @ValueSource(strings = {"23503", "22001", "23514"})
    void otherState(final String sqlState) {
      assertThat(
              ConstraintViolations.violatesConstraint(
                  violation(sqlState, POSTGRES_MESSAGE), CONSTRAINT))
          .isFalse();
    }

    @Test
    @DisplayName("does not match when the driver reported no state")
    void noState() {
      assertThat(
              ConstraintViolations.violatesConstraint(
                  violation(null, POSTGRES_MESSAGE), CONSTRAINT))
          .isFalse();
    }

    @Test
    @DisplayName("does not match when the message is null")
    void nullMessage() {
      assertThat(ConstraintViolations.violatesConstraint(violation("23505", null), CONSTRAINT))
          .isFalse();
    }

    @Test
    @DisplayName("does not match a null exception")
    void nullException() {
      assertThat(ConstraintViolations.violatesConstraint(null, CONSTRAINT)).isFalse();
    }

    @Test
    @DisplayName("does not match a cause that is not a SQLException")
    void causeIsNotSqlException() {
      final var exception =
          new DataIntegrityViolationException("x", new IllegalStateException(POSTGRES_MESSAGE));

      assertThat(ConstraintViolations.violatesConstraint(exception, CONSTRAINT)).isFalse();
    }

    @Test
    @DisplayName("reads the message of the most specific cause")
    void mostSpecificCause() {
      final var root = new SQLException(POSTGRES_MESSAGE, "23505");
      final var wrapper = new SQLException("batch failed", "23505", root);
      final var exception = new DuplicateKeyException("x", wrapper);

      assertThat(ConstraintViolations.violatesConstraint(exception, CONSTRAINT)).isTrue();
    }
  }
}
