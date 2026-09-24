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

import io.repsy.protocols.npm.shared.search.NpmSearchScorer.Scored;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NpmSearchScorer")
class NpmSearchScorerTest {

  private static NpmSearchDocument doc(
      final String scope, final String name, final String description, final String... keywords) {
    return new NpmSearchDocument(
        scope,
        name,
        "1.0.0",
        description,
        List.of(keywords),
        Instant.parse("2026-09-24T10:00:00Z"),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static NpmSearchQuery query(final String text) {
    return NpmSearchQuery.parse(text, null, null);
  }

  private static List<String> names(final List<Scored> ranked) {
    return ranked.stream().map(scored -> scored.document().fullName()).toList();
  }

  @Test
  @DisplayName("an empty query matches every package at the same score, ordered by name")
  void emptyQuery() {
    final var ranked =
        NpmSearchScorer.rank(
            List.of(doc(null, "zeta", null), doc("acme", "alpha", null), doc(null, "beta", null)),
            query(""));

    assertThat(names(ranked)).containsExactly("@acme/alpha", "beta", "zeta");
    assertThat(ranked).extracting(Scored::score).containsOnly(NpmSearchScorer.BASE_SCORE);
  }

  @Test
  @DisplayName("every free term has to match the key, the description or a keyword")
  void everyTermMustMatch() {
    final var both = doc(null, "left-pad", "pads strings on the left", "string");
    final var one = doc(null, "left-shift", "shifts bits");
    final var none = doc(null, "other", "nothing");

    assertThat(names(NpmSearchScorer.rank(List.of(both, one, none), query("left pad"))))
        .containsExactly("left-pad");
    assertThat(names(NpmSearchScorer.rank(List.of(both, one, none), query("left"))))
        .containsExactlyInAnyOrder("left-pad", "left-shift");
  }

  @Test
  @DisplayName("matches on a scope, on a keyword and on the description, ignoring case")
  void matchesEachField() {
    final var docs =
        List.of(
            doc("Acme", "widget", null),
            doc(null, "tool", null, "Awesome"),
            doc(null, "gizmo", "An AWESOME gizmo"));

    assertThat(names(NpmSearchScorer.rank(docs, query("acme")))).containsExactly("@Acme/widget");
    assertThat(names(NpmSearchScorer.rank(docs, query("awesome"))))
        .containsExactlyInAnyOrder("tool", "gizmo");
    assertThat(names(NpmSearchScorer.rank(docs, query("@acme/wid"))))
        .containsExactly("@Acme/widget");
  }

  @Test
  @DisplayName("ranks a whole-name match above a prefix, a substring, a keyword and a description")
  void rankOrder() {
    final var exact = doc(null, "pad", null);
    final var prefix = doc(null, "padding", null);
    final var contains = doc(null, "left-pad", null);
    final var keyword = doc(null, "strings", null, "pad");
    final var description = doc(null, "utils", "a pad for things");

    final var ranked =
        NpmSearchScorer.rank(List.of(description, keyword, contains, prefix, exact), query("pad"));

    assertThat(names(ranked)).containsExactly("pad", "padding", "left-pad", "strings", "utils");
    assertThat(ranked.getFirst().score())
        .isEqualTo(
            NpmSearchScorer.BASE_SCORE
                + NpmSearchScorer.EXACT_NAME
                + NpmSearchScorer.NAME_PREFIX
                + NpmSearchScorer.NAME_CONTAINS);
  }

  @Test
  @DisplayName("scores a scoped package by its bare name and by scope/name")
  void scopedExactMatch() {
    final var ranked =
        NpmSearchScorer.rank(
            List.of(doc("acme", "pad", null), doc(null, "pad", null)), query("@acme/pad"));

    assertThat(names(ranked)).containsExactly("@acme/pad");
    assertThat(ranked.getFirst().score()).isGreaterThan(NpmSearchScorer.EXACT_NAME);
  }

  @Test
  @DisplayName("breaks ties by name")
  void tiesByName() {
    final var ranked =
        NpmSearchScorer.rank(
            List.of(doc(null, "b-pad", null), doc(null, "a-pad", null)), query("pad"));

    assertThat(names(ranked)).containsExactly("a-pad", "b-pad");
  }

  @Test
  @DisplayName("filters on scope and keywords")
  void qualifiers() {
    final var docs =
        List.of(
            doc("acme", "one", null, "x"),
            doc("acme", "two", null, "y"),
            doc("other", "three", null, "x"),
            doc(null, "four", null, "X"));

    assertThat(names(NpmSearchScorer.rank(docs, query("scope:acme"))))
        .containsExactly("@acme/one", "@acme/two");
    assertThat(names(NpmSearchScorer.rank(docs, query("keywords:x"))))
        .containsExactlyInAnyOrder("@acme/one", "@other/three", "four");
    assertThat(names(NpmSearchScorer.rank(docs, query("scope:acme keywords:x"))))
        .containsExactly("@acme/one");
    assertThat(names(NpmSearchScorer.rank(docs, query("scope:missing")))).isEmpty();
  }

  @Test
  @DisplayName("normalizes a score against the best one")
  void normalize() {
    assertThat(NpmSearchScorer.normalize(50, 100)).isEqualTo(0.5);
    assertThat(NpmSearchScorer.normalize(100, 100)).isEqualTo(1.0);
    assertThat(NpmSearchScorer.normalize(0, 0)).isEqualTo(1.0);
  }
}
