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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * What {@code npm search} asks for: the {@code text} of {@code GET /-/v1/search} split into free
 * terms and qualifiers, plus the page it wants.
 *
 * <p>The qualifiers are the ones of the npm registry: {@code scope:}, {@code keywords:}, {@code
 * author:}, {@code maintainer:}, {@code is:}/{@code not:} ({@code deprecated}, {@code unstable},
 * {@code insecure}) and {@code boost-exact:}. A qualifier with an empty or unknown value ({@code
 * is:shiny}, {@code author:}) filters on nothing and is dropped. A text made only of such dropped
 * qualifiers therefore has nothing to look for, and {@link #matchesNothing()} says it matches no
 * package, where an empty text lists everything.
 *
 * @param terms The lowercase free terms; a package must match every one of them
 * @param scope The scope a package must have, without a leading {@code @}, or {@code null}
 * @param keywords The lowercase keywords of which a package must have at least one; may be empty
 * @param authors The lowercase names or emails of which the author of a package must be one; may be
 *     empty
 * @param maintainers The lowercase names or emails of which a maintainer of a package must be one;
 *     may be empty
 * @param deprecated {@code true} for {@code is:deprecated}, {@code false} for {@code
 *     not:deprecated}, {@code null} for no filter
 * @param unstable {@code true} for {@code is:unstable} (a version below 1.0.0), {@code false} for
 *     {@code not:unstable}, {@code null} for no filter
 * @param insecure {@code true} for {@code is:insecure} (the latest version has a finding of the
 *     vulnerability scan), {@code false} for {@code not:insecure}, {@code null} for no filter
 * @param boostExact Whether a package named as a free term ranks far above the others, which {@code
 *     boost-exact:false} turns off
 * @param matchesNothing Whether the text has qualifiers only and none of them is one Repsy filters
 *     on
 * @param size How many objects to return, from {@value #MIN_SIZE} to {@value #MAX_SIZE}
 * @param from How many objects to skip
 */
@NullMarked
public record NpmSearchQuery(
    List<String> terms,
    @Nullable String scope,
    Set<String> keywords,
    Set<String> authors,
    Set<String> maintainers,
    @Nullable Boolean deprecated,
    @Nullable Boolean unstable,
    @Nullable Boolean insecure,
    boolean boostExact,
    boolean matchesNothing,
    int size,
    int from) {

  public static final int DEFAULT_SIZE = 20;
  public static final int MIN_SIZE = 0;
  public static final int MAX_SIZE = 250;
  public static final int MAX_TERMS = 5;

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final Pattern WHOLE_NUMBER = Pattern.compile("[+-]?\\d+");
  private static final String SCOPE_QUALIFIER = "scope:";
  private static final String KEYWORDS_QUALIFIER = "keywords:";
  private static final String AUTHOR_QUALIFIER = "author:";
  private static final String MAINTAINER_QUALIFIER = "maintainer:";
  private static final String IS_QUALIFIER = "is:";
  private static final String NOT_QUALIFIER = "not:";
  private static final String BOOST_EXACT_QUALIFIER = "boost-exact:";
  private static final String DEPRECATED = "deprecated";
  private static final String UNSTABLE = "unstable";
  private static final String INSECURE = "insecure";

  /**
   * Parses the query parameters of a search request. A missing or blank {@code size} or {@code
   * from} takes its default. A {@code size} above {@value #MAX_SIZE} is cut down to it, and a
   * {@code from} above what an {@code int} holds skips every result, as it does for a page beyond
   * the last one. A value that is not a whole number, or is negative, is refused, as the npm
   * registry does.
   *
   * @param text The {@code text} parameter
   * @param size The {@code size} parameter
   * @param from The {@code from} parameter
   * @throws BadRequestException If {@code size} or {@code from} is no whole number of 0 or more
   */
  public static NpmSearchQuery parse(
      final @Nullable String text, final @Nullable String size, final @Nullable String from) {

    final var parts = new Parts();
    final var tokens = WHITESPACE.split(lowercase(text), -1);

    for (final var token : tokens) {
      parts.accept(token);
    }

    return new NpmSearchQuery(
        List.copyOf(parts.terms),
        parts.scope,
        Set.copyOf(parts.keywords),
        Set.copyOf(parts.authors),
        Set.copyOf(parts.maintainers),
        parts.deprecated,
        parts.unstable,
        parts.insecure,
        parts.boostExact,
        parts.seenToken && !parts.filtersOnSomething,
        Math.min(parseNumber(size, DEFAULT_SIZE), MAX_SIZE),
        parseNumber(from, 0));
  }

  /** The same query without the {@code insecure} filter, for a repository that is not scanned. */
  public NpmSearchQuery withInsecure(final @Nullable Boolean insecure) {
    return new NpmSearchQuery(
        this.terms,
        this.scope,
        this.keywords,
        this.authors,
        this.maintainers,
        this.deprecated,
        this.unstable,
        insecure,
        this.boostExact,
        this.matchesNothing,
        this.size,
        this.from);
  }

  private static String lowercase(final @Nullable String text) {
    return text == null ? "" : text.toLowerCase(Locale.ROOT);
  }

  /** What the tokens of the text add up to. */
  private static final class Parts {

    private final List<String> terms = new ArrayList<>();
    private final Set<String> keywords = new LinkedHashSet<>();
    private final Set<String> authors = new LinkedHashSet<>();
    private final Set<String> maintainers = new LinkedHashSet<>();
    private @Nullable String scope;
    private @Nullable Boolean deprecated;
    private @Nullable Boolean unstable;
    private @Nullable Boolean insecure;
    private boolean boostExact = true;
    private boolean seenToken;
    private boolean filtersOnSomething;

    void accept(final String token) {
      if (token.isEmpty()) {
        return;
      }

      this.seenToken = true;

      if (token.startsWith(SCOPE_QUALIFIER)) {
        this.acceptScope(token.substring(SCOPE_QUALIFIER.length()));
      } else if (token.startsWith(KEYWORDS_QUALIFIER)) {
        this.acceptAll(this.keywords, token.substring(KEYWORDS_QUALIFIER.length()));
      } else if (token.startsWith(AUTHOR_QUALIFIER)) {
        this.acceptAll(this.authors, token.substring(AUTHOR_QUALIFIER.length()));
      } else if (token.startsWith(MAINTAINER_QUALIFIER)) {
        this.acceptAll(this.maintainers, token.substring(MAINTAINER_QUALIFIER.length()));
      } else if (token.startsWith(IS_QUALIFIER)) {
        this.acceptFlag(token.substring(IS_QUALIFIER.length()), true);
      } else if (token.startsWith(NOT_QUALIFIER)) {
        this.acceptFlag(token.substring(NOT_QUALIFIER.length()), false);
      } else if (token.startsWith(BOOST_EXACT_QUALIFIER)) {
        this.acceptBoostExact(token.substring(BOOST_EXACT_QUALIFIER.length()));
      } else {
        this.acceptTerm(token);
      }
    }

    private void acceptTerm(final String token) {
      this.filtersOnSomething = true;

      if (this.terms.size() < MAX_TERMS) {
        this.terms.add(token);
      }
    }

    private void acceptScope(final String value) {
      final var scopeName = stripAt(value);

      if (!scopeName.isEmpty()) {
        this.scope = scopeName;
        this.filtersOnSomething = true;
      }
    }

    private void acceptAll(final Set<String> values, final String value) {
      for (final var single : value.split(",", -1)) {
        if (!single.isEmpty()) {
          values.add(single);
          this.filtersOnSomething = true;
        }
      }
    }

    private void acceptFlag(final String name, final boolean value) {
      switch (name) {
        case DEPRECATED -> this.deprecated = value;
        case UNSTABLE -> this.unstable = value;
        case INSECURE -> this.insecure = value;
        default -> {
          return;
        }
      }

      this.filtersOnSomething = true;
    }

    /**
     * {@code boost-exact:false} turns the ranking bonus of a whole-name match off, {@code true} is
     * what a search does anyway. It filters nothing, but it is a qualifier Repsy understands, so a
     * text made of it alone is not "nothing to look for".
     */
    private void acceptBoostExact(final String value) {
      switch (value) {
        case "false" -> this.boostExact = false;
        case "true" -> this.boostExact = true;
        default -> {
          return;
        }
      }

      this.filtersOnSomething = true;
    }
  }

  private static String stripAt(final String value) {
    return value.startsWith("@") ? value.substring(1) : value;
  }

  private static int parseNumber(final @Nullable String value, final int fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }

    final var number = value.trim();

    if (!WHOLE_NUMBER.matcher(number).matches()) {
      throw new BadRequestException("invalidSearchParameter");
    }

    try {
      return checkNotNegative(Integer.parseInt(number));
    } catch (final NumberFormatException _) {
      // A whole number that does not fit an int: a huge from skips every result, and a huge
      // negative one is as invalid as a small one.
      if (number.startsWith("-")) {
        throw new BadRequestException("invalidSearchParameter");
      }

      return Integer.MAX_VALUE;
    }
  }

  private static int checkNotNegative(final int value) {
    if (value < 0) {
      throw new BadRequestException("invalidSearchParameter");
    }

    return value;
  }
}
