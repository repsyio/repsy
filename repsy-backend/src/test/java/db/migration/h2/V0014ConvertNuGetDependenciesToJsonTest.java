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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.repsy.os.panel.shared.config.configs.Jackson2MapperConfig;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetDependencyInfo;
import io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs the real migrations against an in-memory H2 database, so it needs no Docker. The legacy
 * values are produced by the application's own {@code XmlMapper}, as the pre-RPS-901 service did.
 */
@DisplayName("V0014 convert NuGet dependencies to JSON (H2)")
class V0014ConvertNuGetDependenciesToJsonTest {

  private static final List<NuGetDependencyInfo> DEPENDENCIES =
      List.of(
          new NuGetDependencyInfo("Newtonsoft.Json", "[13.0.1, )", "net6.0"),
          new NuGetDependencyInfo("Serilog", "3.1.1", "netstandard2.0"),
          new NuGetDependencyInfo("Legacy.Flat", "", null));

  private String url;

  @BeforeEach
  void setUp() {
    this.url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
  }

  @Test
  @DisplayName("rewrites XML dependencies as JSON that the NuGet API can read again")
  void convertsXmlToJson() throws Exception {

    final var xml = legacyXml(DEPENDENCIES);
    final var id = UUID.randomUUID();

    migrateTo("13");
    insert(id, xml);

    assertThat(xml).startsWith("<ArrayList>");
    assertThat(NuGetPackageUtils.parseDependenciesJson(xml)).isEmpty();

    migrateTo("14");

    final var stored = dependenciesOf(id);
    assertThat(stored).startsWith("[");
    assertThat(NuGetPackageUtils.parseDependenciesJson(stored)).isEqualTo(DEPENDENCIES);
    assertThat(stored).isEqualTo(NuGetPackageUtils.toDependenciesJson(DEPENDENCIES));
  }

  @Test
  @DisplayName("leaves JSON, null and unreadable values alone")
  void leavesOtherValuesAlone() throws Exception {

    final var json = NuGetPackageUtils.toDependenciesJson(DEPENDENCIES);
    final var malformed = "<ArrayList><item><packageId>Broken</packageId>";
    final var jsonId = UUID.randomUUID();
    final var nullId = UUID.randomUUID();
    final var malformedId = UUID.randomUUID();

    migrateTo("13");
    insert(jsonId, json);
    insert(nullId, null);
    insert(malformedId, malformed);

    migrateTo("14");

    assertThat(dependenciesOf(jsonId)).isEqualTo(json);
    assertThat(dependenciesOf(nullId)).isNull();
    assertThat(dependenciesOf(malformedId)).isEqualTo(malformed);
  }

  @Test
  @DisplayName("converts every legacy row")
  void convertsEveryRow() throws Exception {

    final var first = UUID.randomUUID();
    final var second = UUID.randomUUID();

    migrateTo("13");
    insert(first, legacyXml(DEPENDENCIES.subList(0, 1)));
    insert(second, legacyXml(DEPENDENCIES.subList(1, 3)));

    migrateTo("14");

    assertThat(NuGetPackageUtils.parseDependenciesJson(dependenciesOf(first)))
        .isEqualTo(DEPENDENCIES.subList(0, 1));
    assertThat(NuGetPackageUtils.parseDependenciesJson(dependenciesOf(second)))
        .isEqualTo(DEPENDENCIES.subList(1, 3));
  }

  @Test
  @DisplayName("runs on a database without any NuGet package versions")
  void runsOnEmptyTable() {
    migrateTo("14");
  }

  private static String legacyXml(final List<NuGetDependencyInfo> dependencies)
      throws JsonProcessingException {
    // The service serialized the ArrayList that extractDependenciesFromNuspec returns.
    return new Jackson2MapperConfig().xmlMapper().writeValueAsString(new ArrayList<>(dependencies));
  }

  private void migrateTo(final String version) {
    Flyway.configure()
        .dataSource(this.url, "sa", "")
        .locations("classpath:db/migration/h2")
        .schemas("public")
        .defaultSchema("public")
        .target(version)
        .load()
        .migrate();
  }

  private void insert(final UUID id, final @Nullable String dependencies) throws SQLException {
    try (final var connection = connect();
        final var statement = connection.createStatement();
        final var insert =
            connection.prepareStatement(
                "insert into \"public\".\"nuget_package_version\""
                    + " (\"id\", \"package_id\", \"version\", \"published_at\", \"created_at\","
                    + " \"dependencies\") values (?, ?, ?, ?, ?, ?)")) {
      // Only the version row matters here, so skip the repo and package rows it references.
      statement.execute("set referential_integrity false");
      insert.setObject(1, id);
      insert.setObject(2, UUID.randomUUID());
      insert.setString(3, "1.0.0");
      insert.setTimestamp(4, Timestamp.from(Instant.now()));
      insert.setTimestamp(5, Timestamp.from(Instant.now()));
      insert.setString(6, dependencies);
      insert.executeUpdate();
    }
  }

  private @Nullable String dependenciesOf(final UUID id) throws SQLException {
    try (final var connection = connect();
        final var select =
            connection.prepareStatement(
                "select \"dependencies\" from \"public\".\"nuget_package_version\""
                    + " where \"id\" = ?")) {
      select.setObject(1, id);
      try (final var rows = select.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  private Connection connect() throws SQLException {
    return DriverManager.getConnection(this.url, "sa", "");
  }
}
