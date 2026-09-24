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
package io.repsy.protocols.npm.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.protocols.shared.utils.EntryTooLargeException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("NpmAuditRequestReader")
class NpmAuditRequestReaderTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final long MAX = 1024 * 1024;
  private static final String BODY =
      "{\"lodash\":[\"4.17.20\",\"4.17.21\"],\"left-pad\":[\"1.3.0\"]}";

  private static byte[] gzip(final String text) throws IOException {
    final var bytes = new ByteArrayOutputStream();

    try (var out = new GZIPOutputStream(bytes)) {
      out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    return bytes.toByteArray();
  }

  private static tools.jackson.databind.JsonNode read(
      final byte[] body, final String encoding, final long max) throws IOException {
    return NpmAuditRequestReader.read(new ByteArrayInputStream(body), encoding, MAPPER, max);
  }

  private static byte[] plain(final String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("reads a plain JSON body, which pnpm and yarn send")
  void plainBody() throws Exception {
    assertThat(read(plain(BODY), null, MAX).get("lodash")).hasSize(2);
  }

  @Test
  @DisplayName("inflates a body that declares gzip, which npm and bun send")
  void gzipWithHeader() throws Exception {
    assertThat(read(gzip(BODY), "gzip", MAX).get("left-pad").get(0).asString()).isEqualTo("1.3.0");
    assertThat(read(gzip(BODY), "GZIP, identity", MAX).has("lodash")).isTrue();
  }

  @Test
  @DisplayName("inflates a body that starts with the gzip magic number without the header")
  void gzipWithoutHeader() throws Exception {
    assertThat(read(gzip(BODY), null, MAX).has("lodash")).isTrue();
  }

  @Test
  @DisplayName("reads a plain body that wrongly declares gzip as an error")
  void headerWithoutGzip() {
    assertThatThrownBy(() -> read(plain(BODY), "gzip", MAX))
        .isInstanceOf(InvalidAuditRequestException.class);
  }

  @Test
  @DisplayName("refuses a truncated gzip body")
  void truncatedGzip() throws Exception {
    final var gzipped = gzip(BODY);
    final var truncated = java.util.Arrays.copyOf(gzipped, gzipped.length / 2);

    assertThatThrownBy(() -> read(truncated, "gzip", MAX))
        .isInstanceOf(InvalidAuditRequestException.class);
  }

  @Test
  @DisplayName("an empty body, plain or with the gzip header, is an empty object")
  void emptyBody() throws Exception {
    assertThat(read(new byte[0], null, MAX).isObject()).isTrue();
    assertThat(read(new byte[0], "gzip", MAX)).isEmpty();
    assertThat(read(gzip(""), "gzip", MAX)).isEmpty();
  }

  @Test
  @DisplayName("refuses a body that is not JSON")
  void invalidJson() {
    assertThatThrownBy(() -> read(plain("{not json"), null, MAX))
        .isInstanceOf(InvalidAuditRequestException.class)
        .hasMessageContaining("JSON");
  }

  @Test
  @DisplayName("refuses JSON that is not an object")
  void notAnObject() {
    assertThatThrownBy(() -> read(plain("[1,2]"), null, MAX))
        .isInstanceOf(InvalidAuditRequestException.class)
        .hasMessageContaining("object");
    assertThatThrownBy(() -> read(plain("\"text\""), null, MAX))
        .isInstanceOf(InvalidAuditRequestException.class);
  }

  @Test
  @DisplayName("refuses a body that is larger than the limit, plain or inflated")
  void tooLarge() throws Exception {
    final var big = "{\"a\":[\"" + "1".repeat(2000) + "\"]}";

    assertThatThrownBy(() -> read(plain(big), null, 100))
        .isInstanceOf(EntryTooLargeException.class);
    assertThatThrownBy(() -> read(gzip(big), "gzip", 100))
        .isInstanceOf(EntryTooLargeException.class);
    // The compressed body is far smaller than what it inflates to: only the inflated size counts.
    assertThat(gzip(big).length).isLessThan(200);
  }

  @Test
  @DisplayName("takes the versions of a bulk body and skips what is not an array of strings")
  void bulkVersions() throws Exception {
    final var body =
        read(
            plain(
                "{\"a\":[\"1.0.0\",2,null,\"1.0.0\",\"1.1.0\"],\"b\":\"1.0.0\",\"c\":{},\"d\":[]}"),
            null,
            MAX);

    final var versions = NpmAuditRequestReader.bulkVersionsByName(body);

    assertThat(versions).containsOnlyKeys("a", "d");
    assertThat(versions.get("a")).containsExactly("1.0.0", "1.1.0");
    assertThat(versions.get("d")).isEmpty();
  }

  @Test
  @DisplayName("stops at the most package names")
  void capsThePackageNames() throws Exception {
    final var json = new StringBuilder("{");
    for (var i = 0; i < NpmAuditRequestReader.MAX_PACKAGE_NAMES + 5; i++) {
      json.append(i == 0 ? "" : ",").append("\"p").append(i).append("\":[\"1.0.0\"]");
    }
    json.append('}');

    final var versions =
        NpmAuditRequestReader.bulkVersionsByName(read(plain(json.toString()), null, MAX * 10));

    assertThat(versions).hasSize(NpmAuditRequestReader.MAX_PACKAGE_NAMES);
  }
}
