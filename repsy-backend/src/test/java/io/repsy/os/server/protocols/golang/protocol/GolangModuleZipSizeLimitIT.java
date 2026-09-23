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
package io.repsy.os.server.protocols.golang.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * RPS-1119: a Go module zip publish is now spooled to a temporary file through {@code
 * repsy.golang.max-module-zip-size} instead of being read whole into memory ({@code
 * inputStream.readAllBytes()}). The limit is set low here so the zip that exceeds it stays small;
 * the production default (500 MiB, matching {@code MaxZipFile} in {@code golang.org/x/mod/zip}) is
 * exercised by {@link io.repsy.protocols.golang.shared.utils.GoModuleHashCalculatorTest} instead,
 * which does not need to hold hundreds of megabytes to prove the limit works.
 */
@TestPropertySource(properties = "repsy.golang.max-module-zip-size=4KB")
@DisplayName("Go module zip publish size limit (RPS-1119)")
class GolangModuleZipSizeLimitIT extends AbstractIntegrationTest {

  private static final String MODULE = "example.com/zipsize";
  private static final String VERSION = "v1.0.0";
  private static final String UPLOAD = "/{repo}/" + MODULE + "/@v/" + VERSION;

  /** A small, valid module zip: well under the 4KB limit configured for this class. */
  private static byte[] validModuleZip() {
    final var out = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(out)) {
      final var prefix = MODULE + "@" + VERSION + "/";

      zip.putNextEntry(new ZipEntry(prefix + "go.mod"));
      zip.write(("module " + MODULE + "\n\ngo 1.23\n").getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry(prefix + "zipsize.go"));
      zip.write("package zipsize\n".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return out.toByteArray();
  }

  @Test
  @DisplayName("a module zip under the limit is published and downloads byte for byte")
  void acceptsZipUnderLimit() throws Exception {
    final var repo = this.seedRepo(RepoType.GOLANG, uniqueRepoName("zipsize"));
    final var zip = validModuleZip();
    assertThat(zip.length).isLessThan(4 * 1024);

    this.mockMvc
        .perform(
            put(UPLOAD, repo.getName())
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .contentType("application/zip")
                .content(zip)
                .with(protocolPort()))
        .andExpect(status().isOk());

    final var downloaded =
        this.mockMvc
            .perform(
                get(UPLOAD + ".zip", repo.getName())
                    .header(AUTHORIZATION, this.adminProtocolBearerToken())
                    .with(protocolPort()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    assertThat(downloaded).isEqualTo(zip);
  }

  @Test
  @DisplayName(
      "a module zip over the limit is answered with 413 payloadTooLarge and stores nothing")
  void rejectsZipOverLimit() throws Exception {
    final var repo = this.seedRepo(RepoType.GOLANG, uniqueRepoName("zipsize"));
    final var token = this.adminProtocolBearerToken();

    // The declared Content-Length alone is over the limit, so this is refused before the body
    // would be parsed as a zip at all: it does not need to be a valid one.
    this.mockMvc
        .perform(
            put(UPLOAD, repo.getName())
                .header(AUTHORIZATION, token)
                .contentType("application/zip")
                .content(new byte[4 * 1024 + 1])
                .with(protocolPort()))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.msgId").value("payloadTooLarge"));

    assertThat(
            this.mockMvc
                .perform(
                    get("/{repo}/" + MODULE + "/@v/list", repo.getName())
                        .header(AUTHORIZATION, token)
                        .with(protocolPort()))
                .andReturn()
                .getResponse()
                .getContentAsString())
        .doesNotContain(VERSION);
  }
}
