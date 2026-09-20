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
import io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciManifestMismatch;
import io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciManifestRepository;
import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestForm;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RPS-1038: manifests that a push accepted before RPS-978 stored under the path name while the
 * chart carries the {@code Chart.yaml} name.
 */
@DisplayName("Helm OCI manifest name repair (RPS-1038)")
class HelmOciManifestNameRepairServiceIT extends AbstractIntegrationTest {

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private HelmOciManifestNameRepairService repairService;
  @Autowired private HelmChartService helmChartService;
  @Autowired private HelmOciManifestService helmOciManifestService;
  @Autowired private HelmOciManifestRepository helmOciManifestRepository;
  @Autowired private HelmStorageService helmStorageService;

  private Repo helmRepo() {
    return this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));
  }

  private HelmChartInfo chart(final Repo repo, final String name, final String version) {
    return this.helmChartService.findOrCreate(
        HelmChartForm.builder()
            .name(name)
            .version(version)
            .description(name + " chart")
            .appVersion("1.0.0")
            .type("application")
            .digest(sha256(name + ":" + version))
            .size(10)
            .build(),
        repo.getId());
  }

  /** Stores the manifest row and, like a push does, its file. */
  private void manifest(
      final Repo repo,
      final HelmChartInfo chart,
      final String name,
      final String reference,
      final String content) {
    this.helmOciManifestService.save(
        HelmOciManifestForm.builder()
            .chartId(chart.id())
            .name(name)
            .reference(reference)
            .digest(sha256(content))
            .mediaType("application/vnd.oci.image.manifest.v1+json")
            .content(content)
            .build(),
        repo.getId());
    this.helmStorageService.saveManifest(
        repo.getId(), name, reference, content.getBytes(StandardCharsets.UTF_8), repo.getName());
    this.entityManager.flush();
    this.entityManager.clear();
  }

  private Path file(final Repo repo, final String name, final String reference) {
    return storageDirOf(repo).resolve("oci").resolve("manifests").resolve(name).resolve(reference);
  }

  private static String sha256(final String value) {
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName("reports only the manifests whose name differs from their chart's name")
  void findsOnlyMismatches() {
    final var repo = this.helmRepo();
    final var other = this.helmRepo();
    final var payments = this.chart(repo, "payments", "1.0.0");
    final var orders = this.chart(other, "orders", "1.0.0");
    this.manifest(repo, payments, "payments", "1.0.0", "{\"ok\":1}");
    this.manifest(repo, payments, "alias", "1.0.0", "{\"stray\":1}");
    this.manifest(other, orders, "orders", "1.0.0", "{\"ok\":2}");

    assertThat(this.repairService.findMismatches())
        .containsExactly(
            new HelmOciManifestMismatch(
                this.helmOciManifestRepository
                    .findByRepoIdAndNameAndReference(repo.getId(), "alias", "1.0.0")
                    .orElseThrow()
                    .getId(),
                repo.getId(),
                repo.getName(),
                "alias",
                "1.0.0",
                sha256("{\"stray\":1}"),
                "payments"));
  }

  @Test
  @DisplayName("re-keys the manifest and moves its file to the chart name")
  void rekeysManifestAndMovesFile() throws IOException {
    final var repo = this.helmRepo();
    final var chart = this.chart(repo, "real-name", "1.0.0");
    this.manifest(repo, chart, "alias", "1.0.0", "{\"stray\":1}");
    this.manifest(repo, chart, "alias", "latest", "{\"stray\":1}");
    assertThat(this.file(repo, "alias", "1.0.0")).exists();

    final var report = this.repairService.repair();

    assertThat(report.rekeyed()).isEqualTo(2);
    assertThat(report.duplicatesRemoved()).isZero();
    assertThat(report.conflicts()).isEmpty();
    this.entityManager.clear();

    assertThat(this.helmOciManifestService.listTagsByName(repo.getId(), "real-name"))
        .containsExactlyInAnyOrder("1.0.0", "latest");
    assertThat(this.helmOciManifestService.listTagsByName(repo.getId(), "alias")).isEmpty();
    final var moved =
        this.helmOciManifestService
            .findByNameAndReference(repo.getId(), "real-name", "1.0.0")
            .orElseThrow();
    assertThat(moved.chartId()).isEqualTo(chart.id());
    assertThat(moved.digest()).isEqualTo(sha256("{\"stray\":1}"));
    assertThat(moved.content()).isEqualTo("{\"stray\":1}");

    assertThat(Files.readString(this.file(repo, "real-name", "1.0.0"))).isEqualTo("{\"stray\":1}");
    assertThat(this.file(repo, "real-name", "latest")).exists();
    assertThat(this.file(repo, "alias", "1.0.0")).doesNotExist();
    assertThat(this.file(repo, "alias", "latest")).doesNotExist();
  }

  @Test
  @DisplayName("a second run finds nothing left to repair")
  void isIdempotent() {
    final var repo = this.helmRepo();
    this.manifest(repo, this.chart(repo, "real-name", "1.0.0"), "alias", "1.0.0", "{}");

    assertThat(this.repairService.repair().isEmpty()).isFalse();

    assertThat(this.repairService.findMismatches()).isEmpty();
    assertThat(this.repairService.repair().isEmpty()).isTrue();
  }

  @Test
  @DisplayName("removes a stray manifest that duplicates the one under the chart name")
  void removesDuplicateOfManifestUnderChartName() {
    final var repo = this.helmRepo();
    final var chart = this.chart(repo, "real-name", "1.0.0");
    this.manifest(repo, chart, "real-name", "1.0.0", "{\"same\":1}");
    this.manifest(repo, chart, "alias", "1.0.0", "{\"same\":1}");

    final var report = this.repairService.repair();

    assertThat(report.rekeyed()).isZero();
    assertThat(report.duplicatesRemoved()).isEqualTo(1);
    assertThat(report.conflicts()).isEmpty();
    this.entityManager.clear();

    assertThat(this.helmOciManifestService.listTagsByName(repo.getId(), "alias")).isEmpty();
    assertThat(this.helmOciManifestService.listTagsByName(repo.getId(), "real-name"))
        .containsExactly("1.0.0");
    assertThat(this.file(repo, "alias", "1.0.0")).doesNotExist();
    assertThat(this.file(repo, "real-name", "1.0.0")).exists();
  }

  @Test
  @DisplayName("leaves a stray manifest alone when the chart name holds a different one")
  void reportsConflictAndKeepsBothManifests() {
    final var repo = this.helmRepo();
    final var chart = this.chart(repo, "real-name", "1.0.0");
    this.manifest(repo, chart, "real-name", "1.0.0", "{\"current\":1}");
    this.manifest(repo, chart, "alias", "1.0.0", "{\"stray\":1}");

    final var report = this.repairService.repair();

    assertThat(report.rekeyed()).isZero();
    assertThat(report.duplicatesRemoved()).isZero();
    assertThat(report.conflicts())
        .singleElement()
        .satisfies(
            conflict -> {
              assertThat(conflict.name()).isEqualTo("alias");
              assertThat(conflict.chartName()).isEqualTo("real-name");
              assertThat(conflict.reference()).isEqualTo("1.0.0");
            });
    this.entityManager.clear();

    assertThat(
            this.helmOciManifestService
                .findByNameAndReference(repo.getId(), "real-name", "1.0.0")
                .orElseThrow()
                .content())
        .isEqualTo("{\"current\":1}");
    assertThat(
            this.helmOciManifestService
                .findByNameAndReference(repo.getId(), "alias", "1.0.0")
                .orElseThrow()
                .content())
        .isEqualTo("{\"stray\":1}");
    assertThat(this.file(repo, "alias", "1.0.0")).exists();
    // The conflict stays reported until an operator resolves it.
    assertThat(this.repairService.findMismatches()).hasSize(1);
  }

  @Test
  @DisplayName("two strays for one tag: the older is re-keyed, the other is handled against it")
  void twoStraysForTheSameTag() {
    final var repo = this.helmRepo();
    final var chart = this.chart(repo, "real-name", "1.0.0");
    this.manifest(repo, chart, "first", "1.0.0", "{\"same\":1}");
    this.manifest(repo, chart, "second", "1.0.0", "{\"same\":1}");
    this.manifest(repo, chart, "third", "1.0.0", "{\"other\":1}");

    final var report = this.repairService.repair();

    assertThat(report.rekeyed()).isEqualTo(1);
    assertThat(report.duplicatesRemoved()).isEqualTo(1);
    assertThat(report.conflicts())
        .singleElement()
        .satisfies(c -> assertThat(c.name()).isEqualTo("third"));
    this.entityManager.clear();

    assertThat(
            this.helmOciManifestService
                .findByNameAndReference(repo.getId(), "real-name", "1.0.0")
                .orElseThrow()
                .content())
        .isEqualTo("{\"same\":1}");
  }

  @Test
  @DisplayName("a manifest of another repo under the chart name is not a collision")
  void collisionIsScopedToTheRepo() {
    final var repo = this.helmRepo();
    final var other = this.helmRepo();
    this.manifest(
        other, this.chart(other, "real-name", "1.0.0"), "real-name", "1.0.0", "{\"other\":1}");
    this.manifest(repo, this.chart(repo, "real-name", "1.0.0"), "alias", "1.0.0", "{\"stray\":1}");

    final var report = this.repairService.repair();

    assertThat(report.rekeyed()).isEqualTo(1);
    assertThat(report.conflicts()).isEmpty();
  }

  @Test
  @DisplayName("finishes the repair even when the manifest file is already gone")
  void toleratesMissingManifestFile() throws IOException {
    final var repo = this.helmRepo();
    this.manifest(repo, this.chart(repo, "real-name", "1.0.0"), "alias", "1.0.0", "{}");
    Files.delete(this.file(repo, "alias", "1.0.0"));

    final var report = this.repairService.repair();

    assertThat(report.rekeyed()).isEqualTo(1);
    assertThat(this.file(repo, "real-name", "1.0.0")).exists();
  }
}
