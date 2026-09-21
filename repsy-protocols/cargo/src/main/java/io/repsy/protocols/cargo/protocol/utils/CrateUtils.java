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
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexDep;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishDep;
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishRequest;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateVersionListItem;
import io.repsy.protocols.cargo.shared.crate.services.SemverComparator;
import io.repsy.protocols.shared.utils.BoundedEntryReader;
import io.repsy.protocols.shared.utils.EntryTooLargeException;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.jspecify.annotations.Nullable;
import org.semver4j.Semver;
import org.semver4j.SemverException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.util.Pair;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@UtilityClass
public class CrateUtils {

  private static final long MEBIBYTE = 1024L * 1024L;

  /**
   * The largest {@code Cargo.toml} a crate may carry, in bytes. A real manifest is a few kilobytes,
   * and even one that lists thousands of features stays far below this; the limit only has to stop
   * a decompression bomb, which the tar header size lets {@link #isLib(byte[])} refuse before it
   * inflates any of it.
   */
  public static final long MAX_CARGO_TOML_BYTES = 10 * MEBIBYTE;

  private static final int TWO = 2;
  private static final int THREE = 3;

  private static final Pattern CRATE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_-]*$");

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

  private static final int MAX_KEYWORDS = 5;

  public static Pair<String, String> extractCrateNameAndVersion(final ProtocolContext context) {

    final var segments = CrateUtils.splitPath(context);

    final var crateName = CrateUtils.normalizeCrateName(segments[segments.length - THREE]);
    final var versionName = segments[segments.length - TWO];

    return Pair.of(crateName, versionName);
  }

  private static long readU32LittleEndian(final InputStream inputStream) throws IOException {

    final var bytes = inputStream.readNBytes(4);

    return Integer.toUnsignedLong(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt());
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
   * Refuses a publish whose metadata cannot be stored. It runs before the crate or its index entry
   * is written, so a refused publish leaves nothing behind. A value is rejected when cutting or
   * dropping it would change what the registry serves: the name and version identify the crate, the
   * rust-version says which compilers may use it, and {@code links} is what the index resolves the
   * native library a crate links to by. The descriptive fields that can be dropped instead are
   * handled by {@link #dropOverLongMetadata(CratePublishRequest)}.
   */
  public static void validatePublishRequest(final CratePublishRequest request) {
    validateCrateName(request.name());
    validateVersion(request.vers());
    validateKeywords(request.keywords());
    validateRustVersion(request.rustVersion());
    validateLinks(request.links());
  }

  /**
   * Returns the request without the descriptive metadata that does not fit its column (RPS-1072):
   * the homepage, repository, documentation, license and license file are dropped, and so is any
   * author or category that is too long. Cutting a URL would make it point somewhere else and
   * cutting a name or an SPDX expression would make it say something it does not, so a value is
   * dropped whole. The publish itself goes through: none of these is needed to fetch the crate, and
   * the {@code .crate} file, which carries its own Cargo.toml, is stored untouched.
   */
  public static CratePublishRequest dropOverLongMetadata(final CratePublishRequest request) {

    return new CratePublishRequest(
        request.name(),
        request.vers(),
        request.hasLib(),
        request.deps(),
        request.features(),
        dropEntriesIfTooLong(request.authors(), MAX_AUTHOR_LENGTH, "author"),
        request.description(),
        dropIfTooLong(request.documentation(), MAX_DOCUMENTATION_LENGTH, "documentation"),
        dropIfTooLong(request.homepage(), MAX_HOMEPAGE_LENGTH, "homepage"),
        request.readme(),
        request.readmeFile(),
        request.keywords(),
        dropEntriesIfTooLong(request.categories(), MAX_CATEGORY_LENGTH, "category"),
        dropIfTooLong(request.license(), MAX_LICENSE_LENGTH, "license"),
        dropIfTooLong(request.licenseFile(), MAX_LICENSE_FILE_LENGTH, "license_file"),
        dropIfTooLong(request.repository(), MAX_REPOSITORY_LENGTH, "repository"),
        request.links(),
        request.rustVersion(),
        request.cksum(),
        request.features2());
  }

  private static @Nullable String dropIfTooLong(
      final @Nullable String value, final int maxLength, final String field) {

    if (value != null && value.length() > maxLength) {
      log.warn("Skipping {}: longer than {} characters", field, maxLength);
      return null;
    }

    return value;
  }

  private static @Nullable List<String> dropEntriesIfTooLong(
      final @Nullable List<String> values, final int maxLength, final String field) {

    if (values == null) {
      return null;
    }

    return values.stream()
        .filter(
            value -> {
              final var fits = value == null || value.length() <= maxLength;
              if (!fits) {
                log.warn("Skipping a {}: longer than {} characters", field, maxLength);
              }
              return fits;
            })
        .toList();
  }

  private static void validateCrateName(final @Nullable String name) {

    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("crate name cannot be empty");
    }

    if (name.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "crate name `%s` must be at most %d characters".formatted(name, MAX_NAME_LENGTH));
    }

    if (!CRATE_NAME_PATTERN.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "crate name `%s` must start with an alphanumeric character and contain only alphanumerics, `-`, or `_`"
              .formatted(name));
    }
  }

  private static void validateVersion(final @Nullable String vers) {

    if (vers == null || vers.isBlank()) {
      throw new IllegalArgumentException("version cannot be empty");
    }

    if (vers.length() > MAX_VERSION_LENGTH) {
      throw new IllegalArgumentException(
          "version must be at most %d characters".formatted(MAX_VERSION_LENGTH));
    }

    try {
      new Semver(vers);
    } catch (final SemverException ex) {
      throw new IllegalArgumentException(
          "version `%s` is not a valid semver format (expected MAJOR.MINOR.PATCH)".formatted(vers));
    }
  }

  private static void validateRustVersion(final @Nullable String rustVersion) {

    if (rustVersion != null && rustVersion.length() > MAX_RUST_VERSION_LENGTH) {
      throw new IllegalArgumentException(
          "rust-version must be at most %d characters".formatted(MAX_RUST_VERSION_LENGTH));
    }
  }

  private static void validateLinks(final @Nullable String links) {

    if (links != null && links.length() > MAX_LINKS_LENGTH) {
      throw new IllegalArgumentException(
          "links must be at most %d characters".formatted(MAX_LINKS_LENGTH));
    }
  }

  private static void validateKeywords(final @Nullable List<String> keywords) {

    if (keywords == null) {
      return;
    }

    if (keywords.size() > MAX_KEYWORDS) {
      throw new IllegalArgumentException(
          "a crate may have at most %d keywords, got %d".formatted(MAX_KEYWORDS, keywords.size()));
    }

    for (final var kw : keywords) {
      validateKeyword(kw);
    }
  }

  private static void validateKeyword(final String kw) {

    if (kw.length() > MAX_KEYWORD_LENGTH) {
      throw new IllegalArgumentException(
          "keyword `%s` must be at most %d characters".formatted(kw, MAX_KEYWORD_LENGTH));
    }
  }

  public static String getIndexJsonLine(
      final CratePublishRequest request, final ObjectMapper objectMapper) {

    final var deps =
        request.deps() == null
            ? List.<CrateIndexDep>of()
            : request.deps().stream().map(CrateUtils::toIndexDep).toList();

    final var features =
        request.features() != null ? request.features() : Map.<String, List<String>>of();

    final var v = request.features2() != null ? 2 : 1;

    final var entry =
        new CrateIndexEntry(
            request.name(),
            request.vers(),
            deps,
            request.cksum(),
            features,
            false,
            request.links(),
            v,
            request.features2(),
            request.rustVersion());

    return objectMapper.writeValueAsString(entry);
  }

  private static CrateIndexDep toIndexDep(final CratePublishDep dep) {

    final String packageName;
    final String name;

    if (dep.explicitNameInToml() != null) {
      name = dep.explicitNameInToml();
      packageName = dep.name();
    } else {
      name = dep.name();
      packageName = null;
    }

    return new CrateIndexDep(
        name,
        dep.versionReq(),
        dep.features(),
        dep.optional(),
        dep.defaultFeatures(),
        dep.target(),
        dep.kind(),
        dep.registry(),
        packageName);
  }

  public static CratePublishRequest createCratePublishRequestWithChecksum(
      final CratePublishRequest request, final String checksum, final boolean hasLib) {

    return new CratePublishRequest(
        request.name(),
        request.vers(),
        hasLib,
        request.deps(),
        request.features(),
        request.authors(),
        request.description(),
        request.documentation(),
        request.homepage(),
        request.readme(),
        request.readmeFile(),
        request.keywords(),
        request.categories(),
        request.license(),
        request.licenseFile(),
        request.repository(),
        request.links(),
        request.rustVersion(),
        checksum,
        request.features2());
  }

  public static CratePublishRequest getPublishRequest(
      final InputStream inputStream, final ObjectMapper objectMapper) throws IOException {

    final var jsonLength = CrateUtils.readU32LittleEndian(inputStream);
    final var jsonBytes = inputStream.readNBytes((int) jsonLength);
    return objectMapper.readValue(jsonBytes, CratePublishRequest.class);
  }

  public static byte[] getCrateBytes(final InputStream inputStream) throws IOException {

    final var crateLength = CrateUtils.readU32LittleEndian(inputStream);

    return inputStream.readNBytes((int) crateLength);
  }

  public static Comparator<CrateVersionListItem> resolveVersionSort(final Pageable pageable) {

    if (pageable.getSort().isUnsorted()) {
      return Comparator.comparing(CrateVersionListItem::createdAt).reversed();
    }

    final var order = pageable.getSort().iterator().next();
    Comparator<CrateVersionListItem> comparator =
        "version".equalsIgnoreCase(order.getProperty())
            ? Comparator.comparing(CrateVersionListItem::version, new SemverComparator())
            : Comparator.comparing(CrateVersionListItem::createdAt);

    if (order.isDescending()) {
      comparator = comparator.reversed();
    }

    return comparator;
  }

  @SneakyThrows
  public static boolean isLib(final byte[] crateBytes) {
    try (final var tar =
        new TarArchiveInputStream(
            new GzipCompressorInputStream(new ByteArrayInputStream(crateBytes)))) {

      TarArchiveEntry entry;

      while ((entry = tar.getNextEntry()) != null) {
        final var entryName = entry.getName();

        if (entryName.endsWith("/src/lib.rs")) {
          return true;
        }

        if (entryName.endsWith("/Cargo.toml")) {
          final var toml = new String(readCargoToml(tar, entry), StandardCharsets.UTF_8);
          if (toml.lines().anyMatch(line -> line.trim().equals("[lib]"))) {
            return true;
          }
        }
      }
      return false;
    }
  }

  private static byte[] readCargoToml(final InputStream tar, final TarArchiveEntry entry)
      throws IOException {

    try {
      return BoundedEntryReader.readAllBytes(tar, entry.getSize(), MAX_CARGO_TOML_BYTES);
    } catch (final EntryTooLargeException e) {
      throw new IllegalArgumentException(
          "Cargo.toml in the crate must be at most %d MiB"
              .formatted(MAX_CARGO_TOML_BYTES / MEBIBYTE),
          e);
    }
  }
}
