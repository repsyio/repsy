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
package db.migration.h2;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;
import tools.jackson.databind.ObjectMapper;

/**
 * Repairs {@code nuget_package_version.dependencies} values that were stored as XML.
 *
 * <p>Before RPS-901 the NuGet service serialized a version's dependencies with the injected Jackson
 * mapper, which in this application is the {@code XmlMapper}. PostgreSQL rejected that value in its
 * {@code jsonb} column, but the H2 {@code clob} column accepted it, so H2 installs still hold rows
 * such as {@code <ArrayList><item><packageId>...}. The application reads the column as JSON, so
 * those versions came back without dependencies. This migration rewrites them to the JSON the
 * application writes today: {@code [{"packageId":..,"versionRange":..,"targetFramework":..}]}.
 *
 * <p>It is safe to re-run: values that are already JSON are left alone, and a value that cannot be
 * converted is logged and left as it is rather than failing the startup. PostgreSQL never held XML,
 * so it only has a no-op script with the same version.
 */
@Slf4j
public class V0014ConvertNuGetDependenciesToJson extends BaseJavaMigration {

  private static final String SELECT_SQL =
      "select \"id\", \"dependencies\" from \"public\".\"nuget_package_version\""
          + " where \"dependencies\" is not null";
  private static final String UPDATE_SQL =
      "update \"public\".\"nuget_package_version\" set \"dependencies\" = ? where \"id\" = ?";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /**
   * Flyway derives the version and description from a {@code V14__Description} class name. The
   * class name here follows the Java naming rules instead, so both are given explicitly below.
   */
  @Override
  protected void init() {
    // Nothing to derive from the class name.
  }

  @Override
  public MigrationVersion getVersion() {
    return MigrationVersion.fromVersion("14");
  }

  @Override
  public String getDescription() {
    return "Convert NuGet Dependencies To Json";
  }

  @Override
  public void migrate(final Context context) throws SQLException {

    final var legacyRows = new ArrayList<LegacyRow>();

    try (final var statement = context.getConnection().createStatement();
        final var rows = statement.executeQuery(SELECT_SQL)) {
      while (rows.next()) {
        final var value = rows.getString("dependencies");
        if (value != null && value.strip().startsWith("<")) {
          legacyRows.add(new LegacyRow(rows.getObject("id", UUID.class), value));
        }
      }
    }

    int converted = 0;

    try (final var update = context.getConnection().prepareStatement(UPDATE_SQL)) {
      for (final var row : legacyRows) {
        final var json = toJson(row);
        if (json != null) {
          update.setString(1, json);
          update.setObject(2, row.id());
          update.executeUpdate();
          converted++;
        }
      }
    }

    log.info(
        "Converted the dependencies of {} of {} NuGet package versions from XML to JSON",
        converted,
        legacyRows.size());
  }

  private static @Nullable String toJson(final LegacyRow row) {
    try {
      return OBJECT_MAPPER.writeValueAsString(parseDependencies(row.xml()));
    } catch (final Exception e) {
      log.warn("Leaving the dependencies of NuGet package version {} as they are", row.id(), e);
      return null;
    }
  }

  private static List<Map<String, @Nullable String>> parseDependencies(final String xml)
      throws Exception {

    final var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

    final var document =
        factory
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    final var items = document.getElementsByTagName("item");

    final var dependencies = new ArrayList<Map<String, @Nullable String>>();

    for (int i = 0; i < items.getLength(); i++) {
      final var item = (Element) items.item(i);
      final var packageId = childText(item, "packageId");

      if (packageId != null) {
        final var dependency = new LinkedHashMap<String, @Nullable String>();
        dependency.put("packageId", packageId);
        final var versionRange = childText(item, "versionRange");
        dependency.put("versionRange", versionRange == null ? "" : versionRange);
        dependency.put("targetFramework", childText(item, "targetFramework"));
        dependencies.add(dependency);
      }
    }

    return dependencies;
  }

  /** Returns the trimmed text of the child element, or {@code null} when it is absent or empty. */
  private static @Nullable String childText(final Element parent, final String name) {
    final var children = parent.getElementsByTagName(name);
    if (children.getLength() == 0) {
      return null;
    }
    final var text = children.item(0).getTextContent().strip();
    return text.isEmpty() ? null : text;
  }

  private record LegacyRow(UUID id, String xml) {}
}
