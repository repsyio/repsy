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
package io.repsy.protocols.npm.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.handlers.NpmHandlerTestSupport.FixedBaseParser;
import io.repsy.protocols.npm.shared.audit.NpmAdvisory;
import io.repsy.protocols.npm.shared.audit.NpmAdvisorySource;
import io.repsy.protocols.npm.shared.audit.NpmSeverity;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("The npm audit handlers")
class AbstractNpmAuditProtocolMethodHandlersTest {

  private static final String BULK = "/-/npm/v1/security/advisories/bulk";
  private static final String AUDITS = "/-/npm/v1/security/audits";
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  @Mock private NpmAdvisorySource<UUID> source;
  @Mock private NpmProtocolProvider provider;

  private static class Bulk extends AbstractNpmAuditBulkProtocolMethodHandler<UUID> {
    private final long limit;

    Bulk(
        final PathParser base,
        final NpmAdvisorySource<UUID> source,
        final NpmProtocolProvider p,
        final long limit) {
      super(base, source, MAPPER, p);
      this.limit = limit;
    }

    @Override
    protected long maxAuditBodyBytes() {
      return this.limit;
    }
  }

  private static class Legacy extends AbstractNpmAuditLegacyProtocolMethodHandler<UUID> {
    Legacy(
        final PathParser base, final NpmAdvisorySource<UUID> source, final NpmProtocolProvider p) {
      super(base, source, MAPPER, p);
    }
  }

  private Bulk bulk(final long limit) {
    return new Bulk(new FixedBaseParser(BULK, true), this.source, this.provider, limit);
  }

  private Legacy legacy(final String relativePath) {
    return new Legacy(new FixedBaseParser(relativePath, true), this.source, this.provider);
  }

  private static MockHttpServletRequest post(final String path, final byte[] body) {
    final var request = NpmHandlerTestSupport.request("POST", "/npm" + path);
    request.setContent(body);
    return request;
  }

  private static byte[] gzip(final String text) throws IOException {
    final var bytes = new ByteArrayOutputStream();

    try (var out = new GZIPOutputStream(bytes)) {
      out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    return bytes.toByteArray();
  }

  private static NpmAdvisory advisory() {
    return new NpmAdvisory(
        1,
        "lodash",
        "CVE-1",
        "https://x",
        NpmSeverity.HIGH,
        List.of("4.17.20"),
        ">=4.17.21",
        List.of("CVE-1"),
        null,
        "",
        "",
        "",
        null,
        null,
        Instant.EPOCH,
        null);
  }

  private static ResponseEntity<Object> handle(
      final AbstractNpmAuditProtocolMethodHandler<UUID> handler,
      final String path,
      final MockHttpServletRequest request)
      throws Exception {
    return handler.handle(
        NpmHandlerTestSupport.context(path), request, new MockHttpServletResponse());
  }

  @Test
  @DisplayName("register for POST and read; writeOperation is present and false")
  void metadata() {
    final var bulk = this.bulk(1024);
    final var legacy = this.legacy(AUDITS);

    verify(this.provider).registerMethodHandler(bulk);
    verify(this.provider).registerMethodHandler(legacy);
    for (final var handler : List.of(bulk, legacy)) {
      assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.POST);
      assertThat(handler.getProperties())
          .containsEntry("permission", Permission.READ)
          .containsEntry("writeOperation", false)
          .containsEntry("skipUsagePostProcessor", true);
    }
  }

  @Test
  @DisplayName("bulk claims POST advisories/bulk and nothing else")
  void bulkParser() {
    final var parser = this.bulk(1024).getPathParser();

    assertThat(parser.parse(NpmHandlerTestSupport.request("POST", "/npm" + BULK))).isPresent();
    assertThat(parser.parse(NpmHandlerTestSupport.request("GET", "/npm" + BULK))).isEmpty();
    assertThat(parser.parse(NpmHandlerTestSupport.request("POST", "/npm" + AUDITS))).isEmpty();
  }

  @Test
  @DisplayName("legacy claims POST audits and audits/quick and nothing else")
  void legacyParser() {
    final var audits = this.legacy(AUDITS).getPathParser();
    final var quick = this.legacy(AUDITS + "/quick").getPathParser();

    assertThat(audits.parse(NpmHandlerTestSupport.request("POST", "/npm" + AUDITS))).isPresent();
    assertThat(quick.parse(NpmHandlerTestSupport.request("POST", "/npm" + AUDITS + "/quick")))
        .isPresent();
    assertThat(audits.parse(NpmHandlerTestSupport.request("POST", "/npm" + AUDITS + "/other")))
        .isEmpty();
    assertThat(audits.parse(NpmHandlerTestSupport.request("POST", "/npm" + BULK))).isEmpty();
    assertThat(audits.parse(NpmHandlerTestSupport.request("GET", "/npm" + AUDITS))).isEmpty();
  }

  @Test
  @DisplayName("bulk answers the advisories of the requested versions, plain or gzip")
  void bulkAnswers() throws Exception {
    when(this.source.findAdvisories(any(), eq(Map.of("lodash", Set.of("4.17.20")))))
        .thenReturn(List.of(advisory()));
    final var body = "{\"lodash\":[\"4.17.20\"]}";

    final var plain =
        handle(this.bulk(1 << 20), BULK, post(BULK, body.getBytes(StandardCharsets.UTF_8)));
    final var compressed = post(BULK, gzip(body));
    compressed.addHeader(HttpHeaders.CONTENT_ENCODING, "gzip");
    final var zipped = handle(this.bulk(1 << 20), BULK, compressed);

    for (final var response : List.of(plain, zipped)) {
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
      assertThat(MAPPER.writeValueAsString(response.getBody()))
          .contains("\"lodash\":[{\"id\":1")
          .contains("\"vulnerable_versions\":\"4.17.20\"");
    }
  }

  @Test
  @DisplayName("bulk answers {} for an empty body")
  void bulkEmptyBody() throws Exception {
    when(this.source.findAdvisories(any(), eq(Map.of()))).thenReturn(List.of());

    final var response = handle(this.bulk(1024), BULK, post(BULK, new byte[0]));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(MAPPER.writeValueAsString(response.getBody())).isEqualTo("{}");
  }

  @Test
  @DisplayName("bulk answers 400 for a body that is not a JSON object")
  void bulkBadJson() throws Exception {
    for (final var bad : List.of("{not json", "[]", "7")) {
      final var response =
          handle(this.bulk(1024), BULK, post(BULK, bad.getBytes(StandardCharsets.UTF_8)));

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(response.getBody()).isEqualTo(Map.of("error", "invalid audit request body"));
    }
    verify(this.source, never()).findAdvisories(any(), any());
  }

  @Test
  @DisplayName("bulk answers 400 for a broken gzip body")
  void bulkBrokenGzip() throws Exception {
    final var request = post(BULK, "not gzip at all".getBytes(StandardCharsets.UTF_8));
    request.addHeader(HttpHeaders.CONTENT_ENCODING, "gzip");

    assertThat(handle(this.bulk(1024), BULK, request).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  @DisplayName("bulk answers 413 for a body that inflates past the limit")
  void bulkTooLarge() throws Exception {
    final var body = "{\"a\":[\"" + "1".repeat(500) + "\"]}";
    final var request = post(BULK, gzip(body));
    request.addHeader(HttpHeaders.CONTENT_ENCODING, "gzip");

    final var response = handle(this.bulk(100), BULK, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    assertThat(response.getBody()).isEqualTo(Map.of("error", "audit request body too large"));
  }

  @Test
  @DisplayName("the body limit is 64 MiB unless a subclass lowers it")
  void defaultLimit() {
    assertThat(
            new Legacy(new FixedBaseParser(AUDITS, true), this.source, this.provider)
                .maxAuditBodyBytes())
        .isEqualTo(64L * 1024 * 1024);
  }

  @Test
  @DisplayName("legacy answers the report of the dependency tree, on audits and audits/quick")
  void legacyAnswers() throws Exception {
    when(this.source.findAdvisories(
            any(), eq(Map.of("a", Set.of("1.0.0"), "lodash", Set.of("4.17.20")))))
        .thenReturn(List.of(advisory()));
    final var tree =
        "{\"dependencies\":{\"a\":{\"version\":\"1.0.0\",\"dependencies\":{\"lodash\":{\"version\":\"4.17.20\"}}}}}";

    final var plain =
        handle(this.legacy(AUDITS), AUDITS, post(AUDITS, tree.getBytes(StandardCharsets.UTF_8)));
    final var compressed = post(AUDITS + "/quick", gzip(tree));
    compressed.addHeader(HttpHeaders.CONTENT_ENCODING, "gzip");
    final var quick = handle(this.legacy(AUDITS + "/quick"), AUDITS + "/quick", compressed);

    for (final var response : List.of(plain, quick)) {
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(MAPPER.writeValueAsString(response.getBody()))
          .contains("\"paths\":[\"a>lodash\"]")
          .contains(
              "\"vulnerabilities\":{\"info\":0,\"low\":0,\"moderate\":0,\"high\":1,\"critical\":0}")
          .contains("\"totalDependencies\":2");
    }
  }

  @Test
  @DisplayName("legacy of a tree without packages does not ask for advisories and has its metadata")
  void legacyEmptyTree() throws Exception {
    final var response =
        handle(this.legacy(AUDITS), AUDITS, post(AUDITS, "{}".getBytes(StandardCharsets.UTF_8)));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(MAPPER.writeValueAsString(response.getBody()))
        .contains("\"advisories\":{}")
        .contains(
            "\"vulnerabilities\":{\"info\":0,\"low\":0,\"moderate\":0,\"high\":0,\"critical\":0}");
    verify(this.source, never()).findAdvisories(any(), any());
  }

  @Test
  @DisplayName("legacy answers 400 for a body that is not a JSON object")
  void legacyBadJson() throws Exception {
    final var response =
        handle(this.legacy(AUDITS), AUDITS, post(AUDITS, "[".getBytes(StandardCharsets.UTF_8)));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }
}
