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
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.semver4j.Semver;
import org.semver4j.SemverException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.util.Pair;
import tools.jackson.databind.ObjectMapper;

@UtilityClass
public class CrateUtils {

  private static final int TWO = 2;
  private static final int THREE = 3;

  private static final Pattern CRATE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_-]*$");

  private static final int MAX_NAME_LENGTH = 64;
  private static final int MAX_KEYWORDS = 5;
  private static final int MAX_KEYWORD_LENGTH = 20;

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

    return name.toLowerCase().replace('-', '_');
  }

  public static void validatePublishRequest(final CratePublishRequest request) {
    validateCrateName(request.name());
    validateVersion(request.vers());
    validateKeywords(request.keywords());
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

    try {
      new Semver(vers);
    } catch (final SemverException ex) {
      throw new IllegalArgumentException(
          "version `%s` is not a valid semver format (expected MAJOR.MINOR.PATCH)".formatted(vers));
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
      final CratePublishRequest request, final String checksum) {

    return new CratePublishRequest(
        request.name(),
        request.vers(),
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
}
