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

import io.repsy.protocols.npm.shared.search.NpmSearchScorer.Scored;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The answer of {@code GET /-/v1/search}.
 *
 * @param objects The requested page of hits
 * @param total How many packages matched in all
 * @param time When the search ran, ISO-8601
 */
@NullMarked
public record NpmSearchResult(List<NpmSearchObject> objects, long total, String time) {

  private static final String UNSCOPED = "unscoped";
  private static final NpmSearchScore.Detail FIXED_DETAIL =
      new NpmSearchScore.Detail(1.0, 1.0, 1.0);

  /** The answer for no match. */
  public static NpmSearchResult empty(final Instant now) {
    return new NpmSearchResult(List.of(), 0, format(now));
  }

  /**
   * Builds the answer for one page of the ranked matches.
   *
   * @param page The hits of the requested page
   * @param total How many packages matched in all
   * @param best The best score of all matches, which the scores are shown against
   * @param maintainers The maintainers of the packages of the page by their full name
   * @param now The time of the search
   */
  public static NpmSearchResult of(
      final List<Scored> page,
      final long total,
      final double best,
      final Map<String, List<NpmSearchPerson>> maintainers,
      final Instant now) {

    final var objects =
        page.stream()
            .map(
                scored -> {
                  final var people =
                      maintainers.getOrDefault(scored.document().fullName(), List.of());
                  final var normalized = NpmSearchScorer.normalize(scored.score(), best);

                  return new NpmSearchObject(
                      toPackage(scored.document(), people),
                      new NpmSearchScore(normalized, FIXED_DETAIL),
                      scored.score());
                })
            .toList();

    return new NpmSearchResult(objects, total, format(now));
  }

  /** Formats an instant the way JavaScript's {@code Date} prints it, in milliseconds. */
  public static String format(final Instant instant) {
    return instant.truncatedTo(ChronoUnit.MILLIS).toString();
  }

  private static NpmSearchPackage toPackage(
      final NpmSearchDocument document, final List<NpmSearchPerson> maintainers) {

    return new NpmSearchPackage(
        document.name(),
        document.scope() == null ? UNSCOPED : document.scope(),
        document.version(),
        document.description(),
        document.keywords(),
        document.date() == null ? null : format(document.date()),
        new NpmSearchLinks(document.homepage(), document.repositoryUrl(), document.bugsUrl()),
        author(document),
        maintainers.isEmpty() ? null : maintainers.getFirst(),
        maintainers);
  }

  private static @Nullable NpmSearchAuthor author(final NpmSearchDocument document) {
    if (document.authorName() == null
        && document.authorEmail() == null
        && document.authorUrl() == null) {
      return null;
    }

    return new NpmSearchAuthor(document.authorName(), document.authorEmail(), document.authorUrl());
  }
}
