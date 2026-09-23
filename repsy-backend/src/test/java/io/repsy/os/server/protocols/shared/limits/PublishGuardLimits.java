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
package io.repsy.os.server.protocols.shared.limits;

import io.repsy.protocols.cargo.protocol.utils.CrateUtils;
import io.repsy.protocols.docker.shared.utils.DockerConstants;
import io.repsy.protocols.golang.shared.utils.GoVersionUtils;
import io.repsy.protocols.helm.shared.utils.HelmConstants;
import io.repsy.protocols.npm.shared.utils.NpmPublishLimits;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The limits the Helm, Cargo, Go (RPS-1072), npm (RPS-1136) and Docker (RPS-1139) publish paths
 * hold pushed metadata to, set against the columns Flyway creates. A limit is only a guard if the
 * column really is that long, so this reads {@code information_schema} and reports every limit that
 * has drifted from its column, for PostgreSQL and for H2, whose scripts differ (Cargo's {@code
 * links}, author and category are {@code text} in PostgreSQL and {@code varchar(255)} in H2).
 *
 * <p>It covers the limits of the constants above, not the {@code @Column} annotations of the
 * entities, which RPS-1133 compares with the schema.
 */
final class PublishGuardLimits {

  /** How a limit relates to the length of its column. */
  enum Rule {
    /** The column is a {@code varchar} of exactly the limit, in both databases. */
    EXACT,
    /** The column is a {@code varchar} of at least the limit, or unbounded. */
    WITHIN,
    /** {@code text} in PostgreSQL, so the limit is only a policy there, and exact in H2. */
    TEXT_IN_POSTGRES
  }

  record Limit(String constant, int value, String table, String column, Rule rule) {}

  private record DbColumn(String dataType, @Nullable Long maxLength) {}

  private static final String VARCHAR = "CHARACTER VARYING";
  private static final String TEXT = "TEXT";
  private static final String CLOB = "CHARACTER LARGE OBJECT";

  private PublishGuardLimits() {}

  /** Every limit the three publish paths apply, with the column it is measured against. */
  static List<Limit> limits() {
    final var limits = new ArrayList<Limit>();

    limits.add(
        exact(
            "HelmConstants.MAX_CHART_NAME_LENGTH",
            HelmConstants.MAX_CHART_NAME_LENGTH,
            "helm_chart",
            "name"));
    limits.add(
        exact(
            "HelmConstants.MAX_CHART_VERSION_LENGTH",
            HelmConstants.MAX_CHART_VERSION_LENGTH,
            "helm_chart_version",
            "version"));
    limits.add(
        exact(
            "HelmConstants.MAX_CHART_APP_VERSION_LENGTH",
            HelmConstants.MAX_CHART_APP_VERSION_LENGTH,
            "helm_chart_version",
            "app_version"));
    limits.add(
        exact(
            "HelmConstants.MAX_CHART_TYPE_LENGTH",
            HelmConstants.MAX_CHART_TYPE_LENGTH,
            "helm_chart_version",
            "type"));
    limits.add(
        exact(
            "HelmConstants.MAX_DIGEST_LENGTH",
            HelmConstants.MAX_DIGEST_LENGTH,
            "helm_chart_version",
            "digest"));
    limits.add(
        exact(
            "HelmConstants.MAX_DIGEST_LENGTH",
            HelmConstants.MAX_DIGEST_LENGTH,
            "helm_oci_blob",
            "digest"));
    limits.add(
        exact(
            "HelmConstants.MAX_DIGEST_LENGTH",
            HelmConstants.MAX_DIGEST_LENGTH,
            "helm_oci_manifest",
            "digest"));
    limits.add(
        exact(
            "HelmConstants.MAX_OCI_MANIFEST_NAME_LENGTH",
            HelmConstants.MAX_OCI_MANIFEST_NAME_LENGTH,
            "helm_oci_manifest",
            "name"));
    limits.add(
        exact(
            "HelmConstants.MAX_OCI_MANIFEST_REFERENCE_LENGTH",
            HelmConstants.MAX_OCI_MANIFEST_REFERENCE_LENGTH,
            "helm_oci_manifest",
            "reference"));
    limits.add(
        exact(
            "HelmConstants.MAX_OCI_MEDIA_TYPE_LENGTH",
            HelmConstants.MAX_OCI_MEDIA_TYPE_LENGTH,
            "helm_oci_manifest",
            "media_type"));
    limits.add(
        exact(
            "HelmConstants.MAX_OCI_MEDIA_TYPE_LENGTH",
            HelmConstants.MAX_OCI_MEDIA_TYPE_LENGTH,
            "helm_oci_blob",
            "media_type"));

    limits.add(
        exact("CrateUtils.MAX_NAME_LENGTH", CrateUtils.MAX_NAME_LENGTH, "cargo_crate", "name"));
    limits.add(
        exact(
            "CrateUtils.MAX_NAME_LENGTH",
            CrateUtils.MAX_NAME_LENGTH,
            "cargo_crate",
            "original_name"));
    limits.add(
        exact(
            "CrateUtils.MAX_VERSION_LENGTH",
            CrateUtils.MAX_VERSION_LENGTH,
            "cargo_crate",
            "max_version"));
    limits.add(
        exact(
            "CrateUtils.MAX_VERSION_LENGTH",
            CrateUtils.MAX_VERSION_LENGTH,
            "cargo_crate_index",
            "vers"));
    limits.add(
        exact(
            "CrateUtils.MAX_VERSION_LENGTH",
            CrateUtils.MAX_VERSION_LENGTH,
            "cargo_crate_meta",
            "version"));
    limits.add(
        exact(
            "CrateUtils.MAX_RUST_VERSION_LENGTH",
            CrateUtils.MAX_RUST_VERSION_LENGTH,
            "cargo_crate_index",
            "rust_version"));
    limits.add(
        exact(
            "CrateUtils.MAX_RUST_VERSION_LENGTH",
            CrateUtils.MAX_RUST_VERSION_LENGTH,
            "cargo_crate_meta",
            "rust_version"));
    limits.add(
        exact(
            "CrateUtils.MAX_HOMEPAGE_LENGTH",
            CrateUtils.MAX_HOMEPAGE_LENGTH,
            "cargo_crate",
            "homepage"));
    limits.add(
        exact(
            "CrateUtils.MAX_REPOSITORY_LENGTH",
            CrateUtils.MAX_REPOSITORY_LENGTH,
            "cargo_crate",
            "repository"));
    limits.add(
        exact(
            "CrateUtils.MAX_LICENSE_LENGTH",
            CrateUtils.MAX_LICENSE_LENGTH,
            "cargo_crate_meta",
            "license"));
    limits.add(
        exact(
            "CrateUtils.MAX_LICENSE_FILE_LENGTH",
            CrateUtils.MAX_LICENSE_FILE_LENGTH,
            "cargo_crate_meta",
            "license_file"));
    limits.add(
        exact(
            "CrateUtils.MAX_DOCUMENTATION_LENGTH",
            CrateUtils.MAX_DOCUMENTATION_LENGTH,
            "cargo_crate_meta",
            "documentation"));
    limits.add(
        textInPostgres(
            "CrateUtils.MAX_LINKS_LENGTH",
            CrateUtils.MAX_LINKS_LENGTH,
            "cargo_crate_index",
            "links"));
    limits.add(
        textInPostgres(
            "CrateUtils.MAX_AUTHOR_LENGTH",
            CrateUtils.MAX_AUTHOR_LENGTH,
            "cargo_author",
            "author"));
    limits.add(
        textInPostgres(
            "CrateUtils.MAX_CATEGORY_LENGTH",
            CrateUtils.MAX_CATEGORY_LENGTH,
            "cargo_category",
            "category"));
    limits.add(
        within(
            "CrateUtils.MAX_KEYWORD_LENGTH",
            CrateUtils.MAX_KEYWORD_LENGTH,
            "cargo_keyword",
            "keyword"));

    // The module path is measured against the scan row it is copied into as well, which is the
    // shorter of the two columns.
    limits.add(
        within(
            "GoVersionUtils.MAX_MODULE_PATH_LENGTH",
            GoVersionUtils.MAX_MODULE_PATH_LENGTH,
            "go_module",
            "module_path"));
    limits.add(
        exact(
            "GoVersionUtils.MAX_MODULE_PATH_LENGTH",
            GoVersionUtils.MAX_MODULE_PATH_LENGTH,
            "vulnerability_scan",
            "artifact_name"));
    limits.add(
        exact(
            "GoVersionUtils.MAX_VERSION_LENGTH",
            GoVersionUtils.MAX_VERSION_LENGTH,
            "go_module_version",
            "version"));
    limits.add(
        within(
            "GoVersionUtils.MAX_VERSION_LENGTH",
            GoVersionUtils.MAX_VERSION_LENGTH,
            "vulnerability_scan",
            "artifact_version"));
    limits.add(
        exact(
            "GoVersionUtils.MAX_GO_VERSION_LENGTH",
            GoVersionUtils.MAX_GO_VERSION_LENGTH,
            "go_module_version",
            "go_version"));

    limits.add(
        exact(
            "NpmPublishLimits.MAX_SCOPE_LENGTH",
            NpmPublishLimits.MAX_SCOPE_LENGTH,
            "npm_package",
            "scope"));
    limits.add(
        exact(
            "NpmPublishLimits.MAX_NAME_LENGTH",
            NpmPublishLimits.MAX_NAME_LENGTH,
            "npm_package",
            "name"));
    // npm_package.latest and npm_package_version.version are held to the version guard (128),
    // below the columns' actual varchar(255). vulnerability_scan.artifact_version was widened to
    // 512 by RPS-1140, so this cap is no longer needed to fit the scan row; it is left at 128
    // deliberately (RPS-1140 widens the shared column, it does not force any protocol's own
    // constant up), and can be raised independently later.
    limits.add(
        within(
            "NpmPublishLimits.MAX_VERSION_LENGTH",
            NpmPublishLimits.MAX_VERSION_LENGTH,
            "npm_package",
            "latest"));
    limits.add(
        within(
            "NpmPublishLimits.MAX_VERSION_LENGTH",
            NpmPublishLimits.MAX_VERSION_LENGTH,
            "npm_package_version",
            "version"));
    limits.add(
        within(
            "NpmPublishLimits.MAX_VERSION_LENGTH",
            NpmPublishLimits.MAX_VERSION_LENGTH,
            "vulnerability_scan",
            "artifact_version"));
    limits.add(
        exact(
            "NpmPublishLimits.MAX_AUTHOR_NAME_LENGTH",
            NpmPublishLimits.MAX_AUTHOR_NAME_LENGTH,
            "npm_package_version",
            "author_name"));
    limits.add(
        exact(
            "NpmPublishLimits.MAX_AUTHOR_EMAIL_LENGTH",
            NpmPublishLimits.MAX_AUTHOR_EMAIL_LENGTH,
            "npm_package_version",
            "author_email"));
    limits.add(
        exact(
            "NpmPublishLimits.MAX_AUTHOR_URL_LENGTH",
            NpmPublishLimits.MAX_AUTHOR_URL_LENGTH,
            "npm_package_version",
            "author_url"));
    limits.add(
        exact(
            "NpmPublishLimits.MAX_BUGS_URL_LENGTH",
            NpmPublishLimits.MAX_BUGS_URL_LENGTH,
            "npm_package_version",
            "bugs_url"));
    limits.add(
        exact(
            "NpmPublishLimits.MAX_BUGS_EMAIL_LENGTH",
            NpmPublishLimits.MAX_BUGS_EMAIL_LENGTH,
            "npm_package_version",
            "bugs_email"));
    limits.add(
        exact(
            "NpmPublishLimits.MAX_HOMEPAGE_LENGTH",
            NpmPublishLimits.MAX_HOMEPAGE_LENGTH,
            "npm_package_version",
            "homepage"));
    limits.add(
        exact(
            "NpmPublishLimits.MAX_LICENSE_LENGTH",
            NpmPublishLimits.MAX_LICENSE_LENGTH,
            "npm_package_version",
            "license"));
    limits.add(
        exact(
            "NpmPublishLimits.MAX_REPOSITORY_TYPE_LENGTH",
            NpmPublishLimits.MAX_REPOSITORY_TYPE_LENGTH,
            "npm_package_version",
            "repository_type"));
    limits.add(
        exact(
            "NpmPublishLimits.MAX_REPOSITORY_URL_LENGTH",
            NpmPublishLimits.MAX_REPOSITORY_URL_LENGTH,
            "npm_package_version",
            "repository_url"));
    limits.add(
        exact(
            "NpmPublishLimits.MAX_KEYWORD_LENGTH",
            NpmPublishLimits.MAX_KEYWORD_LENGTH,
            "npm_package_keyword",
            "keyword"));
    limits.add(
        exact(
            "NpmPublishLimits.MAX_DIST_TAG_LENGTH",
            NpmPublishLimits.MAX_DIST_TAG_LENGTH,
            "npm_package_dist_tag",
            "tag_name"));
    limits.add(
        exact(
            "NpmPublishLimits.MAX_MAINTAINER_NAME_LENGTH",
            NpmPublishLimits.MAX_MAINTAINER_NAME_LENGTH,
            "npm_package_maintainer",
            "name"));
    limits.add(
        exact(
            "NpmPublishLimits.MAX_MAINTAINER_EMAIL_LENGTH",
            NpmPublishLimits.MAX_MAINTAINER_EMAIL_LENGTH,
            "npm_package_maintainer",
            "email"));
    limits.add(
        exact(
            "NpmPublishLimits.MAX_MAINTAINER_URL_LENGTH",
            NpmPublishLimits.MAX_MAINTAINER_URL_LENGTH,
            "npm_package_maintainer",
            "url"));

    limits.add(
        exact(
            "DockerConstants.MAX_IMAGE_NAME_LENGTH",
            DockerConstants.MAX_IMAGE_NAME_LENGTH,
            "docker_image",
            "name"));
    limits.add(
        exact(
            "DockerConstants.MAX_REFERENCE_LENGTH",
            DockerConstants.MAX_REFERENCE_LENGTH,
            "docker_tag",
            "name"));
    limits.add(
        exact(
            "DockerConstants.MAX_MEDIA_TYPE_LENGTH",
            DockerConstants.MAX_MEDIA_TYPE_LENGTH,
            "docker_tag",
            "media_type"));
    limits.add(
        exact(
            "DockerConstants.MAX_PLATFORM_LENGTH",
            DockerConstants.MAX_PLATFORM_LENGTH,
            "docker_tag",
            "platform"));
    limits.add(
        exact(
            "DockerConstants.MAX_PLATFORM_LENGTH",
            DockerConstants.MAX_PLATFORM_LENGTH,
            "docker_tag_platform",
            "platform"));
    limits.add(
        exact(
            "DockerConstants.MAX_REFERENCE_LENGTH",
            DockerConstants.MAX_REFERENCE_LENGTH,
            "docker_manifest",
            "name"));
    limits.add(
        exact(
            "DockerConstants.MAX_PLATFORM_LENGTH",
            DockerConstants.MAX_PLATFORM_LENGTH,
            "docker_manifest",
            "platform"));
    limits.add(
        exact(
            "DockerConstants.MAX_MEDIA_TYPE_LENGTH",
            DockerConstants.MAX_MEDIA_TYPE_LENGTH,
            "docker_manifest",
            "media_type"));
    limits.add(
        exact(
            "DockerConstants.MAX_MEDIA_TYPE_LENGTH",
            DockerConstants.MAX_MEDIA_TYPE_LENGTH,
            "docker_manifest",
            "config_media_type"));

    // The image name and reference (tag or digest) are also measured against the scan row they
    // are copied into (RPS-1140): both fit comfortably within vulnerability_scan.artifact_name /
    // artifact_version (widened to 512), so this is "within", not "exact" like Go's module path
    // (whose own constant was chosen to equal the old artifact_name width).
    limits.add(
        within(
            "DockerConstants.MAX_IMAGE_NAME_LENGTH",
            DockerConstants.MAX_IMAGE_NAME_LENGTH,
            "vulnerability_scan",
            "artifact_name"));
    limits.add(
        within(
            "DockerConstants.MAX_REFERENCE_LENGTH",
            DockerConstants.MAX_REFERENCE_LENGTH,
            "vulnerability_scan",
            "artifact_version"));

    return limits;
  }

  private static Limit exact(
      final String constant, final int value, final String table, final String column) {
    return new Limit(constant, value, table, column, Rule.EXACT);
  }

  private static Limit within(
      final String constant, final int value, final String table, final String column) {
    return new Limit(constant, value, table, column, Rule.WITHIN);
  }

  private static Limit textInPostgres(
      final String constant, final int value, final String table, final String column) {
    return new Limit(constant, value, table, column, Rule.TEXT_IN_POSTGRES);
  }

  /**
   * The limits that differ from their column.
   *
   * @param jdbc The connection to the database under test
   * @param h2 Whether the database is H2, whose columns differ from PostgreSQL's in a few places
   */
  static List<String> problems(final JdbcTemplate jdbc, final boolean h2) {
    final var problems = new ArrayList<String>();
    final var tables = new HashMap<String, Map<String, DbColumn>>();

    for (final var limit : limits()) {
      final var columns = tables.computeIfAbsent(limit.table(), table -> readColumns(jdbc, table));
      final var db = columns.get(limit.column());
      final var problem = db == null ? "the schema has no such column" : check(limit, db, h2);

      if (problem != null) {
        problems.add(
            "%s = %d against %s.%s: %s"
                .formatted(
                    limit.constant(), limit.value(), limit.table(), limit.column(), problem));
      }
    }

    return problems;
  }

  private static @Nullable String check(final Limit limit, final DbColumn db, final boolean h2) {
    if (TEXT.equals(db.dataType()) || CLOB.equals(db.dataType())) {
      return checkUnbounded(limit, db, h2);
    }
    if (!VARCHAR.equals(db.dataType()) || db.maxLength() == null) {
      return "the column is " + db.dataType().toLowerCase(Locale.ROOT) + ", not a varchar";
    }

    final var length = db.maxLength();

    if (limit.rule() == Rule.TEXT_IN_POSTGRES && !h2) {
      return "the PostgreSQL column was expected to be text but is varchar(%d)".formatted(length);
    }
    if (limit.rule() == Rule.WITHIN) {
      return length >= limit.value() ? null : "the column is only varchar(%d)".formatted(length);
    }

    return length == limit.value() ? null : "the column is varchar(%d)".formatted(length);
  }

  private static @Nullable String checkUnbounded(
      final Limit limit, final DbColumn db, final boolean h2) {

    final var type = db.dataType().toLowerCase(Locale.ROOT);

    if (limit.rule() == Rule.WITHIN || (limit.rule() == Rule.TEXT_IN_POSTGRES && !h2)) {
      return null;
    }

    return "the column is unbounded (%s), not a varchar of the limit".formatted(type);
  }

  private static Map<String, DbColumn> readColumns(final JdbcTemplate jdbc, final String table) {
    final var columns = new HashMap<String, DbColumn>();

    jdbc.query(
        """
        select lower(column_name), upper(data_type), character_maximum_length
          from information_schema.columns
         where lower(table_schema) = 'public' and lower(table_name) = ?
        """,
        rs -> {
          // H2 reports Long.MAX_VALUE for a clob.
          final var raw = rs.getLong(3);
          final var length = rs.wasNull() ? null : raw;

          columns.put(rs.getString(1), new DbColumn(rs.getString(2), length));
        },
        table);

    return columns;
  }
}
