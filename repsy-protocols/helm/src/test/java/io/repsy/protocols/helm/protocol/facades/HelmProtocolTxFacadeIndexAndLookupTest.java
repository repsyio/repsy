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
package io.repsy.protocols.helm.protocol.facades;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
import io.repsy.protocols.helm.shared.chart.services.AbstractHelmChartFilesService;
import io.repsy.protocols.helm.shared.chart.services.ChartService;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestInfo;
import io.repsy.protocols.helm.shared.oci.services.OciBlobService;
import io.repsy.protocols.helm.shared.oci.services.OciManifestService;
import io.repsy.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

/**
 * Pins the classic index and the OCI lookups of {@link AbstractHelmProtocolTxFacade}, the part its
 * other test leaves open. RPS-2061: written before the facade is split by concern.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractHelmProtocolTxFacade index and OCI lookups")
class HelmProtocolTxFacadeIndexAndLookupTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "charts";
  private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05Z");

  @Mock private HelmStorageService<UUID> helmStorageService;
  @Mock private ChartService<UUID> chartService;
  @Mock private OciBlobService<UUID> ociBlobService;
  @Mock private OciManifestService<UUID> ociManifestService;
  @Mock private AbstractHelmChartFilesService<UUID> chartFilesService;

  private TestFacade facade;
  private ProtocolContext context;

  private static class TestFacade extends AbstractHelmProtocolTxFacade<UUID> {

    TestFacade(
        final HelmStorageService<UUID> helmStorageService,
        final ChartService<UUID> chartService,
        final OciBlobService<UUID> ociBlobService,
        final OciManifestService<UUID> ociManifestService,
        final AbstractHelmChartFilesService<UUID> chartFilesService) {
      super(
          helmStorageService, chartService, ociBlobService, ociManifestService, chartFilesService);
    }
  }

  private record Chart(
      String name, String version, String dependencies, String digest, String description)
      implements HelmChartInfo {

    @Override
    public UUID id() {
      return UUID.randomUUID();
    }

    @Override
    public String appVersion() {
      return "1.0";
    }

    @Override
    public String type() {
      return "application";
    }

    @Override
    public String apiVersion() {
      return "v2";
    }

    @Override
    public long size() {
      return 10;
    }

    @Override
    public Instant createdAt() {
      return CREATED;
    }

    @Override
    public Instant lastUpdatedAt() {
      return CREATED;
    }
  }

  @BeforeEach
  void setUp() {
    this.facade =
        new TestFacade(
            this.helmStorageService,
            this.chartService,
            this.ociBlobService,
            this.ociManifestService,
            this.chartFilesService);

    final var repoInfo = new BaseRepoInfo<UUID>();
    repoInfo.setId(REPO_ID);
    repoInfo.setStorageKey(REPO_ID);
    repoInfo.setName(REPO_NAME);

    this.context = new ProtocolContext();
    this.context.addProperty(
        "urlProperties",
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName(REPO_NAME)
            .relativePath(new RelativePath("/"))
            .repoInfo(repoInfo)
            .build());
  }

  @Test
  @DisplayName("generateIndex() lists charts by name, the versions of a chart highest first")
  void generateIndexOrdersChartsAndVersions() {
    when(this.chartService.findAllByRepoId(REPO_ID))
        .thenReturn(
            List.of(
                new Chart("zeta", "1.0.0", null, "sha256:z", "z"),
                new Chart("alpha", "1.2.0", null, "sha256:a12", "a"),
                new Chart("alpha", "1.10.0", "[{\"name\":\"redis\"}]", "sha256:a110", null),
                new Chart("alpha", "1.9.0", "  ", "sha256:a19", "a")));

    final var index = this.facade.generateIndex(this.context);

    assertThat(index.getApiVersion()).isEqualTo("v1");
    assertThat(index.getGenerated()).isNotBlank();
    assertThat(index.getEntries().keySet()).containsExactly("alpha", "zeta");
    final var alpha = index.getEntries().get("alpha");
    assertThat(alpha).extracting("version").containsExactly("1.10.0", "1.9.0", "1.2.0");
    assertThat(alpha.get(0).getUrls()).containsExactly("charts/alpha-1.10.0.tgz");
    assertThat(alpha.get(0).getDigest()).isEqualTo("sha256:a110");
    assertThat(alpha.get(0).getCreated()).isEqualTo(CREATED.toString());
    assertThat(alpha.get(0).getDependencies()).containsExactly(java.util.Map.of("name", "redis"));
    assertThat(alpha.get(1).getDependencies()).isNull();
    assertThat(alpha.get(0).getApiVersion()).isEqualTo("v2");
  }

  @Test
  @DisplayName("generateIndex() leaves out the dependencies of a row that no longer read back")
  void generateIndexSurvivesUnreadableDependencies() {
    when(this.chartService.findAllByRepoId(REPO_ID))
        .thenReturn(List.of(new Chart("alpha", "1.0.0", "{not json", "sha256:a", "a")));

    final var index = this.facade.generateIndex(this.context);

    assertThat(index.getEntries().get("alpha")).hasSize(1);
    assertThat(index.getEntries().get("alpha").get(0).getDependencies()).isNull();
  }

  @Test
  @DisplayName("checkBlob() finds a blob only when its row and its file both exist")
  void checkBlobNeedsRowAndFile() {
    final var blob = mock(HelmOciBlobInfo.class);
    when(this.ociBlobService.findByDigest(REPO_ID, "sha256:has")).thenReturn(Optional.of(blob));
    when(this.ociBlobService.findByDigest(REPO_ID, "sha256:nofile")).thenReturn(Optional.of(blob));
    when(this.ociBlobService.findByDigest(REPO_ID, "sha256:norow")).thenReturn(Optional.empty());
    when(this.helmStorageService.blobExists(REPO_ID, "sha256:has", REPO_NAME)).thenReturn(true);
    when(this.helmStorageService.blobExists(REPO_ID, "sha256:nofile", REPO_NAME)).thenReturn(false);

    assertThat(this.facade.checkBlob(this.context, "sha256:has")).containsSame(blob);
    assertThat(this.facade.checkBlob(this.context, "sha256:nofile")).isEmpty();
    assertThat(this.facade.checkBlob(this.context, "sha256:norow")).isEmpty();
  }

  @Test
  @DisplayName("getBlob() answers the file, or blobNotFound")
  void getBlobAnswersTheFileOrNotFound() throws Exception {
    final var resource = new ByteArrayResource(new byte[3]);
    when(this.helmStorageService.findBlob(REPO_ID, "sha256:has", REPO_NAME))
        .thenReturn(Optional.of(resource));
    when(this.helmStorageService.findBlob(REPO_ID, "sha256:no", REPO_NAME))
        .thenReturn(Optional.empty());

    assertThat(this.facade.getBlob(this.context, "sha256:has")).isSameAs(resource);
    assertThatThrownBy(() -> this.facade.getBlob(this.context, "sha256:no"))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("blobNotFound");
  }

  @Test
  @DisplayName("checkManifest() and getManifest() look the manifest up by name and reference")
  void manifestLookups() throws Exception {
    final var manifest = mock(HelmOciManifestInfo.class);
    when(this.ociManifestService.findByNameAndReference(REPO_ID, "app", "1.0.0"))
        .thenReturn(Optional.of(manifest));
    when(this.ociManifestService.findByNameAndReference(REPO_ID, "app", "2.0.0"))
        .thenReturn(Optional.empty());

    assertThat(this.facade.checkManifest(this.context, "app", "1.0.0")).containsSame(manifest);
    assertThat(this.facade.checkManifest(this.context, "app", "2.0.0")).isEmpty();
    assertThat(this.facade.getManifest(this.context, "app", "1.0.0")).isSameAs(manifest);
    assertThatThrownBy(() -> this.facade.getManifest(this.context, "app", "2.0.0"))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("manifestNotFound");
  }

  @Test
  @DisplayName("findChartByNameAndVersion() and getOrCreateBlob() delegate to the services")
  void delegatingLookups() {
    final var chart = new Chart("app", "1.0.0", null, "sha256:a", "a");
    when(this.chartService.findOptionalByNameAndVersion(REPO_ID, "app", "1.0.0"))
        .thenReturn(Optional.of(chart));
    final var form = HelmOciBlobForm.builder().digest("sha256:b").size(1).mediaType("x").build();
    final var blob = mock(HelmOciBlobInfo.class);
    when(this.ociBlobService.getOrCreate(eq(form), any())).thenReturn(blob);

    assertThat(this.facade.findChartByNameAndVersion(this.context, "app", "1.0.0"))
        .containsSame(chart);
    assertThat(this.facade.getOrCreateBlob(form, REPO_ID)).isSameAs(blob);
  }

  @Test
  @DisplayName("listTags() drops digest references, sorts lexically and names <repo>/<chart>")
  void listTagsFiltersSortsAndPrefixesTheName() {
    when(this.ociManifestService.listTagsByName(REPO_ID, "app"))
        .thenReturn(List.of("2.0.0", "sha256:abc", "1.0.0", "1.10.0"));

    final var tags = this.facade.listTags(this.context, "app");

    assertThat(tags.getName()).isEqualTo("charts/app");
    assertThat(tags.getTags()).containsExactly("1.0.0", "1.10.0", "2.0.0");
  }
}
