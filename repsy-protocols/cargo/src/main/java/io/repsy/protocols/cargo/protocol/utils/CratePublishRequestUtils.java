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

import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexDep;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishDep;
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishRequest;
import io.repsy.protocols.shared.limits.FieldLimits;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.semver4j.Semver;
import org.semver4j.SemverException;
import tools.jackson.databind.ObjectMapper;

/** Validates a crate publish request and turns it into the metadata that is stored and indexed. */
@UtilityClass
public class CratePublishRequestUtils {

  private static final int MAX_KEYWORDS = 5;

  private static final Pattern CRATE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_-]*$");

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
        FieldLimits.dropEntriesIfTooLong(request.authors(), CrateUtils.MAX_AUTHOR_LENGTH, "author"),
        request.description(),
        FieldLimits.dropIfTooLong(
            request.documentation(), CrateUtils.MAX_DOCUMENTATION_LENGTH, "documentation"),
        FieldLimits.dropIfTooLong(request.homepage(), CrateUtils.MAX_HOMEPAGE_LENGTH, "homepage"),
        request.readme(),
        request.readmeFile(),
        request.keywords(),
        FieldLimits.dropEntriesIfTooLong(
            request.categories(), CrateUtils.MAX_CATEGORY_LENGTH, "category"),
        FieldLimits.dropIfTooLong(request.license(), CrateUtils.MAX_LICENSE_LENGTH, "license"),
        FieldLimits.dropIfTooLong(
            request.licenseFile(), CrateUtils.MAX_LICENSE_FILE_LENGTH, "license_file"),
        FieldLimits.dropIfTooLong(
            request.repository(), CrateUtils.MAX_REPOSITORY_LENGTH, "repository"),
        request.links(),
        request.rustVersion(),
        request.cksum(),
        request.features2());
  }

  private static void validateCrateName(final @Nullable String name) {

    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("crate name cannot be empty");
    }

    if (name.length() > CrateUtils.MAX_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "crate name `%s` must be at most %d characters"
              .formatted(name, CrateUtils.MAX_NAME_LENGTH));
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

    if (vers.length() > CrateUtils.MAX_VERSION_LENGTH) {
      throw new IllegalArgumentException(
          "version must be at most %d characters".formatted(CrateUtils.MAX_VERSION_LENGTH));
    }

    try {
      new Semver(vers);
    } catch (final SemverException ex) {
      throw new IllegalArgumentException(
          "version `%s` is not a valid semver format (expected MAJOR.MINOR.PATCH)".formatted(vers));
    }
  }

  private static void validateRustVersion(final @Nullable String rustVersion) {

    if (rustVersion != null && rustVersion.length() > CrateUtils.MAX_RUST_VERSION_LENGTH) {
      throw new IllegalArgumentException(
          "rust-version must be at most %d characters"
              .formatted(CrateUtils.MAX_RUST_VERSION_LENGTH));
    }
  }

  private static void validateLinks(final @Nullable String links) {

    if (links != null && links.length() > CrateUtils.MAX_LINKS_LENGTH) {
      throw new IllegalArgumentException(
          "links must be at most %d characters".formatted(CrateUtils.MAX_LINKS_LENGTH));
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

    if (kw.length() > CrateUtils.MAX_KEYWORD_LENGTH) {
      throw new IllegalArgumentException(
          "keyword `%s` must be at most %d characters"
              .formatted(kw, CrateUtils.MAX_KEYWORD_LENGTH));
    }
  }

  public static String getIndexJsonLine(
      final CratePublishRequest request, final ObjectMapper objectMapper) {

    final var deps =
        request.deps() == null
            ? List.<CrateIndexDep>of()
            : request.deps().stream().map(CratePublishRequestUtils::toIndexDep).toList();

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
}
