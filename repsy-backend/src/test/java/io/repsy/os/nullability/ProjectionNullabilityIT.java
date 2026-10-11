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
package io.repsy.os.nullability;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.AbstractIT;
import io.repsy.os.nullability.fixtures.ProjectionViolations;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fails when a getter of an interface projection in a {@code @NullMarked} package can answer null
 * but is not {@code @Nullable} (RPS-2077, found by the Docker {@code ImageListItem#getDigest}):
 * Spring Data guards projection getters under the marking, so such a row answers 500 instead of
 * {@code null}. The rule and its limits are in {@link ProjectionNullabilityRules}; the schema is
 * the one Flyway built for the integration database.
 *
 * <p>{@link #FROZEN} holds the getters that the rule cannot derive (an aggregate, a native query)
 * or that a {@code where} clause keeps non-null, each proved non-null by hand in the reason; it may
 * only shrink. Never add an entry for a column that can be null: annotate the getter. Only the
 * marked packages are checked, so the day another package is marked the test names what to fix
 * (npm: {@code NpmPackageListItem#getLatest}, {@code PackageMaintainerListItem#getEmail}/{@code
 * getUrl}, {@code VersionMaintainerListItem#getEmail}).
 */
class ProjectionNullabilityIT extends AbstractIT {

  /** {@code Projection#getter} that is not derivable and not null, with the proof. */
  private static final Map<String, String> FROZEN =
      Map.of(
          "io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository$"
              + "UntaggedStats#getImageId",
          "native: cast of the primary key of docker_image",
          "io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository$"
              + "UntaggedStats#getManifestCount",
          "native: count(*) of a scalar subquery",
          "io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository$"
              + "UntaggedStats#getSize",
          "native: coalesce(sum(...), 0)",
          "io.repsy.protocols.pypi.shared.python_package.dtos.PypiPackageListItem#getLatestVersion",
          "the query joins on p.latestVersion = r.version, which no null satisfies",
          "io.repsy.os.server.protocols.ruby.shared.ruby_gem.dtos.GemListItem#getUpdatedAt",
          "max(gv.createdAt) over the inner join of the gem with its latest version, grouped by"
              + " the gem: every group has a row with a NOT NULL created_at");

  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void everyProjectionGetterInANullMarkedPackageSaysWhereNullIsLegal() {
    final var rules = new ProjectionNullabilityRules(this.entityManagerFactory, this.jdbcTemplate);
    final var projections = new TreeSet<String>();
    final var violations = new TreeMap<String, String>();
    final var seen = new TreeSet<String>();

    for (final var repository : RepositoryNullabilityTest.repositories()) {
      for (final var finding : rules.check(repository, true)) {
        projections.add(finding.projection().getName());
        seen.add(finding.key());

        if (finding.violates()) {
          violations.putIfAbsent(finding.key(), finding.toString());
        }
      }
    }

    assertThat(projections.size())
        .as("projections found in the marked packages: %s", projections)
        .isGreaterThan(15);

    final var unexpected = new TreeMap<>(violations);
    unexpected.keySet().removeAll(FROZEN.keySet());

    assertThat(unexpected.values())
        .as("projection getters that can be null but are not @Nullable")
        .isEmpty();

    final var stale = new TreeSet<>(FROZEN.keySet());
    stale.removeAll(violations.keySet());

    assertThat(stale).as("fixed getters still in FROZEN, delete them").isEmpty();
  }

  @Test
  void aPlantedViolationOfEachRuleIsFound() {
    final var rules = new ProjectionNullabilityRules(this.entityManagerFactory, this.jdbcTemplate);
    final var violating =
        rules.check(ProjectionViolations.Repo.class, false).stream()
            .filter(ProjectionNullabilityRules.Finding::violates)
            .map(finding -> finding.projection().getSimpleName() + "#" + finding.getter())
            .collect(java.util.stream.Collectors.toSet());

    assertThat(violating)
        .isEqualTo(
            Set.of(
                "BadColumn#getLatest",
                "BadLeftJoin#getVersionName",
                "BadAggregate#getNewest",
                "BadWholeEntity#getLatest"));
  }

  @Test
  void aProjectionThatFollowsTheRulesIsNotFound() {
    final var rules = new ProjectionNullabilityRules(this.entityManagerFactory, this.jdbcTemplate);
    final List<String> ok =
        rules.check(ProjectionViolations.Repo.class, false).stream()
            .filter(finding -> finding.projection().getSimpleName().startsWith("Ok"))
            .filter(ProjectionNullabilityRules.Finding::violates)
            .map(ProjectionNullabilityRules.Finding::toString)
            .toList();

    assertThat(ok).isEmpty();
  }
}
