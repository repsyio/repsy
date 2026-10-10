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
package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The legacy rows V0044 (RPS-2121) is tested on, at V0043, and what must hold after it. Shared by
 * {@code V0044DropRedundantIndexesTest} (H2, rows only) and {@code V0044DropRedundantIndexesIT}
 * (PostgreSQL).
 *
 * <p>Two Maven repositories with artifacts and versions: the rows must survive the drops, a lookup
 * by repository must still be served and deleting a repository must still cascade to its children.
 */
public final class RedundantIndexesScenario {

  /** The dropped single-column index mapped to the index that now serves its lookups. */
  public static final Map<String, String> DROPPED =
      Map.ofEntries(
          Map.entry("ix_cargo_crate__repo_id", "ux_cargo_crate__repo_id_name"),
          Map.entry("ix_cargo_crate_index__crate_id", "ux_cargo_crate_index__crate_id_vers"),
          Map.entry("ix_cargo_crate_meta__crate_id", "ux_cargo_crate_meta__crate_id_version"),
          Map.entry("idx_docker_layer__repo_id", "ux_docker_layer__repo_id_digest"),
          Map.entry("idx_docker_manifest_layer__layer_id", "pk_docker_manifest_layer"),
          Map.entry("idx_docker_tag__image_id", "ux_docker_tag__image_id_name"),
          Map.entry("idx_go_module__repo_id", "ux_go_module__repo_id_module_path"),
          Map.entry("idx_go_module_version__module_id", "ux_go_module_version__module_id_version"),
          Map.entry("ix_helm_chart__repo_id", "ux_helm_chart__repo_id_name"),
          Map.entry("ix_helm_chart_version__chart_id", "ux_helm_chart_version__chart_id_version"),
          Map.entry("ix_helm_oci_blob__repo_id", "ux_helm_oci_blob__repo_id_digest"),
          Map.entry(
              "ix_helm_oci_manifest__repo_id", "ux_helm_oci_manifest__repo_id_name_reference"),
          Map.entry("idx_key_store__repo_id", "ux_key_store__repo_id__allowed_keyserver_id"),
          Map.entry("idx_maven_artifact__repo_id", "ux_maven_artifact__repo_id_group_artifact"),
          Map.entry(
              "idx_maven_artifact_version__artifact_id",
              "ux_maven_artifact_version__artifact_id_version_name"),
          Map.entry(
              "idx_npm_package_dist_tag__package_version_id",
              "ux_npm_package_dist_tag__version_id_tag_name"),
          Map.entry(
              "idx_npm_package_version__package_id", "ux_npm_package_version__package_id_version"),
          Map.entry("ix_nuget_package__repo_id", "ux_nuget_package__repo_id_package_id"),
          Map.entry(
              "ix_nuget_package_version__package_id",
              "ux_nuget_package_version__package_id_version"),
          Map.entry("idx_pgp_public_key__repo_id", "ux_pgp_public_key__repo_id__fingerprint"),
          Map.entry("idx_pypi_package__repo_id", "ux_pypi_package__repo_id_normalized_name"),
          Map.entry("idx_pypi_release__package_id", "ux_pypi_release__package_id_version"),
          Map.entry("ix_ruby_gem__repo_id", "ux_ruby_gem__repo_id_name"),
          Map.entry("ix_ruby_gem_version__gem_id", "ux_ruby_gem_version__gem_id_version_platform"),
          Map.entry(
              "ix_vulnerability_scan__repo_id",
              "ix_vulnerability_scan__repo_id_artifact_name_artifact_version"));

  /** Indexes that stay: their uniques are expression indexes or the table is the dead one. */
  public static final java.util.List<String> KEPT =
      java.util.List.of(
          "idx_docker_image__repo_id",
          "idx_npm_package__repo_id",
          "idx_docker_manifest_layer__manifest_id",
          "idx_docker_tag_platform__tag_id");

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  public final UUID keptRepo = UUID.randomUUID();
  public final UUID deletedRepo = UUID.randomUUID();

  private final JdbcTemplate jdbc;

  public RedundantIndexesScenario(final JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Seeds two repositories with three artifacts of two versions each; the schema must be at V0043.
   */
  public void seed() {
    for (final var repoId : new UUID[] {this.keptRepo, this.deletedRepo}) {
      this.jdbc.update(
          "insert into \"public\".\"repo\" (\"id\", \"name\", \"type\", \"allow_override\","
              + " \"created_at\") values (?, ?, 'MAVEN', true, ?)",
          repoId,
          "maven-" + repoId.toString().substring(0, 8),
          Timestamp.from(NOW));
      for (int a = 0; a < 3; a++) {
        final var artifactId = UUID.randomUUID();
        this.jdbc.update(
            "insert into \"public\".\"maven_artifact\" (\"id\", \"repo_id\", \"group_name\","
                + " \"artifact_name\") values (?, ?, 'io.repsy', ?)",
            artifactId,
            repoId,
            "artifact-" + a);
        for (int v = 0; v < 2; v++) {
          this.jdbc.update(
              "insert into \"public\".\"maven_artifact_version\" (\"id\", \"artifact_id\","
                  + " \"type\", \"version_name\") values (?, ?, 'RELEASE', ?)",
              UUID.randomUUID(),
              artifactId,
              "1." + v);
        }
      }
    }
  }

  /** Asserts the dropped indexes are gone and the covering ones and the kept ones are there. */
  public void assertDropped(final Predicate<String> indexExists) {
    DROPPED.forEach(
        (dropped, covering) -> {
          assertThat(indexExists.test(dropped)).as("%s is dropped", dropped).isFalse();
          assertThat(indexExists.test(covering)).as("%s covers it", covering).isTrue();
        });
    for (final var kept : KEPT) {
      assertThat(indexExists.test(kept)).as("%s is kept", kept).isTrue();
    }

    this.assertRowsIntact();
  }

  /** Asserts the rows are intact and a repository delete still cascades. */
  public void assertRowsIntact() {
    assertThat(count("maven_artifact", "repo_id", this.keptRepo)).isEqualTo(3);
    assertThat(
            this.jdbc.queryForObject(
                "select count(*) from \"public\".\"maven_artifact_version\" v join \"public\".\"maven_artifact\" a"
                    + " on a.\"id\" = v.\"artifact_id\" where a.\"repo_id\" = ?",
                Integer.class,
                this.keptRepo))
        .isEqualTo(6);

    this.jdbc.update("delete from \"public\".\"repo\" where \"id\" = ?", this.deletedRepo);

    assertThat(count("maven_artifact", "repo_id", this.deletedRepo)).isZero();
    assertThat(
            this.jdbc.queryForObject(
                "select count(*) from \"public\".\"maven_artifact_version\"", Integer.class))
        .isEqualTo(6);
    assertThat(count("maven_artifact", "repo_id", this.keptRepo)).isEqualTo(3);
  }

  private int count(final String table, final String column, final UUID id) {
    return this.jdbc.queryForObject(
        "select count(*) from \"public\".\"%s\" where \"%s\" = ?".formatted(table, column),
        Integer.class,
        id);
  }
}
