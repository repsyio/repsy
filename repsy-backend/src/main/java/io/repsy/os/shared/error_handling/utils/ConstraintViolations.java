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

import java.sql.SQLException;
import java.util.Locale;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Reads what the database reported from a {@link DataIntegrityViolationException}: the SQL state
 * and, for a unique violation, the constraint or index it names.
 *
 * <p>The SQL states are standard, so PostgreSQL and H2 report the same ones. The exception is
 * inspected through its most specific cause, which is the driver's {@link SQLException} for a
 * violation raised by the database itself. Any other cause carries no SQL state.
 */
@UtilityClass
@NullMarked
public final class ConstraintViolations {

  /** SQL state of a value longer than its column. */
  public static final String SQL_STATE_VALUE_TOO_LONG = "22001";

  /** SQL state of a unique constraint or unique index violation. */
  public static final String SQL_STATE_UNIQUE_VIOLATION = "23505";

  /** SQL state of a foreign key violation. */
  public static final String SQL_STATE_FOREIGN_KEY_VIOLATION = "23503";

  /**
   * Returns the SQL state of the violation.
   *
   * @param exception the exception to inspect, may be {@code null}
   * @return the SQL state, or {@code null} when there is no exception, its most specific cause is
   *     not a {@link SQLException}, or the driver reported no state
   */
  public static @Nullable String sqlState(
      final @Nullable DataIntegrityViolationException exception) {
    final var sqlException = sqlException(exception);

    return sqlException == null ? null : sqlException.getSQLState();
  }

  /**
   * Tells whether the violation carries the given SQL state.
   *
   * @param exception the exception to inspect, may be {@code null}
   * @param sqlState the SQL state to compare with
   * @return {@code true} when the state of the violation equals {@code sqlState}
   */
  public static boolean hasSqlState(
      final @Nullable DataIntegrityViolationException exception, final String sqlState) {

    return sqlState.equals(sqlState(exception));
  }

  /**
   * Tells whether the violation is a unique constraint or unique index violation.
   *
   * @param exception the exception to inspect, may be {@code null}
   * @return {@code true} for SQL state {@value #SQL_STATE_UNIQUE_VIOLATION}
   */
  public static boolean isUniqueViolation(
      final @Nullable DataIntegrityViolationException exception) {
    return hasSqlState(exception, SQL_STATE_UNIQUE_VIOLATION);
  }

  /**
   * Tells whether a value was longer than its column.
   *
   * @param exception the exception to inspect, may be {@code null}
   * @return {@code true} for SQL state {@value #SQL_STATE_VALUE_TOO_LONG}
   */
  public static boolean isValueTooLong(final @Nullable DataIntegrityViolationException exception) {
    return hasSqlState(exception, SQL_STATE_VALUE_TOO_LONG);
  }

  /**
   * Tells whether the violation is a foreign key violation.
   *
   * @param exception the exception to inspect, may be {@code null}
   * @return {@code true} for SQL state {@value #SQL_STATE_FOREIGN_KEY_VIOLATION}
   */
  public static boolean isForeignKeyViolation(
      final @Nullable DataIntegrityViolationException exception) {

    return hasSqlState(exception, SQL_STATE_FOREIGN_KEY_VIOLATION);
  }

  /**
   * Tells whether the violation is a unique violation of the named constraint or index.
   *
   * <p>The database names the constraint in the message of the driver's exception, so the name is
   * looked up there. The match ignores case. Every unique index of this project is created quoted
   * and in lower case, so a case-sensitive match would give the same answer on PostgreSQL and H2.
   *
   * @param exception the exception to inspect, may be {@code null}
   * @param constraintName the name of the constraint or index, as created
   * @return {@code true} for a unique violation whose message contains the name
   */
  public static boolean violatesConstraint(
      final @Nullable DataIntegrityViolationException exception, final String constraintName) {

    final var sqlException = sqlException(exception);

    if (sqlException == null || !SQL_STATE_UNIQUE_VIOLATION.equals(sqlException.getSQLState())) {
      return false;
    }

    final var message = sqlException.getMessage();

    return message != null
        && message.toLowerCase(Locale.ROOT).contains(constraintName.toLowerCase(Locale.ROOT));
  }

  private static @Nullable SQLException sqlException(
      final @Nullable DataIntegrityViolationException exception) {

    if (exception == null) {
      return null;
    }

    return exception.getMostSpecificCause() instanceof final SQLException sqlException
        ? sqlException
        : null;
  }
}
