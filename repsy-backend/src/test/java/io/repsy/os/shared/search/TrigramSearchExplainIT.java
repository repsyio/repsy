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
package io.repsy.os.shared.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.AbstractIT;
import io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository;
import io.repsy.os.server.protocols.golang.shared.go_module.repositories.GoModuleRepository;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.NpmPackageRepository;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageRepository;
import io.repsy.os.server.protocols.pypi.shared.python_package.repositories.PypiPackageRepository;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.repositories.RubyGemRepository;
import io.repsy.os.shared.user.repositories.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.ConnectionCallback;

/**
 * The SQL Hibernate emits for each contains-search must be one the trigram index of V0041 can serve
 * (RPS-2117): the planner has to be able to pick it, which holds only when the indexed expression
 * and the one in the query are the same expression, byte for byte after parsing.
 *
 * <p>The JPQL is read from the {@code @Query} of the repository method itself and run in a session
 * with a statement inspector, so a rewrite that drifts from the index fails here. The tables are
 * nearly empty, so sequential scans are switched off for the one transaction to get the choice the
 * planner makes on a table with rows.
 */
@DisplayName("Contains searches use their trigram index (RPS-2117)")
class TrigramSearchExplainIT extends AbstractIT {

  @Autowired private EntityManagerFactory entityManagerFactory;

  static Stream<Arguments> searches() {
    return Stream.of(
        Arguments.of(
            ArtifactRepository.class,
            "findAllByRepoIdAndContainsGroupName",
            "idx_maven_artifact__group_artifact_trgm"),
        Arguments.of(
            ArtifactRepository.class,
            "findAllByRepoIdContainsArtifactName",
            "idx_maven_artifact__group_artifact_trgm"),
        Arguments.of(
            NpmPackageRepository.class,
            "findAllByRepoIdAndLatestVersionAndScopeIsNullContainsName",
            "idx_npm_package__scope_name_trgm"),
        Arguments.of(
            NpmPackageRepository.class,
            "findAllByRepoIdAndLatestVersionAndScopeContainsName",
            "idx_npm_package__scope_name_trgm"),
        Arguments.of(
            NpmPackageRepository.class,
            "findAllByRepoIdAndLatestVersionContainsScope",
            "idx_npm_package__scope_name_trgm"),
        Arguments.of(
            ImageRepository.class, "findAllByRepoIdAndContainsName", "idx_docker_image__name_trgm"),
        Arguments.of(
            PypiPackageRepository.class,
            "findAllByRepoIdContainsName",
            "idx_pypi_package__name_trgm"),
        Arguments.of(
            HelmChartVersionRepository.class,
            "findLatestByRepoIdAndQuery",
            "idx_helm_chart__name_trgm"),
        Arguments.of(
            RubyGemRepository.class, "findAllByRepoIdContainsName", "idx_ruby_gem__name_trgm"),
        Arguments.of(
            GoModuleRepository.class,
            "findAllByRepoIdContainsModulePath",
            "idx_go_module__module_path_trgm"),
        Arguments.of(NuGetPackageRepository.class, "search", "idx_nuget_package__package_id_trgm"),
        Arguments.of(UserRepository.class, "findAllWithSearch", "idx_users__username_trgm"));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("searches")
  @DisplayName("the planner can use the index")
  void plannerUsesIndex(final Class<?> repository, final String method, final String index) {
    final var sql = this.sqlOf(repository, method);

    this.dropOtherIndexes(index);

    assertThat(this.plan(sql, index)).as("plan of %s", sql).contains(index);
  }

  /**
   * Drops, for this transaction only (DDL is transactional on PostgreSQL), the other indexes of the
   * table of {@code index} (but its primary key and keys that a foreign key needs). The tables hold
   * no rows, so the planner has no statistics to prefer the trigram index over a btree on {@code
   * repo_id}; with nothing else to choose, it picks the trigram index exactly when the query's
   * expression matches it.
   */
  private void dropOtherIndexes(final String index) {
    final var others =
        this.jdbcTemplate.queryForList(
            """
            SELECT other.indexname FROM pg_indexes target
            JOIN pg_indexes other
              ON other.schemaname = target.schemaname AND other.tablename = target.tablename
            WHERE target.indexname = ? AND other.indexname <> target.indexname
              AND NOT EXISTS (
                SELECT 1 FROM pg_constraint c
                WHERE c.conindid = (quote_ident(other.schemaname) || '.' || quote_ident(other.indexname))::regclass)
            """,
            String.class,
            index);
    for (final var other : others) {
      this.jdbcTemplate.execute("DROP INDEX \"" + other + "\"");
    }
    // A unique constraint is an index too; only the primary key is left.
    final var uniques =
        this.jdbcTemplate.queryForList(
            "SELECT c.conrelid::regclass::text || '|' || c.conname FROM pg_constraint c"
                + " WHERE c.contype = 'u' AND c.conrelid = (SELECT (quote_ident(schemaname) || '.'"
                + " || quote_ident(tablename))::regclass FROM pg_indexes WHERE indexname = ?)",
            String.class,
            index);
    for (final var unique : uniques) {
      final var parts = unique.split("\\|");
      this.jdbcTemplate.execute(
          "ALTER TABLE " + parts[0] + " DROP CONSTRAINT \"" + parts[1] + "\"");
    }
  }

  /** The select Hibernate sends for the JPQL of {@code method}, with its placeholders. */
  private String sqlOf(final Class<?> repository, final String method) {
    final var jpql =
        Arrays.stream(repository.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(method))
            .map(candidate -> candidate.getAnnotation(Query.class))
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElseThrow()
            .value();
    final var recorded = new ArrayList<String>();
    final var sessionFactory = this.entityManagerFactory.unwrap(SessionFactory.class);

    try (final var session =
        sessionFactory
            .withOptions()
            .statementInspector(
                (StatementInspector)
                    sql -> {
                      recorded.add(sql);
                      return sql;
                    })
            .openSession()) {
      final var query = session.createQuery(jpql);
      for (final var parameter : query.getParameters()) {
        final var name = parameter.getName();
        final Object value =
            switch (name) {
              case "repoId" -> UUID.randomUUID();
              case "includePrerelease", "includeSemVer2" -> Boolean.TRUE;
              default -> "%abc%";
            };
        query.setParameter(name, value);
      }
      query.setMaxResults(10);
      query.getResultList();
    }

    return recorded.stream()
        .filter(sql -> sql.toLowerCase(java.util.Locale.ROOT).startsWith("select"))
        .findFirst()
        .orElseThrow();
  }

  /**
   * The plan of {@code SELECT 1 FROM <table of index> WHERE <the search predicate of sql>}: the
   * predicate is cut out of the query Hibernate sent and run against the one table, so the join
   * order the planner picks over the few rows of the shared test database cannot hide whether the
   * index matches the expression.
   */
  private String plan(final String sql, final String index) {
    final var like = sql.indexOf(" like ?");
    final var start = sql.lastIndexOf("lower(", like);
    assertThat(start).as("a lower(...) like ? predicate in %s", sql).isNotNegative();
    // The alias of the table (np1_0.) is not valid in the single-table statement.
    final var predicate = sql.substring(start, like).replaceAll("\\b\\w+_\\d\\.", "");
    final var table =
        this.jdbcTemplate.queryForObject(
            "SELECT quote_ident(tablename) FROM pg_indexes WHERE indexname = ?",
            String.class,
            index);

    return this.jdbcTemplate.execute(
        (ConnectionCallback<String>)
            connection -> {
              try (final var statement = connection.createStatement()) {
                statement.execute("SET LOCAL enable_seqscan = off");
                try (final var rows =
                    statement.executeQuery(
                        "EXPLAIN SELECT 1 FROM %s WHERE %s LIKE '%%abc%%'"
                            .formatted(table, predicate))) {
                  return text(rows);
                }
              }
            });
  }

  private static String text(final ResultSet rows) throws SQLException {
    final var plan = new StringBuilder();
    while (rows.next()) {
      plan.append(rows.getString(1)).append('\n');
    }

    return plan.toString();
  }
}
