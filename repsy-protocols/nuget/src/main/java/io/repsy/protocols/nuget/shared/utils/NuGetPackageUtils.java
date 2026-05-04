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

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.nuget.protocol.facades.dtos.PackageIdVersion;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetDependencyInfo;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
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
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
}
