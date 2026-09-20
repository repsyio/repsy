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

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.protocols.golang.shared.utils.GoModuleZipReader;
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
 * RPS-1054: a Go module upload reads the {@code go.mod} out of the module zip. A zip whose {@code
 * go.mod} inflates to gigabytes used to be buffered whole, so a small upload could exhaust the heap
 * of the instance. The {@code go.mod} is now capped, and an oversized one is answered with a 400
 * and stores nothing.
 */
@DisplayName("Go module upload caps the size of the go.mod (RPS-1054)")
class GolangPublishGoModSizeIT extends AbstractIntegrationTest {

  private static final String MODULE = "example.com/gomodsize";
  private static final String VERSION = "v1.0.0";
  private static final String UPLOAD = "/{repo}/" + MODULE + "/@v/" + VERSION;
  private static final String VERSION_LIST = "/{repo}/" + MODULE + "/@v/list";
  private static final String HEAD = "module " + MODULE + "\n\ngo 1.23\n//";

  /** A module zip whose go.mod is exactly {@code goModBytes} long. */
  private static byte[] moduleZip(final long goModBytes) {
    final var goMod = (HEAD + "#".repeat((int) goModBytes - HEAD.length()));
    final var out = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(out)) {
      final var prefix = MODULE + "@" + VERSION + "/";

      zip.putNextEntry(new ZipEntry(prefix + "go.mod"));
      zip.write(goMod.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry(prefix + "gomodsize.go"));
      zip.write("package gomodsize\n".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return out.toByteArray();
  }

  private String versionList(final String repoName, final String token) throws Exception {
    return this.mockMvc
        .perform(get(VERSION_LIST, repoName).header(AUTHORIZATION, token).with(protocolPort()))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  @DisplayName("refuses a go.mod over the limit with a 400 and stores nothing")
  void refusesOversizedGoMod() throws Exception {
    final var repo = this.seedRepo(RepoType.GOLANG, uniqueRepoName("rps1054"));
    final var token = this.adminProtocolBearerToken();

    final var result =
        this.mockMvc
            .perform(
                put(UPLOAD, repo.getName())
                    .header(AUTHORIZATION, token)
                    .contentType("application/zip")
                    .content(moduleZip(GoModuleZipReader.MAX_GO_MOD_BYTES + 1))
                    .with(protocolPort()))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    assertThat(result.getResponse().getContentAsString())
        .contains("\"msgId\":\"goModTooLarge\"")
        .contains("The go.mod in the module zip is larger than 16 MiB.");
    assertThat(this.versionList(repo.getName(), token)).doesNotContain(VERSION);
  }

  @Test
  @DisplayName("still publishes a go.mod of exactly the limit")
  void publishesGoModAtLimit() throws Exception {
    final var repo = this.seedRepo(RepoType.GOLANG, uniqueRepoName("rps1054"));
    final var token = this.adminProtocolBearerToken();

    final var result =
        this.mockMvc
            .perform(
                put(UPLOAD, repo.getName())
                    .header(AUTHORIZATION, token)
                    .contentType("application/zip")
                    .content(moduleZip(GoModuleZipReader.MAX_GO_MOD_BYTES))
                    .with(protocolPort()))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(this.versionList(repo.getName(), token)).contains(VERSION);
  }
}
