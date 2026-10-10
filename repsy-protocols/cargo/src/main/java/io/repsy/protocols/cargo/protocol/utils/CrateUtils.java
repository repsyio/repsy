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
package io.repsy.protocols.cargo.protocol.utils;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateVersionListItem;
import io.repsy.protocols.cargo.shared.crate.services.SemverComparator;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.util.Comparator;
import java.util.Locale;
import lombok.experimental.UtilityClass;
import org.springframework.data.domain.Pageable;
import org.springframework.data.util.Pair;

/**
 * Crate names and request paths, the order of a crate's versions and the column limits of its
 * metadata.
 */
@UtilityClass
public class CrateUtils {

  private static final int TWO = 2;
  private static final int THREE = 3;

  // The limits of the columns the published metadata is stored in (RPS-1072). Every one is a
  // varchar of exactly this length in PostgreSQL and H2, except where its Javadoc says the two
  // differ. They are counted in UTF-16 units, the stricter of the two ways either database might
  // count a character, so a value that passes is never refused by the column. The entities take
  // their @Column lengths from here, where the length is the same in both databases.

  /** {@code cargo_crate.name} and {@code cargo_crate.original_name}. */
  public static final int MAX_NAME_LENGTH = 64;

  /**
   * {@code cargo_crate.max_version}, {@code cargo_crate_index.vers} and {@code
   * cargo_crate_meta.version}.
   */
  public static final int MAX_VERSION_LENGTH = 64;

  /** {@code cargo_crate_index.rust_version} and {@code cargo_crate_meta.rust_version}. */
  public static final int MAX_RUST_VERSION_LENGTH = 20;

  /** {@code cargo_crate.homepage}. */
  public static final int MAX_HOMEPAGE_LENGTH = 255;

  /** {@code cargo_crate.repository}. */
  public static final int MAX_REPOSITORY_LENGTH = 255;

  /** {@code cargo_crate_meta.license}. */
  public static final int MAX_LICENSE_LENGTH = 255;

  /** {@code cargo_crate_meta.license_file}. */
  public static final int MAX_LICENSE_FILE_LENGTH = 255;

  /** {@code cargo_crate_meta.documentation}. */
  public static final int MAX_DOCUMENTATION_LENGTH = 255;

  /**
   * {@code cargo_crate_index.links}. The column is {@code varchar(255)} in H2 and {@code text} in
   * PostgreSQL; the limit applies to both, so a registry behaves the same on either.
   */
  public static final int MAX_LINKS_LENGTH = 255;

  /**
   * {@code cargo_author.author}: {@code varchar(255)} in H2, {@code text} in PostgreSQL, where the
   * unique index on it refuses a value of a few kilobytes. The limit applies to both.
   */
  public static final int MAX_AUTHOR_LENGTH = 255;

  /**
   * {@code cargo_category.category}: {@code varchar(255)} in H2, {@code text} in PostgreSQL, where
   * the unique index on it refuses a value of a few kilobytes. The limit applies to both.
   */
  public static final int MAX_CATEGORY_LENGTH = 255;

  /**
   * {@code cargo_keyword.keyword}: {@code varchar(100)} in H2, {@code text} in PostgreSQL. Cargo
   * itself allows 20 characters, which is what a publish is held to, so the column is never the
   * limit.
   */
  public static final int MAX_KEYWORD_LENGTH = 20;

  public static Pair<String, String> extractCrateNameAndVersion(final ProtocolContext context) {

    final var segments = splitPath(context);

    final var crateName = normalizeCrateName(segments[segments.length - THREE]);
    final var versionName = segments[segments.length - TWO];

    return Pair.of(crateName, versionName);
  }

  private static String[] splitPath(final ProtocolContext context) {

    return ProtocolContextUtils.getRelativePath(context).getPath().split("/");
  }

  public static String extractLastSegment(final ProtocolContext context) {

    final var segments = splitPath(context);

    return segments[segments.length - 1];
  }

  public static String normalizeCrateName(final String name) {

    return name.toLowerCase(Locale.ROOT).replace('-', '_');
  }

  /**
   * The order of a crate's versions for {@code pageable}. Versions are sorted in memory, so ties on
   * the requested key (versions published in the same instant) are broken by the version string,
   * which is unique within a crate, and every page sees the same order (RPS-1298).
   */
  public static Comparator<CrateVersionListItem> resolveVersionSort(final Pageable pageable) {

    final Comparator<CrateVersionListItem> tieBreaker =
        Comparator.comparing(CrateVersionListItem::version);

    if (pageable.getSort().isUnsorted()) {
      return Comparator.comparing(CrateVersionListItem::createdAt)
          .reversed()
          .thenComparing(tieBreaker);
    }

    final var order = pageable.getSort().iterator().next();
    Comparator<CrateVersionListItem> comparator =
        "version".equalsIgnoreCase(order.getProperty())
            ? Comparator.comparing(CrateVersionListItem::version, new SemverComparator())
            : Comparator.comparing(CrateVersionListItem::createdAt);

    if (order.isDescending()) {
      comparator = comparator.reversed();
    }

    return comparator.thenComparing(tieBreaker);
  }
}
