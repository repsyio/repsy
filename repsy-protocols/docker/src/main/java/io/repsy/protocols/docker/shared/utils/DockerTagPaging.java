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
package io.repsy.protocols.docker.shared.utils;

import io.repsy.protocols.docker.shared.tag.dtos.TagPage;
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
public final class DockerTagPaging {

  /**
   * Cuts one page out of the tags of an image.
   *
   * @param names the tag names of the image, in any order
   * @param limit {@code n}: the most tags to return, or {@code null} for all that follow {@code
   *     last}; 0 answers an empty page
   * @param last the tag to start after, or {@code null} to start at the first
   * @return the page, and whether tags remain after it
   */
  public static TagPage page(
      final Collection<String> names, final @Nullable Integer limit, final @Nullable String last) {

    final var following =
        names.stream().filter(name -> last == null || name.compareTo(last) > 0).sorted().toList();

    if (limit == null) {
      return new TagPage(following, false);
    }

    if (limit >= following.size()) {
      return new TagPage(following, false);
    }

    final List<String> tags = following.subList(0, limit);

    return new TagPage(List.copyOf(tags), limit > 0);
  }
}
