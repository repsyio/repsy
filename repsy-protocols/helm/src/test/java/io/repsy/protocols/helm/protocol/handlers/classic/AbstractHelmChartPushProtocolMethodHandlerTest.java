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
package io.repsy.protocols.helm.protocol.handlers.classic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmProtocolFacade;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartMetadata;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractHelmChartPushProtocolMethodHandler")
class AbstractHelmChartPushProtocolMethodHandlerTest {

  @Mock private PathParser basePathParser;
  @Mock private HelmProtocolFacade<UUID> helmFacade;
  @Mock private HelmProtocolProvider provider;
  @Mock private HelmChartInfo chartInfo;

  private static class TestHandler extends AbstractHelmChartPushProtocolMethodHandler<UUID> {

    TestHandler(
        final PathParser basePathParser,
        final HelmProtocolFacade<UUID> helmFacade,
        final HelmProtocolProvider provider) {
      super(basePathParser, helmFacade, provider);
    }
  }

  private TestHandler handler() {
    return new TestHandler(this.basePathParser, this.helmFacade, this.provider);
  }

  private static byte[] chart(final String chartYaml) throws Exception {
    final var bytes = new ByteArrayOutputStream();
    try (final var gzip = new GZIPOutputStream(bytes);
        final var tar = new TarArchiveOutputStream(gzip)) {
      final var data = chartYaml.getBytes(StandardCharsets.UTF_8);
      final var entry = new TarArchiveEntry("payments/Chart.yaml");
      entry.setSize(data.length);
      tar.putArchiveEntry(entry);
      tar.write(data);
      tar.closeArchiveEntry();
    }
    return bytes.toByteArray();
  }

  private static MockHttpServletRequest upload(final byte[] chart) {
    final var request = new MockHttpServletRequest("POST", "/charts/api/charts");
    request.addPart(new MockPart("chart", "payments-1.0.0.tgz", chart));
    return request;
  }

  private static String sha256Of(final byte[] bytes) throws Exception {
    return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  @Test
  @DisplayName("stores the chart read from a spooled copy, with its digest and size")
  void storesSpooledChart() throws Exception {
    final var chart =
        chart(
            """
            name: payments
            version: 1.0.0
            description: Pays
            appVersion: "2"
            type: application
            apiVersion: v2
            dependencies:
              - name: postgresql
                version: 12.x.x
                repository: https://charts.example.com
                condition: postgresql.enabled
            """);
    final var stored = new AtomicReference<byte[]>();
    when(this.helmFacade.pushChart(any(), any(), any(), any(), anyLong()))
        .thenAnswer(
            invocation -> {
              stored.set(invocation.<InputStream>getArgument(3).readAllBytes());
              return this.chartInfo;
            });
    final var context = new ProtocolContext();

    final var response =
        this.handler().handle(context, upload(chart), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    final var metadata = ArgumentCaptor.forClass(HelmChartMetadata.class);
    verify(this.helmFacade)
        .pushChart(
            eq(context), metadata.capture(), eq(sha256Of(chart)), any(), eq((long) chart.length));
    assertThat(metadata.getValue().getName()).isEqualTo("payments");
    assertThat(metadata.getValue().getVersion()).isEqualTo("1.0.0");
    assertThat(metadata.getValue().getDescription()).isEqualTo("Pays");
    assertThat(metadata.getValue().getAppVersion()).isEqualTo("2");
    assertThat(metadata.getValue().getType()).isEqualTo("application");
    assertThat(metadata.getValue().getApiVersion()).isEqualTo("v2");
    assertThat(metadata.getValue().getDependencies())
        .isEqualTo(
            "[{\"name\":\"postgresql\",\"version\":\"12.x.x\","
                + "\"repository\":\"https://charts.example.com\","
                + "\"condition\":\"postgresql.enabled\"}]");
    assertThat(stored.get()).isEqualTo(chart);
  }

  @Test
  @DisplayName("passes no description or appVersion, and apiVersion v1, when Chart.yaml has none")
  void passesNoOptionalFields() throws Exception {
    final var chart = chart("name: payments\nversion: 1.0.0\n");

    this.handler().handle(new ProtocolContext(), upload(chart), new MockHttpServletResponse());

    final var metadata = ArgumentCaptor.forClass(HelmChartMetadata.class);
    verify(this.helmFacade).pushChart(any(), metadata.capture(), any(), any(), anyLong());
    assertThat(metadata.getValue().getDescription()).isNull();
    assertThat(metadata.getValue().getAppVersion()).isNull();
    assertThat(metadata.getValue().getApiVersion()).isEqualTo("v1");
    assertThat(metadata.getValue().getDependencies()).isNull();
  }

  @Test
  @DisplayName("answers 400 when there is no chart part")
  void answersBadRequestWithoutChartPart() throws Exception {
    final var response =
        this.handler()
            .handle(
                new ProtocolContext(),
                new MockHttpServletRequest("POST", "/charts/api/charts"),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isEqualTo("Missing 'chart' part");
    verify(this.helmFacade, never()).pushChart(any(), any(), any(), any(), anyLong());
  }

  @Test
  @DisplayName("answers 400 when the request is not multipart at all (RPS-1466)")
  void answersBadRequestForANonMultipartRequest() throws Exception {
    final var request = mock(HttpServletRequest.class);
    when(request.getPart("chart")).thenThrow(new ServletException("not multipart"));

    final var response =
        this.handler().handle(new ProtocolContext(), request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isEqualTo("Missing 'chart' part");
    verify(this.helmFacade, never()).pushChart(any(), any(), any(), any(), anyLong());
  }

  @Test
  @DisplayName("refuses a chart part with no byte in it with helmChartEmpty (RPS-1466)")
  void refusesAnEmptyChartPart() throws Exception {
    final var request = upload(new byte[0]);

    assertThatThrownBy(
            () ->
                this.handler()
                    .handle(new ProtocolContext(), request, new MockHttpServletResponse()))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("helmChartEmpty");

    verify(this.helmFacade, never()).pushChart(any(), any(), any(), any(), anyLong());
  }

  @Test
  @DisplayName("stores nothing when the chart has no valid Chart.yaml")
  void storesNothingForInvalidChart() throws Exception {
    final var request = upload(chart("- just\n- a list\n"));

    assertThatThrownBy(
            () ->
                this.handler()
                    .handle(new ProtocolContext(), request, new MockHttpServletResponse()))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("chartYamlInvalid");

    verify(this.helmFacade, never()).pushChart(any(), any(), any(), any(), anyLong());
  }
}
