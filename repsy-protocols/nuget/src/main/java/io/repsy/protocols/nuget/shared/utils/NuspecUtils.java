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

import io.repsy.protocols.nuget.protocol.facades.dtos.NuspecMetadata;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetDependencyGroupInfo;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetDependencyInfo;
import io.repsy.protocols.shared.limits.FieldLimits;
import io.repsy.protocols.shared.utils.BoundedEntryReader;
import io.repsy.protocols.shared.utils.EntryTooLargeException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Reads the {@code .nuspec} of a NuGet package: its metadata, README and dependencies. */
@Slf4j
@NullMarked
@UtilityClass
public final class NuspecUtils {

  private static final int MAX_README_BYTES = 256 * 1024;
  private static final long MEBIBYTE = 1024L * 1024L;

  /**
   * The largest {@code .nuspec} a package may carry, in bytes. A real nuspec is a few kilobytes;
   * even one that lists hundreds of dependency groups and long release notes stays well below 1
   * MiB, which is also four times the README cap. The limit only has to stop a decompression bomb:
   * the read is bounded, so no more than this is ever inflated into memory.
   */
  public static final long MAX_NUSPEC_BYTES = MEBIBYTE;

  /**
   * The {@code packageType} name a NuGet symbol package (a {@code .snupkg}) declares in its nuspec,
   * inside {@code <packageTypes>}. See {@link #validateNotSymbolsPackage}.
   */
  private static final String SYMBOLS_PACKAGE_TYPE = "SymbolsPackage";

  private static final Pattern NUGET_ID_PATTERN =
      Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}$");
  private static final Pattern NUGET_VERSION_PATTERN =
      Pattern.compile(
          "^(0|[1-9][0-9]*)(?:\\.(0|[1-9][0-9]*)){0,3}(?:-[a-zA-Z0-9][a-zA-Z0-9.-]*)?"
              + "(?:\\+[a-zA-Z0-9][a-zA-Z0-9.-]*)?$");

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
      log.debug("Failed to extract {} from nuspec ({})", tagName, e.getClass().getName());
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
      log.debug("Failed to extract {} from nuspec ({})", tagName, e.getClass().getName());
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
    return truncate(
        extractMetadataField(nuspecXml, "title"), NuGetPackageUtils.MAX_TITLE_LENGTH, "title");
  }

  /**
   * Reads the tags from the nuspec. Tags longer than the {@code tags} column are cut to fit, which
   * can leave a partial last tag.
   */
  public static @Nullable String extractTags(final String nuspecXml) {
    return truncate(
        extractMetadataField(nuspecXml, "tags"), NuGetPackageUtils.MAX_TAGS_LENGTH, "tags");
  }

  /**
   * Reads a URL element such as {@code iconUrl} from the nuspec. A URL longer than its column is
   * dropped rather than cut, because a cut URL links somewhere else. The nuspec is stored as sent,
   * so the full value is not lost.
   */
  public static @Nullable String extractUrl(final String nuspecXml, final String tagName) {
    return FieldLimits.dropIfTooLong(
        extractMetadataField(nuspecXml, tagName),
        NuGetPackageUtils.MAX_URL_LENGTH,
        tagName + " URL");
  }

  /**
   * Reads the repository URL from the nuspec {@code <repository>} element. The standard form
   * carries it in the {@code url} attribute ({@code <repository type="git" url="..." />}); the
   * element text is used as a fallback for the plain-text form. A URL longer than the {@code
   * repository_url} column is dropped rather than failing the publish.
   */
  public static @Nullable String extractRepositoryUrl(final String nuspecXml) {
    return FieldLimits.dropIfTooLong(
        readRepositoryUrl(nuspecXml), NuGetPackageUtils.MAX_URL_LENGTH, "repository URL");
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
      log.debug(
          "Failed to parse nuspec ({}), falling back to the plain-text repository form",
          e.getClass().getName());
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

  /**
   * Reads the dependencies the nuspec declares, flattened: every dependency carries the target
   * framework of its group. A group without dependencies contributes nothing here, use {@link
   * #extractDependencyGroupsFromNuspec} to keep it.
   */
  public static List<NuGetDependencyInfo> extractDependenciesFromNuspec(
      final String nuspecXml, final String packageId, final String version) {

    return NuGetDependencyJsonUtils.flatten(
        extractDependencyGroupsFromNuspec(nuspecXml, packageId, version));
  }

  /**
   * Reads the dependency groups the nuspec declares, in document order, empty groups included
   * (RPS-1555). A nuspec with the old flat format ({@code <dependency>} directly under {@code
   * <dependencies>}) has one group without a target framework. A nuspec without a {@code
   * <dependencies>} element declares none, so that is an empty list without a warning. A nuspec the
   * XML parser rejects also yields an empty list, but with a warning naming the package: {@link
   * #readNuspecMetadata(Path)} refuses such a nuspec before a publish gets here, so this only
   * guards other callers. The warning carries no nuspec content.
   */
  public static List<NuGetDependencyGroupInfo> extractDependencyGroupsFromNuspec(
      final String nuspecXml, final String packageId, final String version) {

    final var result = new ArrayList<NuGetDependencyGroupInfo>();

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
      final List<NuGetDependencyGroupInfo> result, final NodeList groups) {

    for (int i = 0; i < groups.getLength(); i++) {
      final var group = (Element) groups.item(i);
      final var tf = group.getAttribute("targetFramework");
      final var targetFramework = tf.isBlank() ? null : tf;
      final var deps = group.getElementsByTagName("dependency");
      final var members = new ArrayList<NuGetDependencyInfo>();

      for (int j = 0; j < deps.getLength(); j++) {
        final var dep = (Element) deps.item(j);
        final var id = dep.getAttribute("id");
        final var version = dep.getAttribute("version");
        if (!id.isBlank()) {
          members.add(
              new NuGetDependencyInfo(id, version.isBlank() ? "" : version, targetFramework));
        }
      }
      result.add(new NuGetDependencyGroupInfo(targetFramework, members));
    }
  }

  private static void addOldFlatFormats(
      final List<NuGetDependencyGroupInfo> result, final Element dependenciesEl) {

    // Old flat format: <dependency> directly under <dependencies>
    final var deps = dependenciesEl.getElementsByTagName("dependency");
    final var members = new ArrayList<NuGetDependencyInfo>();

    for (int i = 0; i < deps.getLength(); i++) {
      final var dep = (Element) deps.item(i);
      final var id = dep.getAttribute("id");
      final var version = dep.getAttribute("version");

      if (!id.isBlank()) {
        members.add(new NuGetDependencyInfo(id, version.isBlank() ? "" : version, null));
      }
    }
    if (!members.isEmpty()) {
      result.add(new NuGetDependencyGroupInfo(null, members));
    }
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
    validateNotSymbolsPackage(nuspecXml);

    final var packageId = extractMetadataField(nuspecXml, "id");
    final var version = extractMetadataField(nuspecXml, "version");

    if (packageId == null || packageId.isBlank() || version == null || version.isBlank()) {
      throw new IllegalArgumentException("Missing 'id' or 'version' in nuspec.");
    }
    validatePackageId(packageId);
    validatePackageVersion(version);

    final var normalizedVersion = NuGetVersionUtils.normalizeNuGetVersion(version);
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

  /**
   * Refuses a symbol package (a {@code .snupkg}, whose nuspec declares {@code <packageTypes>
   * <packageType name="SymbolsPackage" /></packageTypes>}) with a 400. A symbol package carries
   * only debug symbols, built and named after the same id and version as the real package it
   * debugs; Repsy has no separate home for it and no {@code SymbolPackagePublish} service-index
   * resource for a client to send it to. Storing it under the {@code v3/package} push path used by
   * the real {@code .nupkg} would silently overwrite that package's files with the symbols
   * (RPS-1569), so the push is refused here, before anything is written, instead.
   */
  private static void validateNotSymbolsPackage(final String nuspecXml) {
    final NodeList packageTypes;
    try {
      packageTypes = parseNuspec(nuspecXml).getElementsByTagName("packageType");
    } catch (final Exception e) {
      // The caller already rejected a nuspec that is not well-formed XML before this runs.
      return;
    }

    for (int i = 0; i < packageTypes.getLength(); i++) {
      final var name = ((Element) packageTypes.item(i)).getAttribute("name");
      if (SYMBOLS_PACKAGE_TYPE.equalsIgnoreCase(name)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "This is a NuGet symbol package (.snupkg); Repsy does not accept symbol packages.");
      }
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
    if (version.length() > NuGetPackageUtils.MAX_VERSION_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "NuGet version is longer than " + NuGetPackageUtils.MAX_VERSION_LENGTH + " characters.");
    }
  }
}
