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
package io.repsy.protocols.helm.protocol.handlers.oci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmFacade;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestInfo;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractHelmOciManifestPushProtocolMethodHandler")
class AbstractHelmOciManifestPushProtocolMethodHandlerTest {

  private static final String MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json";
  private static final String LAYER_DIGEST = "sha256:" + "a".repeat(64);
  private static final UUID REPO_ID = UUID.randomUUID();

  @Mock private PathParser basePathParser;
  @Mock private HelmFacade<UUID> facade;
  @Mock private HelmProtocolProvider provider;
  @Mock private HelmChartInfo chartInfo;
  @Mock private HelmOciManifestInfo manifestInfo;

  private AbstractHelmOciManifestPushProtocolMethodHandler<UUID> handler;

  @BeforeEach
  void setUp() {
    this.handler = new TestHandler(this.basePathParser, this.facade, this.provider);
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  static class TestHandler extends AbstractHelmOciManifestPushProtocolMethodHandler<UUID> {

    TestHandler(final PathParser p, final HelmFacade<UUID> f, final HelmProtocolProvider pr) {
      super(p, f, pr);
    }
  }

  @Test
  @DisplayName("rejects a path name that differs from the Chart.yaml name and stores nothing")
  void rejectsDifferingName() throws Exception {
    final var context = context("/alias/manifests/1.0.0");
    this.stubChartLayer("real-name", "1.0.0");

    assertThatThrownBy(() -> this.push(context))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("chartNameMismatch");

    verify(this.facade).checkManifest(context, "alias", "1.0.0");
    verify(this.facade).getBlob(context, LAYER_DIGEST);
    verifyNoMoreInteractions(this.facade);
  }

  @Test
  @DisplayName("rejects a path name that only differs in case")
  void rejectsDifferingCase() throws Exception {
    final var context = context("/Payments/manifests/1.0.0");
    this.stubChartLayer("payments", "1.0.0");

    assertThatThrownBy(() -> this.push(context))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("chartNameMismatch");
  }

  @Test
  @DisplayName("accepts a path name equal to the Chart.yaml name and stores the chart and manifest")
  void acceptsMatchingName() throws Exception {
    final var context = context("/payments/manifests/1.0.0");
    this.stubChartLayer("payments", "1.0.0");
    when(this.chartInfo.id()).thenReturn(UUID.randomUUID());
    when(this.manifestInfo.digest()).thenReturn("sha256:" + "b".repeat(64));
    when(this.facade.findOrCreateChart(any(HelmChartForm.class), eq(REPO_ID)))
        .thenReturn(this.chartInfo);
    when(this.facade.findOrCreateManifest(any(HelmOciManifestForm.class), eq(REPO_ID)))
        .thenReturn(this.manifestInfo);

    final var response = this.push(context);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    final var chartForm = ArgumentCaptor.forClass(HelmChartForm.class);
    verify(this.facade).findOrCreateChart(chartForm.capture(), eq(REPO_ID));
    assertThat(chartForm.getValue().getName()).isEqualTo("payments");
    assertThat(chartForm.getValue().getVersion()).isEqualTo("1.0.0");
    final var manifestForm = ArgumentCaptor.forClass(HelmOciManifestForm.class);
    verify(this.facade).findOrCreateManifest(manifestForm.capture(), eq(REPO_ID));
    assertThat(manifestForm.getValue().getName()).isEqualTo("payments");
    assertThat(manifestForm.getValue().getReference()).isEqualTo("1.0.0");
    verify(this.facade).pushManifest(eq(context), eq("payments"), eq("1.0.0"), any(byte[].class));
  }

  @Test
  @DisplayName("rejects an existing version with a fixed msgId that carries no name or version")
  void rejectsExistingVersionWithFixedMsgId() {
    final var context = context("/payments/manifests/1.0.0");
    when(this.facade.checkManifest(context, "payments", "1.0.0"))
        .thenReturn(Optional.of(this.manifestInfo));

    assertThatThrownBy(() -> this.push(context))
        .isInstanceOf(ItemAlreadyExistException.class)
        .hasMessage("chartAlreadyExists");

    verifyNoMoreInteractions(this.facade);
  }

  private ResponseEntity<Object> push(final ProtocolContext context) throws Exception {
    final var manifest =
        ("{\"schemaVersion\":2,\"layers\":[{\"digest\":\"%s\",\"size\":10}]}")
            .formatted(LAYER_DIGEST);
    final var request = new MockHttpServletRequest("PUT", "/v2/repo/payments/manifests/1.0.0");
    request.setContentType(MANIFEST_TYPE);
    request.setContent(manifest.getBytes(StandardCharsets.UTF_8));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    return this.handler.handle(context, request, new MockHttpServletResponse());
  }

  private void stubChartLayer(final String chartName, final String version) throws IOException {
    when(this.facade.getBlob(any(ProtocolContext.class), eq(LAYER_DIGEST)))
        .thenReturn(new ByteArrayResource(chartArchive(chartName, version)));
  }

  private static ProtocolContext context(final String relativePath) {
    final var repoInfo =
        BaseRepoInfo.<UUID>builder().id(REPO_ID).storageKey(REPO_ID).name("helm").build();
    final var urlProps =
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName("helm")
            .relativePath(new RelativePath(relativePath))
            .repoInfo(repoInfo)
            .build();
    final var context = new ProtocolContext();
    context.addProperty("urlProperties", urlProps);
    return context;
  }

  private static byte[] chartArchive(final String chartName, final String version) {
    final var chartYaml = "apiVersion: v2\nname: %s\nversion: %s\n".formatted(chartName, version);
    try {
      final var bytes = new ByteArrayOutputStream();
      try (final var gzip = new GZIPOutputStream(bytes);
          final var tar = new TarArchiveOutputStream(gzip)) {
        final var data = chartYaml.getBytes(StandardCharsets.UTF_8);
        final var entry = new TarArchiveEntry(chartName + "/Chart.yaml");
        entry.setSize(data.length);
        tar.putArchiveEntry(entry);
        tar.write(data);
        tar.closeArchiveEntry();
      }
      return bytes.toByteArray();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
