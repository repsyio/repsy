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
 * terms and the {@code scope:} and {@code keywords:} qualifiers, plus the page it wants. The other
 * qualifiers of the npm registry ({@code author:}, {@code maintainer:}, {@code is:}, {@code not:},
 * {@code boost-exact:}) are recognized and dropped, because Repsy does not keep what they filter
 * on.
 *
 * @param terms The lowercase free terms; a package must match every one of them
 * @param scope The scope a package must have, without a leading {@code @}, or {@code null}
 * @param keywords The lowercase keywords of which a package must have at least one; may be empty
 * @param size How many objects to return, from {@value #MIN_SIZE} to {@value #MAX_SIZE}
 * @param from How many objects to skip
 */
@NullMarked
public record NpmSearchQuery(
    List<String> terms, @Nullable String scope, Set<String> keywords, int size, int from) {

  public static final int DEFAULT_SIZE = 20;
  public static final int MIN_SIZE = 1;
  public static final int MAX_SIZE = 250;
  public static final int MAX_TERMS = 5;

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final Pattern IGNORED_QUALIFIER =
      Pattern.compile("^(?:author|maintainer|not|is|boost-exact):.*$");
  private static final String SCOPE_QUALIFIER = "scope:";
  private static final String KEYWORDS_QUALIFIER = "keywords:";

  /**
   * Parses the query parameters of a search request. A value that is missing or does not parse
   * falls back to its default instead of failing the request.
   *
   * @param text The {@code text} parameter
   * @param size The {@code size} parameter
   * @param from The {@code from} parameter
   */
  public static NpmSearchQuery parse(
      final @Nullable String text, final @Nullable String size, final @Nullable String from) {

    final var parts = new Parts();

    for (final var token : WHITESPACE.split(lowercase(text), -1)) {
      parts.accept(token);
    }

    return new NpmSearchQuery(
        List.copyOf(parts.terms),
        parts.scope,
        Set.copyOf(parts.keywords),
        clamp(parseInt(size, DEFAULT_SIZE), MIN_SIZE, MAX_SIZE),
        Math.max(0, parseInt(from, 0)));
  }

  private static String lowercase(final @Nullable String text) {
    return text == null ? "" : text.toLowerCase(Locale.ROOT);
  }

  /** What the tokens of the text add up to. */
  private static final class Parts {

    private final List<String> terms = new ArrayList<>();
    private final Set<String> keywords = new LinkedHashSet<>();
    private @Nullable String scope;

    void accept(final String token) {
      if (token.isEmpty() || IGNORED_QUALIFIER.matcher(token).matches()) {
        return;
      }

      if (token.startsWith(SCOPE_QUALIFIER)) {
        this.acceptScope(token.substring(SCOPE_QUALIFIER.length()));
      } else if (token.startsWith(KEYWORDS_QUALIFIER)) {
        this.acceptKeywords(token.substring(KEYWORDS_QUALIFIER.length()));
      } else if (this.terms.size() < MAX_TERMS) {
        this.terms.add(token);
      }
    }

    private void acceptScope(final String value) {
      final var scopeName = stripAt(value);

      if (!scopeName.isEmpty()) {
        this.scope = scopeName;
      }
    }

    private void acceptKeywords(final String value) {
      for (final var keyword : value.split(",", -1)) {
        if (!keyword.isEmpty()) {
          this.keywords.add(keyword);
        }
      }
    }
  }

  private static String stripAt(final String value) {
    return value.startsWith("@") ? value.substring(1) : value;
  }

  private static int parseInt(final @Nullable String value, final int fallback) {
    if (value == null) {
      return fallback;
    }

    try {
      return Integer.parseInt(value.trim());
    } catch (final NumberFormatException _) {
      return fallback;
    }
  }

  private static int clamp(final int value, final int min, final int max) {
    return Math.max(min, Math.min(max, value));
  }
}
