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
package io.repsy.protocols.npm.shared.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("NpmSearchQuery")
class NpmSearchQueryTest {

  @Test
  @DisplayName("splits free terms on whitespace and lowercases them")
  void freeTerms() {
    final var query = NpmSearchQuery.parse("  Left-Pad \t PAD\n", null, null);

    assertThat(query.terms()).containsExactly("left-pad", "pad");
    assertThat(query.scope()).isNull();
    assertThat(query.keywords()).isEmpty();
  }

  @Test
  @DisplayName("no text lists everything")
  void emptyText() {
    assertThat(NpmSearchQuery.parse(null, null, null).terms()).isEmpty();
    assertThat(NpmSearchQuery.parse("", null, null).terms()).isEmpty();
    assertThat(NpmSearchQuery.parse("   ", null, null).terms()).isEmpty();
  }

  @Test
  @DisplayName("reads the scope qualifier without its @, and the last one wins")
  void scopeQualifier() {
    assertThat(NpmSearchQuery.parse("scope:@Acme pad", null, null).scope()).isEqualTo("acme");
    assertThat(NpmSearchQuery.parse("scope:a scope:b", null, null).scope()).isEqualTo("b");
    assertThat(NpmSearchQuery.parse("scope:", null, null).scope()).isNull();
    assertThat(NpmSearchQuery.parse("scope:@", null, null).scope()).isNull();
    assertThat(NpmSearchQuery.parse("scope:acme", null, null).terms()).isEmpty();
  }

  @Test
  @DisplayName("reads the keywords qualifier as a comma separated set")
  void keywordsQualifier() {
    final var query = NpmSearchQuery.parse("keywords:Pad,,strings keywords:x", null, null);

    assertThat(query.keywords()).containsExactlyInAnyOrder("pad", "strings", "x");
    assertThat(query.terms()).isEmpty();
  }

  @Test
  @DisplayName("reads the author and maintainer qualifiers as sets, lowercased (RPS-1343)")
  void peopleQualifiers() {
    final var query =
        NpmSearchQuery.parse("author:Bob,Ann maintainer:Carol author:bob@x.io pad", null, null);

    assertThat(query.authors()).containsExactlyInAnyOrder("bob", "ann", "bob@x.io");
    assertThat(query.maintainers()).containsExactly("carol");
    assertThat(query.terms()).containsExactly("pad");
    assertThat(query.matchesNothing()).isFalse();
  }

  @Test
  @DisplayName("reads is: and not: for deprecated, unstable and insecure (RPS-1343)")
  void flagQualifiers() {
    final var is = NpmSearchQuery.parse("is:deprecated is:unstable is:insecure", null, null);
    final var not = NpmSearchQuery.parse("not:deprecated not:unstable not:insecure", null, null);
    final var none = NpmSearchQuery.parse("pad", null, null);

    assertThat(is.deprecated()).isTrue();
    assertThat(is.unstable()).isTrue();
    assertThat(is.insecure()).isTrue();
    assertThat(not.deprecated()).isFalse();
    assertThat(not.unstable()).isFalse();
    assertThat(not.insecure()).isFalse();
    assertThat(none.deprecated()).isNull();
    assertThat(none.unstable()).isNull();
    assertThat(none.insecure()).isNull();
    assertThat(is.terms()).isEmpty();
    assertThat(is.matchesNothing()).isFalse();
  }

  @Test
  @DisplayName("the last is: or not: of a flag wins")
  void lastFlagWins() {
    assertThat(NpmSearchQuery.parse("is:deprecated not:deprecated", null, null).deprecated())
        .isFalse();
    assertThat(NpmSearchQuery.parse("not:deprecated is:deprecated", null, null).deprecated())
        .isTrue();
  }

  @Test
  @DisplayName("boost-exact:false turns the whole-name bonus off, true and no qualifier keep it")
  void boostExact() {
    assertThat(NpmSearchQuery.parse("pad", null, null).boostExact()).isTrue();
    assertThat(NpmSearchQuery.parse("pad boost-exact:true", null, null).boostExact()).isTrue();
    assertThat(NpmSearchQuery.parse("pad boost-exact:false", null, null).boostExact()).isFalse();
    assertThat(NpmSearchQuery.parse("boost-exact:false", null, null).matchesNothing()).isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "author:",
    "maintainer:",
    "'author:,'",
    "is:shiny",
    "not:shiny",
    "is:",
    "not:",
    "boost-exact:maybe",
    "boost-exact:",
    "scope:",
    "scope:@",
    "keywords:",
    "'keywords:,'",
    "'is:shiny not:shiny author:'"
  })
  @DisplayName("a text of qualifiers Repsy cannot filter on matches nothing (RPS-1343)")
  void unsupportedQualifiersOnlyMatchNothing(final String text) {
    final var query = NpmSearchQuery.parse(text, null, null);

    assertThat(query.matchesNothing()).isTrue();
    assertThat(query.terms()).isEmpty();
  }

  @Test
  @DisplayName("an unsupported qualifier next to a free term or a real filter is only dropped")
  void unsupportedQualifierNextToARealFilter() {
    final var withTerm = NpmSearchQuery.parse("is:shiny pad", null, null);
    final var withFilter = NpmSearchQuery.parse("is:shiny scope:acme", null, null);

    assertThat(withTerm.matchesNothing()).isFalse();
    assertThat(withTerm.terms()).containsExactly("pad");
    assertThat(withFilter.matchesNothing()).isFalse();
    assertThat(withFilter.scope()).isEqualTo("acme");
  }

  @Test
  @DisplayName("withInsecure() changes only the insecure filter")
  void withInsecure() {
    final var query = NpmSearchQuery.parse("pad is:insecure not:deprecated size:x", "5", "3");

    final var changed = query.withInsecure(null);

    assertThat(changed.insecure()).isNull();
    assertThat(changed).usingRecursiveComparison().ignoringFields("insecure").isEqualTo(query);
  }

  @Test
  @DisplayName("keeps a token with another colon as a free term")
  void otherColonIsFreeText() {
    assertThat(NpmSearchQuery.parse("foo:bar", null, null).terms()).containsExactly("foo:bar");
  }

  @Test
  @DisplayName("keeps at most five free terms")
  void capsTheTerms() {
    assertThat(NpmSearchQuery.parse("a b c d e f g", null, null).terms())
        .containsExactly("a", "b", "c", "d", "e");
  }

  @ParameterizedTest(name = "size={0} -> {1}")
  @CsvSource(
      value = {
        "null, 20",
        "'', 20",
        "' ', 20",
        "0, 0",
        "1, 1",
        "20, 20",
        "250, 250",
        "251, 250",
        "999999, 250",
        "99999999999, 250",
        "99999999999999999999999, 250",
        "' 30 ', 30",
        "+5, 5"
      },
      nullValues = "null")
  @DisplayName("size is 0 to 250: 0 asks for no objects, more than 250 is cut down (RPS-1344)")
  void size(final String size, final int expected) {
    assertThat(NpmSearchQuery.parse("x", size, null).size()).isEqualTo(expected);
  }

  @ParameterizedTest(name = "from={0} -> {1}")
  @CsvSource(
      value = {
        "null, 0",
        "'', 0",
        "0, 0",
        "40, 40",
        "' 7 ', 7",
        "2147483647, 2147483647",
        "99999999999, 2147483647",
        "99999999999999999999999, 2147483647"
      },
      nullValues = "null")
  @DisplayName("a from beyond what an int holds skips every result instead of becoming 0")
  void from(final String from, final int expected) {
    assertThat(NpmSearchQuery.parse("x", null, from).from()).isEqualTo(expected);
  }

  @ParameterizedTest(name = "\"{0}\" is refused")
  @ValueSource(strings = {"abc", "1.5", "-1", "-7", "-99999999999", "1e3", "0x10", "12a", "١٢"})
  @DisplayName("a size or from that is no whole number of 0 or more is a 400 (RPS-1344)")
  void junkIsRefused(final String value) {
    assertThatThrownBy(() -> NpmSearchQuery.parse("x", value, null))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("invalidSearchParameter");
    assertThatThrownBy(() -> NpmSearchQuery.parse("x", null, value))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("invalidSearchParameter");
  }
}
