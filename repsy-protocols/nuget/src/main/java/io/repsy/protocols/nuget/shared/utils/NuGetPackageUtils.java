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
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.semver4j.Semver;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@NullMarked
@UtilityClass
public final class NuGetPackageUtils {

  private static final int THREE = 3;
  private static final int FOUR = 4;
  public static final String FORMAT_JSON = ".json";
  private static final int REGISTRATION_PAGE_SIZE = 64;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final Comparator<String> VERSION_COMPARATOR =
      (v1, v2) -> {
        try {
          return Objects.requireNonNull(Semver.coerce(v1))
              .compareTo(Objects.requireNonNull(Semver.coerce(v2)));
        } catch (final Exception e) {
          return v1.compareToIgnoreCase(v2);
        }
      };

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

  public static String extractNuspec(final InputStream inputStream) throws IOException {

    try (final var zipIn = new ZipInputStream(inputStream)) {
      ZipEntry entry;
      while ((entry = zipIn.getNextEntry()) != null) {
        if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".nuspec")) {
          return new String(zipIn.readAllBytes(), StandardCharsets.UTF_8);
        }
      }
    }
    throw new IllegalArgumentException(
        "The uploaded file is not a valid NuGet package (.nuspec not found).");
  }

  public static String extractPackageId(final ProtocolContext context) {
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();
    final var parts = path.split("/");
    return parts.length > THREE ? parts[THREE] : "";
  }

  public static PackageIdVersion extractPackageIdAndVersion(final ProtocolContext context) {
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();
    final var parts = path.split("/");
    return new PackageIdVersion(
        parts.length > THREE ? parts[THREE] : "", parts.length > FOUR ? parts[FOUR] : "");
  }

  public static List<NuGetDependencyInfo> extractDependenciesFromNuspec(final String nuspecXml) {

    final var result = new ArrayList<NuGetDependencyInfo>();

    try {
      final var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);

      final var builder = factory.newDocumentBuilder();
      final var doc =
          builder.parse(new ByteArrayInputStream(nuspecXml.getBytes(StandardCharsets.UTF_8)));

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
      log.debug("Failed to extract dependencies from nuspec", e);
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

  public static List<NuGetDependencyInfo> parseDependenciesJson(@Nullable final String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return OBJECT_MAPPER.readValue(json, new TypeReference<List<NuGetDependencyInfo>>() {});
    } catch (final Exception e) {
      log.debug("Failed to parse dependencies JSON", e);
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

    final var packageId = extractXmlTag(nuspecXml, "id");
    final var version = extractXmlTag(nuspecXml, "version");

    if (packageId == null || packageId.isBlank() || version == null || version.isBlank()) {
      throw new IllegalArgumentException("Missing 'id' or 'version' in nuspec.");
    }

    return new NuspecMetadata(packageId, version, nuspecXml);
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
