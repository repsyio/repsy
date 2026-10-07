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

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.shared.token.dtos.TokenScope;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TokenScopeConverter")
class TokenScopeConverterTest {

  private static final String ALL = "profile:read,repo:manage,repo:read,repo:write,scan:read";

  private final TokenScopeConverter converter = new TokenScopeConverter();

  @Test
  @DisplayName("writes the scopes in canonical order, whichever order they were added in")
  void writesCanonicalOrder() {
    final var forward = EnumSet.noneOf(TokenScope.class);
    final var backward = EnumSet.noneOf(TokenScope.class);

    for (final var scope : TokenScope.values()) {
      forward.add(scope);
    }
    for (int i = TokenScope.values().length - 1; i >= 0; i--) {
      backward.add(TokenScope.values()[i]);
    }

    assertThat(this.converter.convertToDatabaseColumn(forward)).isEqualTo(ALL);
    assertThat(this.converter.convertToDatabaseColumn(backward)).isEqualTo(ALL);
  }

  @Test
  @DisplayName("the same set always gives the same column value, for every subset")
  void everySubsetHasOneColumnValue() {
    final var all = TokenScope.values();

    for (int mask = 0; mask < (1 << all.length); mask++) {
      final var ascending = new ArrayList<TokenScope>();
      final var descending = new ArrayList<TokenScope>();

      for (int i = 0; i < all.length; i++) {
        if ((mask & (1 << i)) != 0) {
          ascending.add(all[i]);
          descending.add(0, all[i]);
        }
      }

      final var fromAscending = this.set(ascending);
      final var fromDescending = this.set(descending);

      assertThat(this.converter.convertToDatabaseColumn(fromAscending))
          .as("subset %s", ascending)
          .isEqualTo(this.converter.convertToDatabaseColumn(fromDescending));
      assertThat(
              this.converter.convertToEntityAttribute(
                  this.converter.convertToDatabaseColumn(fromAscending)))
          .as("round trip of %s", ascending)
          .isEqualTo(fromAscending);
    }
  }

  @Test
  @DisplayName("all the scopes fit the column, which is 255 characters wide")
  void allScopesFitTheColumn() {
    assertThat(this.converter.convertToDatabaseColumn(EnumSet.allOf(TokenScope.class)).length())
        .isLessThanOrEqualTo(255);
  }

  @Test
  @DisplayName(
      "writes an empty or absent set as the empty string, never null (the column is NOT NULL)")
  void writesEmptyAsEmptyString() {
    assertThat(this.converter.convertToDatabaseColumn(EnumSet.noneOf(TokenScope.class))).isEmpty();
    assertThat(this.converter.convertToDatabaseColumn(null)).isEmpty();
  }

  @Test
  @DisplayName("reads a value in any order, with spaces, and collapses repeats")
  void readsLeniently() {
    assertThat(
            this.converter.convertToEntityAttribute(
                " scan:read , repo:read,profile:read,repo:read"))
        .containsExactly(TokenScope.PROFILE_READ, TokenScope.REPO_READ, TokenScope.SCAN_READ);
  }

  @Test
  @DisplayName("reads an empty, blank or null column as no scopes")
  void readsEmptyAsNoScopes() {
    assertThat(this.converter.convertToEntityAttribute("")).isEmpty();
    assertThat(this.converter.convertToEntityAttribute("   ")).isEmpty();
    assertThat(this.converter.convertToEntityAttribute(null)).isEmpty();
  }

  @Test
  @DisplayName("drops a scope it does not know, so a token loses a scope instead of gaining one")
  void dropsUnknownScopes() {
    assertThat(this.converter.convertToEntityAttribute("repo:read,repo:publish,,x"))
        .containsExactly(TokenScope.REPO_READ);
    assertThat(this.converter.convertToEntityAttribute("REPO:READ,Repo:Manage")).isEmpty();
    assertThat(this.converter.convertToEntityAttribute("repo:publish")).isEmpty();
  }

  private EnumSet<TokenScope> set(final List<TokenScope> scopes) {
    final var set = EnumSet.noneOf(TokenScope.class);

    set.addAll(scopes);

    return set;
  }
}
