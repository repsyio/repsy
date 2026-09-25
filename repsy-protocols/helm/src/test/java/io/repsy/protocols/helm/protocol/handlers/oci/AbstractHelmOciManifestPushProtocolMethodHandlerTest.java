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
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmFacade;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestPushForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestPushResult;
import io.repsy.protocols.helm.shared.utils.HelmConstants;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
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
    this.stubPush();

    final var response = this.push(context);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    final var form = ArgumentCaptor.forClass(HelmOciManifestPushForm.class);
    verify(this.facade).pushManifest(eq(context), form.capture(), any(byte[].class));
    assertThat(form.getValue().getChart().getName()).isEqualTo("payments");
    assertThat(form.getValue().getChart().getVersion()).isEqualTo("1.0.0");
    assertThat(form.getValue().getName()).isEqualTo("payments");
    assertThat(form.getValue().getReference()).isEqualTo("1.0.0");
  }

  @Test
  @DisplayName("reports the manifest file's usage once, after the push has succeeded")
  void reportsUsagesOnce() throws Exception {
    final var context = context("/payments/manifests/1.0.0");
    this.stubChartLayer("payments", "1.0.0");
    when(this.manifestInfo.digest()).thenReturn("sha256:" + "b".repeat(64));
    when(this.facade.pushManifest(eq(context), any(HelmOciManifestPushForm.class), any()))
        .thenThrow(new OptimisticLockingFailureException("lost"))
        .thenReturn(new HelmOciManifestPushResult(this.manifestInfo, BaseUsages.ofDisk(7)));

    this.push(context);

    final BaseUsages usages = context.getProperty("usages");
    assertThat(usages.getDiskUsage()).isEqualTo(7);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("retriedFailures")
  @DisplayName("repeats the whole chart, manifest and file write when it loses a race (RPS-1354)")
  void retriesTheWholePairOnALostRace(final RuntimeException failure) throws Exception {
    final var context = context("/payments/manifests/1.0.0");
    this.stubChartLayer("payments", "1.0.0");
    when(this.manifestInfo.digest()).thenReturn("sha256:" + "b".repeat(64));
    when(this.facade.pushManifest(eq(context), any(HelmOciManifestPushForm.class), any()))
        .thenThrow(failure)
        .thenThrow(failure)
        .thenReturn(new HelmOciManifestPushResult(this.manifestInfo, BaseUsages.ofDisk(0)));

    final var response = this.push(context);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    verify(this.facade, times(3))
        .pushManifest(eq(context), any(HelmOciManifestPushForm.class), any());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("retriedFailures")
  @DisplayName("gives up after three runs and lets the failure reach the error handler")
  void givesUpAfterThreeRuns(final RuntimeException failure) throws Exception {
    final var context = context("/payments/manifests/1.0.0");
    this.stubChartLayer("payments", "1.0.0");
    when(this.facade.pushManifest(eq(context), any(HelmOciManifestPushForm.class), any()))
        .thenThrow(failure);

    assertThatThrownBy(() -> this.push(context)).isSameAs(failure);

    verify(this.facade, times(3))
        .pushManifest(eq(context), any(HelmOciManifestPushForm.class), any());
    assertThat((BaseUsages) context.getProperty("usages")).as("nothing was charged").isNull();
  }

  @Test
  @DisplayName("does not repeat a failure that is not a lost race")
  void doesNotRetryOtherFailures() throws Exception {
    final var context = context("/payments/manifests/1.0.0");
    this.stubChartLayer("payments", "1.0.0");
    final var failure = new IllegalStateException("disk full");
    when(this.facade.pushManifest(eq(context), any(HelmOciManifestPushForm.class), any()))
        .thenThrow(failure);

    assertThatThrownBy(() -> this.push(context)).isSameAs(failure);

    verify(this.facade, times(1))
        .pushManifest(eq(context), any(HelmOciManifestPushForm.class), any());
  }

  static Stream<RuntimeException> retriedFailures() {
    return Stream.of(
        new DataIntegrityViolationException("unique index"),
        new OptimisticLockingFailureException("version check"),
        new CannotAcquireLockException("deadlock"));
  }

  private void stubPush() throws IOException {
    when(this.manifestInfo.digest()).thenReturn("sha256:" + "b".repeat(64));
    when(this.facade.pushManifest(any(ProtocolContext.class), any(), any()))
        .thenReturn(new HelmOciManifestPushResult(this.manifestInfo, BaseUsages.ofDisk(0)));
  }

  @Test
  @DisplayName("rejects a name over 255 characters before anything is looked up (RPS-1072)")
  void rejectsOverLongName() {
    final var name = "a".repeat(HelmConstants.MAX_OCI_MANIFEST_NAME_LENGTH + 1);
    final var context = context("/" + name + "/manifests/1.0.0");

    assertThatThrownBy(() -> this.push(context))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("manifestNameTooLong");

    verifyNoInteractions(this.facade);
  }

  @Test
  @DisplayName("rejects a reference over 255 characters before anything is looked up (RPS-1072)")
  void rejectsOverLongReference() {
    final var reference = "1".repeat(HelmConstants.MAX_OCI_MANIFEST_REFERENCE_LENGTH + 1);
    final var context = context("/payments/manifests/" + reference);

    assertThatThrownBy(() -> this.push(context))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("manifestReferenceTooLong");

    verifyNoInteractions(this.facade);
  }

  @Test
  @DisplayName("rejects a Content-Type over 255 characters before anything is looked up (RPS-1072)")
  void rejectsOverLongMediaType() {
    final var mediaType = "application/" + "x".repeat(HelmConstants.MAX_OCI_MEDIA_TYPE_LENGTH);
    final var context = context("/payments/manifests/1.0.0");

    assertThatThrownBy(() -> this.push(context, layer("\"" + LAYER_DIGEST + "\"", "10"), mediaType))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("manifestMediaTypeTooLong");

    verifyNoInteractions(this.facade);
  }

  @Test
  @DisplayName("accepts a name, reference and Content-Type of exactly 255 characters (RPS-1072)")
  void acceptsValuesAtTheLimit() throws Exception {
    final var name = "a".repeat(HelmConstants.MAX_OCI_MANIFEST_NAME_LENGTH);
    final var reference = "1".repeat(HelmConstants.MAX_OCI_MANIFEST_REFERENCE_LENGTH);
    final var mediaType =
        "application/"
            + "x".repeat(HelmConstants.MAX_OCI_MEDIA_TYPE_LENGTH - "application/".length());
    final var context = context("/" + name + "/manifests/" + reference);
    this.stubChartLayer(name, "1.0.0");
    this.stubPush();

    final var response = this.push(context, layer("\"" + LAYER_DIGEST + "\"", "10"), mediaType);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    final var form = ArgumentCaptor.forClass(HelmOciManifestPushForm.class);
    verify(this.facade).pushManifest(eq(context), form.capture(), any(byte[].class));
    assertThat(form.getValue().getName()).isEqualTo(name);
    assertThat(form.getValue().getReference()).isEqualTo(reference);
    assertThat(form.getValue().getMediaType()).isEqualTo(mediaType);
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

  @ParameterizedTest(name = "{0} -> {1}")
  @MethodSource("malformedManifests")
  @DisplayName("rejects a malformed manifest with a 400 and touches nothing")
  void rejectsMalformedManifest(final String manifest, final String msgId) throws Exception {
    final var context = context("/payments/manifests/1.0.0");

    assertThatThrownBy(() -> this.push(context, manifest))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(msgId);

    verify(this.facade).checkManifest(context, "payments", "1.0.0");
    verifyNoMoreInteractions(this.facade);
  }

  static Stream<Arguments> malformedManifests() {
    return Stream.of(
        arguments("not json", "manifestInvalidJson"),
        arguments("", "manifestInvalidJson"),
        arguments("[]", "manifestInvalidJson"),
        arguments("\"layers\"", "manifestInvalidJson"),
        arguments("{}", "manifestLayersMissing"),
        arguments("{\"layers\":null}", "manifestLayersMissing"),
        arguments("{\"layers\":{}}", "manifestLayersMissing"),
        arguments("{\"layers\":[]}", "manifestLayersMissing"),
        arguments("{\"layers\":[null]}", "manifestLayerInvalid"),
        arguments("{\"layers\":[{}]}", "manifestLayerInvalid"),
        arguments("{\"layers\":[{\"size\":10}]}", "manifestLayerInvalid"),
        arguments("{\"layers\":[{\"digest\":\"" + LAYER_DIGEST + "\"}]}", "manifestLayerInvalid"),
        arguments(layer("\"" + LAYER_DIGEST + "\"", "\"10\""), "manifestLayerInvalid"),
        arguments(layer("\"" + LAYER_DIGEST + "\"", "1.5"), "manifestLayerInvalid"),
        arguments(layer("\"" + LAYER_DIGEST + "\"", "-1"), "manifestLayerInvalid"),
        arguments(layer("\"" + LAYER_DIGEST + "\"", "null"), "manifestLayerInvalid"),
        arguments(layer("42", "10"), "manifestLayerInvalid"),
        arguments(layer("null", "10"), "manifestLayerInvalid"),
        arguments(layer("\"sha256:abc\"", "10"), "manifestLayerInvalid"),
        arguments(layer("\"../../etc/passwd\"", "10"), "manifestLayerInvalid"));
  }

  private static String layer(final String digest, final String size) {
    return "{\"layers\":[{\"digest\":%s,\"size\":%s}]}".formatted(digest, size);
  }

  private ResponseEntity<Object> push(final ProtocolContext context) throws Exception {
    return this.push(context, layer("\"" + LAYER_DIGEST + "\"", "10"));
  }

  private ResponseEntity<Object> push(final ProtocolContext context, final String manifest)
      throws Exception {
    return this.push(context, manifest, MANIFEST_TYPE);
  }

  private ResponseEntity<Object> push(
      final ProtocolContext context, final String manifest, final String mediaType)
      throws Exception {
    final var request = new MockHttpServletRequest("PUT", "/v2/repo/payments/manifests/1.0.0");
    request.setContentType(mediaType);
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
        final var entry = new TarArchiveEntry("chart/Chart.yaml");
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
