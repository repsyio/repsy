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
import io.repsy.protocols.golang.shared.utils.GoModuleHashCalculator;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RPS-1118: hashing a module zip used to inflate every entry with no cap on the total inflated size
 * or the entry count, so a request thread could be pinned hashing a crafted zip for a long time.
 * This class exercises the entry-count cap ({@link GoModuleHashCalculator#MAX_ENTRY_COUNT}, 100000)
 * end to end, over the real wire protocol, with the production default still in effect: a zip of
 * many tiny entries is cheap to build and well under the {@code repsy.golang.max-module-zip-size}
 * spool limit, so it does not need a reduced test property the way {@link
 * GolangModuleZipSizeLimitIT} does.
 *
 * <p>The total-inflated-bytes cap ({@link GoModuleHashCalculator#MAX_INFLATED_BYTES}, 500 MiB) is
 * exercised only by {@link
 * io.repsy.protocols.golang.shared.utils.GoModuleHashCalculatorTest#refusesOversizedInflation()},
 * which calls the package-visible overload with a small limit: building a real zip that inflates
 * past 500 MiB is not practical to run as part of this suite.
 */
@DisplayName("Go module zip entry-count limit (RPS-1118)")
class GolangModuleZipBombIT extends AbstractIntegrationTest {

  private static final String MODULE = "example.com/zipbomb";
  private static final String VERSION = "v1.0.0";
  private static final String UPLOAD = "/{repo}/" + MODULE + "/@v/" + VERSION;

  /** A module zip with {@code fileCount} tiny, stored (uncompressed) entries plus a go.mod. */
  private static byte[] moduleZipWithFiles(final int fileCount) {
    final var out = new ByteArrayOutputStream();
    final var prefix = MODULE + "@" + VERSION + "/";

    try (final var zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry(prefix + "go.mod"));
      zip.write(("module " + MODULE + "\n\ngo 1.23\n").getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();

      for (var i = 0; i < fileCount; i++) {
        zip.putNextEntry(new ZipEntry(prefix + "f/" + i + ".txt"));
        zip.write('x');
        zip.closeEntry();
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return out.toByteArray();
  }

  @Test
  @DisplayName("refuses a module zip with more than 100000 files, storing nothing")
  void refusesZipWithTooManyFiles() throws Exception {
    final var repo = this.seedRepo(RepoType.GOLANG, uniqueRepoName("zipbomb"));
    final var token = this.adminProtocolBearerToken();
    final var zip = moduleZipWithFiles(GoModuleHashCalculator.MAX_ENTRY_COUNT + 1);

    this.mockMvc
        .perform(
            put(UPLOAD, repo.getName())
                .header(AUTHORIZATION, token)
                .contentType("application/zip")
                .content(zip)
                .with(protocolPort()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.msgId").value("moduleZipTooManyFiles"));

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
