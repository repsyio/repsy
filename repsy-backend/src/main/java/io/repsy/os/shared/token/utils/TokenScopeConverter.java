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
package io.repsy.os.shared.token.utils;

import io.repsy.os.shared.token.dtos.TokenScope;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.EnumSet;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Stores a set of {@link TokenScope} as one comma separated string, in canonical order.
 *
 * <p>The order matters: a set built in two orders must give one column value, or the same scopes
 * would read differently from row to row and a comparison of the column would depend on the history
 * of the token. {@link EnumSet} iterates in the declaration order of {@link TokenScope}, which is
 * the canonical one, so writing a set is just joining it.
 *
 * <p>A value this version does not know (a scope a newer version wrote, then this one was deployed
 * again) is dropped when it is read and logged. A token then loses a scope instead of failing every
 * request it makes, and it can never gain one by being misread: an unknown scope grants nothing.
 */
@Slf4j
@Converter
public class TokenScopeConverter implements AttributeConverter<EnumSet<TokenScope>, String> {

  private static final String SEPARATOR = ",";

  @Override
  public String convertToDatabaseColumn(final @Nullable EnumSet<TokenScope> scopes) {
    if (scopes == null) {
      return "";
    }

    return scopes.stream().map(TokenScope::getValue).collect(Collectors.joining(SEPARATOR));
  }

  @Override
  public EnumSet<TokenScope> convertToEntityAttribute(final @Nullable String column) {
    final var scopes = EnumSet.noneOf(TokenScope.class);

    if (column == null || column.isBlank()) {
      return scopes;
    }

    for (final var value : column.split(SEPARATOR, -1)) {
      if (!value.isBlank()) {
        TokenScope.fromValue(value.strip())
            .ifPresentOrElse(
                scopes::add, () -> log.warn("Ignoring an unknown token scope in the database"));
      }
    }

    return scopes;
  }
}
