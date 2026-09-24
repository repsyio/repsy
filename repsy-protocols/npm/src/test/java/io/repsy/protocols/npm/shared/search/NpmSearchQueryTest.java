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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

  @ParameterizedTest
  @CsvSource({"author:bob", "maintainer:bob", "not:deprecated", "is:unstable", "boost-exact:false"})
  @DisplayName("drops the qualifiers Repsy cannot filter on")
  void ignoredQualifiers(final String token) {
    assertThat(NpmSearchQuery.parse(token + " pad", null, null).terms()).containsExactly("pad");
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
        "abc, 20",
        "1.5, 20",
        "0, 1",
        "-7, 1",
        "1, 1",
        "20, 20",
        "250, 250",
        "251, 250",
        "999999, 250",
        "99999999999, 20",
        "' 30 ', 30"
      },
      nullValues = "null")
  @DisplayName("clamps size to 1..250 and falls back to 20 for junk")
  void size(final String size, final int expected) {
    assertThat(NpmSearchQuery.parse("x", size, null).size()).isEqualTo(expected);
  }

  @ParameterizedTest(name = "from={0} -> {1}")
  @CsvSource(
      value = {"null, 0", "'', 0", "abc, 0", "-5, 0", "0, 0", "40, 40", "99999999999, 0"},
      nullValues = "null")
  @DisplayName("clamps from to 0 or more and falls back to 0 for junk")
  void from(final String from, final int expected) {
    assertThat(NpmSearchQuery.parse("x", null, from).from()).isEqualTo(expected);
  }
}
