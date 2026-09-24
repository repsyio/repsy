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
package io.repsy.protocols.nuget.shared.utils;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.nuget.protocol.facades.dtos.NuspecMetadata;
import io.repsy.protocols.nuget.protocol.facades.dtos.PackageIdVersion;
import io.repsy.protocols.nuget.shared.dtos.NuGetRegistrationLeafItem;
import io.repsy.protocols.nuget.shared.dtos.NuGetRegistrationPageItem;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetDependencyInfo;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BoundedEntryReader;
import io.repsy.protocols.shared.utils.EntryTooLargeException;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.semver4j.Semver;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@NullMarked
@UtilityClass
public final class NuGetPackageUtils {

  public static final String FORMAT_JSON = ".json";
  public static final Map<String, String> NUGET_CONTEXT =
      Map.of(
          "@vocab", "http://schema.nuget.org/schema#",
          "comment", "http://www.w3.org/2000/01/rdf-schema#comment");
  private static final int THREE = 3;
  private static final int FOUR = 4;
  private static final String ZERO_VERSION = "0.0.0";
  private static final int REGISTRATION_PAGE_SIZE = 64;
  private static final int MAX_README_BYTES = 256 * 1024;
  private static final long MEBIBYTE = 1024L * 1024L;

  /**
   * The largest {@code .nuspec} a package may carry, in bytes. A real nuspec is a few kilobytes;
   * even one that lists hundreds of dependency groups and long release notes stays well below 1
   * MiB, which is also four times the README cap. The limit only has to stop a decompression bomb:
   * the read is bounded, so no more than this is ever inflated into memory.
   */
  public static final long MAX_NUSPEC_BYTES = MEBIBYTE;

  // The limits of the nuget_package and nuget_package_version varchar columns (V0005 migration).
  // They are the single place for these numbers: the guards below cut, drop or reject a value at
  // the limit, and the NuGetPackage and NuGetPackageVersion entities give the same value to
  // @Column(length). Where H2 and PostgreSQL differ (tags is text in PostgreSQL, varchar(1024) in
  // H2) the smaller one applies. NuGetSchemaAnnotationIT fails when one drifts from the schema.

  /** The {@code nuget_package.package_id} column. The id pattern accepts at most 100 characters. */
  public static final int MAX_PACKAGE_ID_LENGTH = 256;

  /** The {@code nuget_package_version.version} column. */
  public static final int MAX_VERSION_LENGTH = 64;

  /** The {@code nuget_package_version.title} column. */
  public static final int MAX_TITLE_LENGTH = 512;

  /** The {@code nuget_package_version.tags} column (PostgreSQL: text, H2: varchar(1024)). */
  public static final int MAX_TAGS_LENGTH = 1024;

  /** The {@code icon_url}, {@code license_url}, {@code project_url} and {@code repository_url}. */
  public static final int MAX_URL_LENGTH = 512;

  /**
   * Upper bound the search and autocomplete endpoints clamp {@code take} to (nuget.org's own
   * convention: a request for more gets this many results instead of an error). Large enough for
   * any real client page, small enough that one request cannot read a whole repo's package listing
   * into memory.
   */
  public static final int MAX_SEARCH_TAKE = 1000;

  private static final Pattern NUGET_ID_PATTERN =
      Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}$");
  private static final Pattern NUGET_VERSION_PATTERN =
      Pattern.compile(
          "^(0|[1-9][0-9]*)(?:\\.(0|[1-9][0-9]*)){0,3}(?:-[a-zA-Z0-9][a-zA-Z0-9.-]*)?"
              + "(?:\\+[a-zA-Z0-9][a-zA-Z0-9.-]*)?$");
  private static final Pattern SEMVER_LEVEL_PATTERN = Pattern.compile("^([0-9]+)(?:[.+-].*)?$");
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /**
   * Orders NuGet versions the way NuGet does (see {@link #compareVersions}), lowest first. Reverse
   * it, or take the {@code max}, to get the latest version.
   */
  public static final Comparator<String> VERSION_COMPARATOR = NuGetPackageUtils::compareVersions;

  /**
   * Orders two NuGet versions the way NuGet does: by major, minor, patch and revision (the optional
   * fourth part, numerically, a missing part counting as 0), then by pre-release label, where a
   * release sorts after its own pre-releases. Build metadata is ignored. A value that cannot be
   * parsed falls back to a case-insensitive string comparison.
   */
  static int compareVersions(final String v1, final String v2) {
    try {
      final var first = v1.strip().toLowerCase(Locale.ROOT);
      final var second = v2.strip().toLowerCase(Locale.ROOT);

      final var numeric = compareNumericParts(numericParts(first), numericParts(second));
      if (numeric != 0) {
        return numeric;
      }
      return Objects.requireNonNull(Semver.parse(ZERO_VERSION + preRelease(first)))
          .compareTo(Objects.requireNonNull(Semver.parse(ZERO_VERSION + preRelease(second))));
    } catch (final Exception e) {
      return v1.compareToIgnoreCase(v2);
    }
  }

  private static String[] numericParts(final String version) {
    final var parts = substringBefore(substringBefore(version, '+'), '-').split("\\.");
    if (parts.length > FOUR) {
      throw new IllegalArgumentException("More than four version parts: " + version);
    }
    return parts;
  }

  private static int compareNumericParts(final String[] first, final String[] second) {
    for (int i = 0; i < FOUR; i++) {
      final var result = numericPart(first, i).compareTo(numericPart(second, i));
      if (result != 0) {
        return result;
      }
    }
    return 0;
  }

  private static BigInteger numericPart(final String[] parts, final int index) {
    return index < parts.length ? new BigInteger(parts[index]) : BigInteger.ZERO;
  }

  private static String preRelease(final String version) {
    final var withoutBuild = substringBefore(version, '+');
    return withoutBuild.substring(substringBefore(withoutBuild, '-').length());
  }

  /**
   * Normalizes a NuGet version string to its canonical form: - Lowercased - Trailing zero
   * components stripped (min 3: major.minor.patch) - Build metadata ({@code +...}) dropped, since
   * NuGet ignores it when it compares versions - 1.0 → 1.0.0, 1.0.0.0 → 1.0.0, 1.0.0-Alpha →
   * 1.0.0-alpha, 1.0.0+Build → 1.0.0. The pre-release suffix is kept as is.
   */
  public static String normalizeNuGetVersion(final String rawVersion) {
    return normalize(rawVersion);
  }

  /**
   * Whether the version is SemVer 2.0.0-only, that is not a valid SemVer 1.0.0 version: it carries
   * build metadata ({@code 1.0.0+abc}) or a pre-release label with dot-separated identifiers
   * ({@code 1.0.0-beta.1}). This is nuget.org's definition, which clients that send no {@code
   * semVerLevel} rely on to be left with versions they can parse.
   */
  public static boolean isSemVer2(final String version) {
    return version.indexOf('+') >= 0
        || preRelease(version.strip().toLowerCase(Locale.ROOT)).indexOf('.') >= 0;
  }

  /**
   * Whether the {@code semVerLevel} query parameter of the search and autocomplete endpoints opts
   * in to SemVer 2.0.0 versions: a version string whose major part is 2 or more ({@code 2.0.0}). A
   * missing, blank or lower value (or one that is not a version at all) is the SemVer 1.0.0
   * default, as it is on nuget.org.
   */
  public static boolean acceptsSemVer2(final @Nullable String semVerLevel) {
    if (semVerLevel == null) {
      return false;
    }
    final var matcher = SEMVER_LEVEL_PATTERN.matcher(semVerLevel.strip());
    return matcher.matches() && new BigInteger(matcher.group(1)).compareTo(BigInteger.TWO) >= 0;
  }

  private static String normalize(final String rawVersion) {

    final var lower = rawVersion.strip().toLowerCase(Locale.ROOT);
    final var withoutBuild = substringBefore(lower, '+');

    final var core = substringBefore(withoutBuild, '-');
    final var preRelease = withoutBuild.substring(core.length());

    final var components = core.split("\\.");

    int end = components.length;
    while (end > THREE && "0".equals(components[end - 1])) {
      end--;
    }

    return buildVersionString(components, end) + preRelease;
  }

  private static String substringBefore(final String value, final char separator) {
    final var idx = value.indexOf(separator);
    return idx >= 0 ? value.substring(0, idx) : value;
  }

  private static String buildVersionString(final String[] components, final int end) {
    final var sb = new StringBuilder();
    final int partCount = Math.max(end, 3);
    for (int i = 0; i < partCount; i++) {
      if (i > 0) {
        sb.append('.');
      }
      sb.append(i < components.length ? components[i] : "0");
    }
    return sb.toString();
  }

  /**
   * Parses the {@code skip} or {@code take} query parameter of the search and autocomplete
   * endpoints, defaulting to {@code defaultValue} when the client left it out. A value that is not
   * a non-negative integer — non-numeric, decimal, negative, or one that overflows {@code int} — is
   * a client error and throws {@link IllegalArgumentException} instead of letting {@link
   * Integer#parseInt} throw {@link NumberFormatException} out to a handler's catch-all, which would
   * answer 500 and log it as a server error.
   */
  public static int parseNonNegativeParam(
      final @Nullable String value, final int defaultValue, final String paramName) {

    if (value == null) {
      return defaultValue;
    }

    final int parsed;
    try {
      parsed = Integer.parseInt(value);
    } catch (final NumberFormatException e) {
      throw new IllegalArgumentException("'" + paramName + "' is not a valid integer: " + value, e);
    }

    if (parsed < 0) {
      throw new IllegalArgumentException("'" + paramName + "' must not be negative: " + value);
    }

    return parsed;
  }

  /**
   * Reads a tag with a regular expression, tolerant of a nuspec that is not well-formed XML. Used
   * only where that tolerance is still wanted: the plain-text {@code <readme>} path and the
   * plain-text fallback of {@link #extractRepositoryUrl}, for a caller other than the push path
   * (which already rejects a nuspec that is not well-formed XML before either is reached). A
   * regular expression does not unescape an XML entity or resolve a CDATA section, and it can match
   * a tag inside a comment, so a {@code metadata} field is read with {@link #extractMetadataField}
   * instead.
   */
  public static @Nullable String extractXmlTag(final String xml, final String tagName) {
    try {
      final var patternStr = String.format("<%s>([^<]+)</%s>", tagName, tagName);
      final var matcher = Pattern.compile(patternStr).matcher(xml);
      if (matcher.find()) {
        return matcher.group(1).trim();
      }
    } catch (final Exception e) {
      log.debug("Failed to extract {} from nuspec", tagName, e);
    }
    return null;
  }

  /**
   * Reads a {@code <metadata>} child element's text content from the nuspec with the XML parser, so
   * an XML-escaped ampersand or angle bracket, or a CDATA section, comes back decoded instead of
   * raw. A tag inside a comment is not an element and is never matched. Returns {@code null} when
   * the element is absent, blank, or the nuspec cannot be parsed: {@link #readNuspecMetadata(Path)}
   * already rejects a nuspec that is not well-formed XML before a publish reaches this, so only
   * other callers see a parse failure here.
   */
  public static @Nullable String extractMetadataField(
      final String nuspecXml, final String tagName) {
    try {
      return metadataChildText(parseNuspec(nuspecXml), tagName);
    } catch (final Exception e) {
      log.debug("Failed to extract {} from nuspec", tagName, e);
      return null;
    }
  }

  private static @Nullable String metadataChildText(final Document doc, final String tagName) {
    final var metadataList = doc.getElementsByTagName("metadata");
    if (metadataList.getLength() == 0) {
      return null;
    }

    final var child = firstElementChild(metadataList.item(0), tagName);
    return child == null ? null : blankToNull(child.getTextContent());
  }

  private static @Nullable Node firstElementChild(final Node parent, final String tagName) {
    final var children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      final var node = children.item(i);
      if (node.getNodeType() == Node.ELEMENT_NODE && tagName.equals(node.getNodeName())) {
        return node;
      }
    }
    return null;
  }

  private static @Nullable String blankToNull(final @Nullable String text) {
    return text == null || text.isBlank() ? null : text.strip();
  }

  /**
   * Reads the title from the nuspec. A title longer than the {@code title} column is cut to fit: it
   * is only shown, and its start still names the package.
   */
  public static @Nullable String extractTitle(final String nuspecXml) {
    return truncate(extractMetadataField(nuspecXml, "title"), MAX_TITLE_LENGTH, "title");
  }

  /**
   * Reads the tags from the nuspec. Tags longer than the {@code tags} column are cut to fit, which
   * can leave a partial last tag.
   */
  public static @Nullable String extractTags(final String nuspecXml) {
    return truncate(extractMetadataField(nuspecXml, "tags"), MAX_TAGS_LENGTH, "tags");
  }

  /**
   * Reads a URL element such as {@code iconUrl} from the nuspec. A URL longer than its column is
   * dropped rather than cut, because a cut URL links somewhere else. The nuspec is stored as sent,
   * so the full value is not lost.
   */
  public static @Nullable String extractUrl(final String nuspecXml, final String tagName) {
    return dropIfTooLong(extractMetadataField(nuspecXml, tagName), tagName);
  }

  /**
   * Reads the repository URL from the nuspec {@code <repository>} element. The standard form
   * carries it in the {@code url} attribute ({@code <repository type="git" url="..." />}); the
   * element text is used as a fallback for the plain-text form. A URL longer than the {@code
   * repository_url} column is dropped rather than failing the publish.
   */
  public static @Nullable String extractRepositoryUrl(final String nuspecXml) {
    return dropIfTooLong(readRepositoryUrl(nuspecXml), "repository");
  }

  private static @Nullable String dropIfTooLong(final @Nullable String url, final String field) {
    if (url != null && url.length() > MAX_URL_LENGTH) {
      log.warn("Skipping {} URL: longer than {} characters", field, MAX_URL_LENGTH);
      return null;
    }
    return url;
  }

  private static @Nullable String truncate(
      final @Nullable String value, final int maxLength, final String field) {

    if (value == null || value.codePointCount(0, value.length()) <= maxLength) {
      return value;
    }

    log.warn("Truncating {}: longer than {} characters", field, maxLength);
    return value.substring(0, value.offsetByCodePoints(0, maxLength));
  }

  private static @Nullable String readRepositoryUrl(final String nuspecXml) {
    try {
      final var repositories = parseNuspec(nuspecXml).getElementsByTagName("repository");
      if (repositories.getLength() == 0) {
        return null;
      }

      final var repository = (Element) repositories.item(0);
      final var urlAttribute = repository.getAttribute("url").strip();
      if (!urlAttribute.isEmpty()) {
        return urlAttribute;
      }

      final var text = repository.getTextContent().strip();
      return text.isEmpty() ? null : text;
    } catch (final Exception e) {
      log.debug("Failed to parse nuspec, falling back to the plain-text repository form", e);
      return extractXmlTag(nuspecXml, "repository");
    }
  }

  /**
   * Reads the README the nuspec {@code <readme>} element points at from the package, or returns
   * {@code null} when there is none, it is missing from the archive, too large or not text.
   */
  public static @Nullable String extractReadme(final Path nupkg, final String nuspecXml) {
    final var readmePath = extractXmlTag(nuspecXml, "readme");
    if (readmePath == null) {
      return null;
    }

    final var wanted = normalizeEntryName(readmePath);

    try (final var zipIn = new ZipInputStream(Files.newInputStream(nupkg))) {
      ZipEntry entry;
      while ((entry = zipIn.getNextEntry()) != null) {
        if (!entry.isDirectory() && matchesEntry(entry.getName(), wanted)) {
          return readReadmeContent(zipIn, readmePath);
        }
      }
    } catch (final IOException e) {
      log.debug("Failed to read the README '{}' from the package", readmePath, e);
      return null;
    }

    log.debug("README '{}' declared in the nuspec is not in the package", readmePath);
    return null;
  }

  private static @Nullable String readReadmeContent(final InputStream in, final String readmePath)
      throws IOException {

    final var bytes = in.readNBytes(MAX_README_BYTES + 1);
    if (bytes.length > MAX_README_BYTES) {
      log.warn("Skipping README '{}': larger than {} bytes", readmePath, MAX_README_BYTES);
      return null;
    }

    final var content = new String(bytes, StandardCharsets.UTF_8);
    // PostgreSQL text columns reject NUL, so a binary file must not be stored as a README.
    if (content.indexOf('\0') >= 0) {
      log.warn("Skipping README '{}': not a text file", readmePath);
      return null;
    }
    return content.startsWith("﻿") ? content.substring(1) : content;
  }

  private static boolean matchesEntry(final String entryName, final String wanted) {
    final var normalized = normalizeEntryName(entryName);
    if (normalized.equalsIgnoreCase(wanted)) {
      return true;
    }
    // OPC part names percent-encode characters such as spaces (docs/My%20Readme.md).
    try {
      final var decoded = URLDecoder.decode(normalized.replace("+", "%2B"), StandardCharsets.UTF_8);
      return decoded.equalsIgnoreCase(wanted);
    } catch (final IllegalArgumentException e) {
      return false;
    }
  }

  private static String normalizeEntryName(final String name) {
    var normalized = name.strip().replace('\\', '/');
    while (normalized.startsWith("/") || normalized.startsWith("./")) {
      normalized = normalized.substring(normalized.startsWith("/") ? 1 : 2);
    }
    return normalized;
  }

  private static Document parseNuspec(final String nuspecXml) throws Exception {
    final var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);

    return factory
        .newDocumentBuilder()
        .parse(new ByteArrayInputStream(nuspecXml.getBytes(StandardCharsets.UTF_8)));
  }

  public static String extractNuspec(final InputStream inputStream) throws IOException {

    try (final var zipIn = new ZipInputStream(inputStream)) {
      ZipEntry entry;
      while ((entry = zipIn.getNextEntry()) != null) {
        if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".nuspec")) {
          return new String(readNuspecBytes(zipIn, entry), StandardCharsets.UTF_8);
        }
      }
    }
    throw new IllegalArgumentException(
        "The uploaded file is not a valid NuGet package (.nuspec not found).");
  }

  // A zip entry does not always carry its size in the header (a data descriptor leaves it unknown),
  // so BoundedEntryReader bounds the read itself and uses the header only as a shortcut.
  private static byte[] readNuspecBytes(final InputStream zipIn, final ZipEntry entry)
      throws IOException {

    try {
      return BoundedEntryReader.readAllBytes(zipIn, entry.getSize(), MAX_NUSPEC_BYTES);
    } catch (final EntryTooLargeException e) {
      throw new IllegalArgumentException(
          "The .nuspec in the package must be at most %d MiB."
              .formatted(MAX_NUSPEC_BYTES / MEBIBYTE),
          e);
    }
  }

  public static String extractPackageId(final ProtocolContext context) {
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();
    final var parts = path.split("/", -1);
    return parts.length > THREE ? parts[THREE] : "";
  }

  public static PackageIdVersion extractPackageIdAndVersion(final ProtocolContext context) {
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();
    final var parts = path.split("/", -1);
    return new PackageIdVersion(
        parts.length > THREE ? parts[THREE] : "", parts.length > FOUR ? parts[FOUR] : "");
  }

  /**
   * Reads the dependencies the nuspec declares. A nuspec without a {@code <dependencies>} element
   * declares none, so that is an empty list without a warning. A nuspec the XML parser rejects also
   * yields an empty list, but with a warning naming the package: {@link #readNuspecMetadata(Path)}
   * refuses such a nuspec before a publish gets here, so this only guards other callers. The
   * warning carries no nuspec content.
   */
  public static List<NuGetDependencyInfo> extractDependenciesFromNuspec(
      final String nuspecXml, final String packageId, final String version) {

    final var result = new ArrayList<NuGetDependencyInfo>();

    try {
      final var doc = parseNuspec(nuspecXml);

      doc.getDocumentElement().normalize();

      final NodeList dependenciesNodes = doc.getElementsByTagName("dependencies");

      if (dependenciesNodes.getLength() == 0) {
        return result;
      }

      final var dependenciesEl = (Element) dependenciesNodes.item(0);

      // New grouped format: <group targetFramework="..."><dependency .../></group>
      final NodeList groups = dependenciesEl.getElementsByTagName("group");

      if (groups.getLength() > 0) {
        addNewGroupedFormats(result, groups);
      } else {
        addOldFlatFormats(result, dependenciesEl);
      }
    } catch (final Exception e) {
      log.warn(
          "Ignoring unreadable dependencies of NuGet package {} {}: {}",
          packageId,
          version,
          e.getClass().getSimpleName());
    }
    return result;
  }

  private static void addNewGroupedFormats(
      final List<NuGetDependencyInfo> result, final NodeList groups) {

    for (int i = 0; i < groups.getLength(); i++) {
      final var group = (Element) groups.item(i);
      final var tf = group.getAttribute("targetFramework");
      final var targetFramework = tf.isBlank() ? null : tf;
      final var deps = group.getElementsByTagName("dependency");

      for (int j = 0; j < deps.getLength(); j++) {
        final var dep = (Element) deps.item(j);
        final var id = dep.getAttribute("id");
        final var version = dep.getAttribute("version");
        if (!id.isBlank()) {
          final var info =
              new NuGetDependencyInfo(id, version.isBlank() ? "" : version, targetFramework);
          result.add(info);
        }
      }
    }
  }

  private static void addOldFlatFormats(
      final List<NuGetDependencyInfo> result, final Element dependenciesEl) {

    // Old flat format: <dependency> directly under <dependencies>
    final var deps = dependenciesEl.getElementsByTagName("dependency");

    for (int i = 0; i < deps.getLength(); i++) {
      final var dep = (Element) deps.item(i);
      final var id = dep.getAttribute("id");
      final var version = dep.getAttribute("version");

      if (!id.isBlank()) {
        result.add(new NuGetDependencyInfo(id, version.isBlank() ? "" : version, null));
      }
    }
  }

  public static String toDependenciesJson(final List<NuGetDependencyInfo> dependencies) {
    return OBJECT_MAPPER.writeValueAsString(dependencies);
  }

  /**
   * Reads the dependencies stored for a package version. A {@code null} or blank value means the
   * package declares none. A value that is there but cannot be read is a stored-data problem, not
   * an absence of dependencies, so it is logged at {@code warn} with the package id and version.
   * The value itself is left out of the log, and the caller still gets an empty list so one bad
   * column does not fail the rest of the version.
   */
  public static List<NuGetDependencyInfo> parseDependenciesJson(
      @Nullable final String json, final String packageId, final String version) {

    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      final List<NuGetDependencyInfo> dependencies =
          OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
      return dependencies == null ? List.of() : dependencies;
    } catch (final Exception e) {
      log.warn(
          "Ignoring unreadable dependencies of NuGet package {} {} ({} characters): {}",
          packageId,
          version,
          json.length(),
          e.getClass().getSimpleName());
      return List.of();
    }
  }

  public static List<NuGetRegistrationPageItem> buildRegistrationPages(
      final List<NuGetRegistrationLeafItem> leafItems, final String indexUrl) {

    if (leafItems.size() <= REGISTRATION_PAGE_SIZE) {
      final var lowerVersion =
          leafItems.stream()
              .map(i -> i.catalogEntry().version())
              .min(VERSION_COMPARATOR)
              .orElse("");
      final var upperVersion =
          leafItems.stream()
              .map(i -> i.catalogEntry().version())
              .max(VERSION_COMPARATOR)
              .orElse("");

      return List.of(
          new NuGetRegistrationPageItem(
              indexUrl,
              "catalog:CatalogPage",
              leafItems.size(),
              leafItems,
              lowerVersion,
              upperVersion));
    }

    // Split into pages of REGISTRATION_PAGE_SIZE
    final var pages = new ArrayList<NuGetRegistrationPageItem>();
    int pageIndex = 0;
    for (int offset = 0; offset < leafItems.size(); offset += REGISTRATION_PAGE_SIZE) {
      final var chunk =
          leafItems.subList(offset, Math.min(offset + REGISTRATION_PAGE_SIZE, leafItems.size()));
      final var pageUrl = indexUrl.replace("/index.json", "/page/" + pageIndex + FORMAT_JSON);
      final var lowerVersion =
          chunk.stream().map(i -> i.catalogEntry().version()).min(VERSION_COMPARATOR).orElse("");
      final var upperVersion =
          chunk.stream().map(i -> i.catalogEntry().version()).max(VERSION_COMPARATOR).orElse("");
      pages.add(
          new NuGetRegistrationPageItem(
              pageUrl, "catalog:CatalogPage", chunk.size(), chunk, lowerVersion, upperVersion));
      pageIndex++;
    }
    return pages;
  }

  public static NuspecMetadata readNuspecMetadata(final Path tempFile) throws IOException {
    final String nuspecXml;

    try (final var is = Files.newInputStream(tempFile)) {
      nuspecXml = extractNuspec(is);
    }

    // Well-formedness is checked before the metadata is read: id and version now come from the XML
    // parser (RPS-1145) rather than a regular expression, so a nuspec that is not well-formed XML
    // must be rejected here first, with its own message, instead of surfacing as a missing id or
    // version.
    validateWellFormed(nuspecXml);

    final var packageId = extractMetadataField(nuspecXml, "id");
    final var version = extractMetadataField(nuspecXml, "version");

    if (packageId == null || packageId.isBlank() || version == null || version.isBlank()) {
      throw new IllegalArgumentException("Missing 'id' or 'version' in nuspec.");
    }
    validatePackageId(packageId);
    validatePackageVersion(version);

    final var normalizedVersion = normalizeNuGetVersion(version);
    validateVersionLength(normalizedVersion);

    return new NuspecMetadata(
        packageId, normalizedVersion, nuspecXml, extractReadme(tempFile, nuspecXml));
  }

  // Every metadata field, and the dependencies and repository URL, are read with the XML parser, so
  // a nuspec that is not well-formed XML is rejected here, before any of it is read, with a message
  // that says so instead of a missing-field or unreadable-dependencies one.
  private static void validateWellFormed(final String nuspecXml) {
    try {
      parseNuspec(nuspecXml);
    } catch (final Exception e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "The .nuspec in the package is not well-formed XML.", e);
    }
  }

  private static void validatePackageId(final String id) {
    if (!NUGET_ID_PATTERN.matcher(id).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid NuGet package id.");
    }
  }

  private static void validatePackageVersion(final String version) {
    if (!NUGET_VERSION_PATTERN.matcher(version.strip()).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid NuGet version format.");
    }
  }

  // The version is stored as it is, so unlike the metadata it cannot be cut or dropped. Rejecting
  // it here, before any row is written, also leaves no package row behind.
  private static void validateVersionLength(final String version) {
    if (version.length() > MAX_VERSION_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "NuGet version is longer than " + MAX_VERSION_LENGTH + " characters.");
    }
  }

  public static void copyStreamToFile(final InputStream stream, final Path target)
      throws IOException {

    final long size = Files.copy(stream, target, REPLACE_EXISTING);

    if (size == 0) {
      throw new IllegalArgumentException("NuGet package stream is empty.");
    }
  }

  public static void checkVersionAllowance(final String version, final BaseRepoInfo<?> repoInfo) {

    final boolean isPrerelease = version.contains("-");
    if (isPrerelease && Boolean.FALSE.equals(repoInfo.getSnapshots())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "Pre-release packages are not allowed in this repository.");
    }
    if (!isPrerelease && Boolean.FALSE.equals(repoInfo.getReleases())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT, "Release packages are not allowed in this repository.");
    }
  }
}
