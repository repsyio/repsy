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

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

/**
 * Filters and orders the packages of a search. Every free term of the query has to match (a
 * substring of the package key, of the description or of a keyword), and the score of a match adds
 * up what each term hit: a whole-name match counts far more than a prefix, which counts more than a
 * substring of the name, a keyword or the description. {@code boost-exact:false} takes the
 * whole-name bonus away.
 */
@UtilityClass
@NullMarked
public class NpmSearchScorer {

  static final double BASE_SCORE = 1.0;
  static final double EXACT_NAME = 100_000;
  static final double NAME_PREFIX = 1_000;
  static final double NAME_CONTAINS = 100;
  static final double KEYWORD_EQUALS = 50;
  static final double DESCRIPTION_CONTAINS = 10;

  /** A package that matched, with the score its match earned. */
  public record Scored(NpmSearchDocument document, double score) {}

  /** The matching packages, best first, and then by name. */
  public static List<Scored> rank(
      final Collection<NpmSearchDocument> documents, final NpmSearchQuery query) {

    return documents.stream()
        .filter(document -> matches(document, query))
        .map(document -> new Scored(document, score(document, query)))
        .sorted(
            Comparator.comparingDouble(Scored::score)
                .reversed()
                .thenComparing(scored -> scored.document().key()))
        .toList();
  }

  /** The score as a share of the best one, which is {@code 1.0} for the best match. */
  public static double normalize(final double score, final double best) {
    return best <= 0 ? 1.0 : score / best;
  }

  /**
   * Whether the package matches the free terms, the scope and the keywords of the query. The
   * qualifiers that filter on data a {@link NpmSearchDocument} does not carry ({@code author:},
   * {@code maintainer:}, {@code is:}, {@code not:}) are applied where the documents are found, so
   * the documents here are the packages that passed them.
   */
  static boolean matches(final NpmSearchDocument document, final NpmSearchQuery query) {
    if (query.matchesNothing()) {
      return false;
    }

    if (query.scope() != null && !query.scope().equalsIgnoreCase(document.scope())) {
      return false;
    }

    final var keywords = lowercase(document.keywords());

    if (!query.keywords().isEmpty() && keywords.stream().noneMatch(query.keywords()::contains)) {
      return false;
    }

    return query.terms().stream().allMatch(term -> matchesTerm(document, keywords, term));
  }

  private static boolean matchesTerm(
      final NpmSearchDocument document, final List<String> keywords, final String term) {

    final var needle = stripAt(term);

    return document.key().contains(needle)
        || describes(document, needle)
        || keywords.stream().anyMatch(keyword -> keyword.contains(needle));
  }

  static double score(final NpmSearchDocument document, final NpmSearchQuery query) {
    final var keywords = lowercase(document.keywords());
    var score = BASE_SCORE;

    for (final var term : query.terms()) {
      score += termScore(document, query, keywords, term);
    }

    return score;
  }

  private static double termScore(
      final NpmSearchDocument document,
      final NpmSearchQuery query,
      final List<String> keywords,
      final String term) {

    final var needle = stripAt(term);
    final var name = document.name().toLowerCase(Locale.ROOT);
    final var key = document.key();

    return weight(query.boostExact() && (name.equals(needle) || key.equals(needle)), EXACT_NAME)
        + weight(name.startsWith(needle) || key.startsWith(needle), NAME_PREFIX)
        + weight(key.contains(needle), NAME_CONTAINS)
        + weight(keywords.contains(needle), KEYWORD_EQUALS)
        + weight(describes(document, needle), DESCRIPTION_CONTAINS);
  }

  private static double weight(final boolean hit, final double weight) {
    return hit ? weight : 0.0;
  }

  private static boolean describes(final NpmSearchDocument document, final String needle) {
    return document.description() != null
        && document.description().toLowerCase(Locale.ROOT).contains(needle);
  }

  private static List<String> lowercase(final List<String> values) {
    return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
  }

  private static String stripAt(final String term) {
    return term.startsWith("@") ? term.substring(1) : term;
  }
}
