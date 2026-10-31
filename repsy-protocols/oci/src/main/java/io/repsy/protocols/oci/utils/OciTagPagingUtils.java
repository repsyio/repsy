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
package io.repsy.protocols.oci.utils;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.protocols.oci.dtos.OciTagPage;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The pagination of a tag listing as the distribution spec describes it: the tags are listed in
 * lexical order, {@code last} is the tag the page starts after (exclusive) and {@code n} the
 * largest number of tags the page holds.
 *
 * <p>The order is the byte order of the names, which for a tag (ASCII by grammar) is the order of a
 * {@code C} collation. It is not the database's: a PostgreSQL {@code en_US} collation orders {@code
 * _} and {@code -} differently from H2, and a {@code last} that is compared under another order
 * than the list is sorted under would skip or repeat tags between two pages.
 */
@UtilityClass
public final class OciTagPagingUtils {

  /**
   * Cuts one page out of the tags of an image.
   *
   * @param names the tag names of the image, in any order
   * @param limit {@code n}: the most tags to return, or {@code null} for all that follow {@code
   *     last}; 0 answers an empty page
   * @param last the tag to start after, or {@code null} to start at the first
   * @return the page, and whether tags remain after it
   */
  public static OciTagPage page(
      final Collection<String> names, final @Nullable Integer limit, final @Nullable String last) {

    final var following =
        names.stream().filter(name -> last == null || name.compareTo(last) > 0).sorted().toList();

    if (limit == null) {
      return new OciTagPage(following, false);
    }

    if (limit >= following.size()) {
      return new OciTagPage(following, false);
    }

    final List<String> tags = following.subList(0, limit);

    return new OciTagPage(List.copyOf(tags), limit > 0);
  }

  /** The query parameter that limits a page: the largest number of tags it holds. */
  public static final String LIMIT_PARAMETER = "n";

  /** The query parameter naming the tag a page starts after. */
  public static final String LAST_PARAMETER = "last";

  /**
   * Reads {@code n}: a non-negative integer, or absent for every tag. Anything else names no number
   * of results, which the distribution reference registry answers with 400 {@code
   * PAGINATION_NUMBER_INVALID}.
   *
   * @param value the raw {@code n} parameter, or {@code null} when absent
   * @return the limit, or {@code null} for no limit
   * @throws BadRequestException when the value is not a non-negative integer
   */
  public static @Nullable Integer parseLimit(final @Nullable String value) {

    if (value == null) {
      return null;
    }

    try {
      final var limit = Integer.parseInt(value);

      if (limit < 0) {
        throw new BadRequestException(ProtocolErrorCodes.PAGINATION_NUMBER_INVALID);
      }

      return limit;
    } catch (final NumberFormatException _) {
      throw new BadRequestException(ProtocolErrorCodes.PAGINATION_NUMBER_INVALID);
    }
  }

  /**
   * The {@code Link} header value of the next page: a relative reference, as the distribution spec
   * allows, which the client resolves against the URL it called, so a proxy in front of the
   * registry cannot make it point at an address the client cannot reach.
   */
  public static String nextLink(final String requestUri, final int limit, final String last) {

    return "<%s?%s=%d&%s=%s>; rel=\"next\""
        .formatted(
            requestUri,
            LIMIT_PARAMETER,
            limit,
            LAST_PARAMETER,
            URLEncoder.encode(last, StandardCharsets.UTF_8));
  }
}
