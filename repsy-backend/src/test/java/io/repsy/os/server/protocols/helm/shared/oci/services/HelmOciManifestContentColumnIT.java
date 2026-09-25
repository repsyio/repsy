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
package io.repsy.os.server.protocols.helm.shared.oci.services;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.helm.shared.chart.services.HelmChartService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestForm;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RPS-1392: {@code helm_oci_manifest.content} is a plain {@code text} column. Mapped with
 * {@code @Lob}, Hibernate bound the value as a CLOB and PostgreSQL stored a large-object OID
 * ("17509") in the column: the JSON lived in {@code pg_largeobject}, SQL could not read it and
 * deleting the row leaked the object.
 */
@DisplayName("Helm OCI manifest content is stored inline (RPS-1392)")
class HelmOciManifestContentColumnIT extends AbstractIntegrationTest {

  private static final String CONTENT =
      "{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\"}";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private HelmChartService helmChartService;
  @Autowired private HelmOciManifestService helmOciManifestService;

  private long largeObjects() {
    return this.jdbcTemplate.queryForObject(
        "select count(*) from pg_largeobject_metadata", Long.class);
  }

  @Test
  @DisplayName("a saved manifest is the JSON in the column, not a large-object OID")
  void storesTheJsonInTheColumn() {
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));
    final var chart =
        this.helmChartService.findOrCreate(
            HelmChartForm.builder()
                .name("payments")
                .version("1.0.0")
                .description("payments chart")
                .appVersion("1.0.0")
                .type("application")
                .digest("sha256:" + "a".repeat(64))
                .size(10)
                .build(),
            repo.getId());
    final var largeObjectsBefore = this.largeObjects();

    this.helmOciManifestService.save(
        HelmOciManifestForm.builder()
            .chartId(chart.id())
            .name("payments")
            .reference("1.0.0")
            .digest("sha256:" + "b".repeat(64))
            .mediaType("application/vnd.oci.image.manifest.v1+json")
            .content(CONTENT)
            .build(),
        repo.getId());
    this.entityManager.flush();
    this.entityManager.clear();

    final var stored =
        this.jdbcTemplate.queryForObject(
            "select content from helm_oci_manifest where repo_id = ?", String.class, repo.getId());
    assertThat(stored).isEqualTo(CONTENT);
    assertThat(this.largeObjects()).isEqualTo(largeObjectsBefore);
    assertThat(
            this.helmOciManifestService
                .findByNameAndReference(repo.getId(), "payments", "1.0.0")
                .orElseThrow()
                .content())
        .isEqualTo(CONTENT);
  }
}
