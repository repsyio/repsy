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
package io.repsy.core.ulid;

import com.github.f4b6a3.ulid.Ulid;
import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.EnhancedUserType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class UlidUserType implements EnhancedUserType<Ulid> {

  @Override
  public int getSqlType() {

    return Types.VARCHAR;
  }

  @Override
  public @NonNull Class<Ulid> returnedClass() {

    return Ulid.class;
  }

  @Override
  public boolean equals(final @Nullable Ulid x, final @Nullable Ulid y) {

    if (Objects.equals(x, y)) {
      return true;
    }

    if (x == null || y == null) {
      return false;
    }

    return x.equals(y);
  }

  @Override
  public int hashCode(final @Nullable Ulid ulid) {

    return ulid != null ? ulid.hashCode() : 0;
  }

  @Override
  public @Nullable Ulid nullSafeGet(
      final @NonNull ResultSet rs,
      final int position,
      final @NonNull SharedSessionContractImplementor session,
      final @Nullable Object owner)
      throws SQLException {

    final var value = rs.getString(position);

    return value != null ? Ulid.from(value) : null;
  }

  @Override
  public void nullSafeSet(
      final @NonNull PreparedStatement st,
      final @Nullable Ulid value,
      final int index,
      final @NonNull SharedSessionContractImplementor session)
      throws SQLException {

    st.setString(index, value != null ? value.toString() : null);
  }

  @Override
  public @Nullable Ulid deepCopy(final @Nullable Ulid ulid) {

    return ulid == null ? null : Ulid.from(ulid.toString());
  }

  @Override
  public boolean isMutable() {

    return false;
  }

  @Override
  public @Nullable Serializable disassemble(final @Nullable Ulid ulid) {

    return ulid == null ? null : ulid.toString();
  }

  @Override
  public @Nullable Ulid assemble(
      final @Nullable Serializable serializable, final @Nullable Object entityInstance) {

    if (serializable == null) {
      return null;
    }

    if (serializable instanceof final Ulid ulid) {
      return ulid;
    }

    return Ulid.from(serializable.toString());
  }

  @Override
  public @Nullable String toSqlLiteral(final @Nullable Ulid value) {

    return value == null ? null : value.toString();
  }

  @Override
  public @Nullable String toString(final @Nullable Ulid value) throws HibernateException {

    return value == null ? null : value.toString();
  }

  @Override
  public @Nullable Ulid fromStringValue(final @Nullable CharSequence sequence)
      throws HibernateException {

    return sequence == null ? null : Ulid.from(sequence.toString());
  }
}
