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
package io.repsy.os.server.protocols.npm.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIT;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-2739: with {@code allowOverride} off, publishing a version that already exists is refused
 * over HTTP with 403 {@code packageVersionAlreadyExists} and changes nothing, while a new version
 * of the same package is still accepted. Until now only the e2e suite pinned this.
 *
 * <p>{@link UsageUpdateService} is mocked: it is {@code @Async} and cannot see this class's
 * uncommitted rows. Every test runs in the default rolled-back transaction.
 */
@DisplayName("npm publish with allowOverride off refuses an existing version")
class NpmPublishOverrideRefusedIT extends AbstractIT {

  private static final String PACKAGE = "override-demo";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private ObjectMapper objectMapper;

  private Repo repoWithOverrideOff() throws Exception {
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("npm"));

    this.perform(
            put("/api/repos/{name}/settings", repo.getName())
                .header(AUTHORIZATION, this.adminBearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"allowOverride\":false}"))
        .andReturn();

    return repo;
  }

  private MockHttpServletResponse publish(
      final Repo repo, final String version, final String tarball) throws Exception {
    final var body =
        NpmPublishBodies.body(
            this.objectMapper,
            repo.getName(),
            PACKAGE,
            version,
            Map.of(),
            tarball.getBytes(StandardCharsets.UTF_8));

    return this.mockMvc
        .perform(
            put("/{repo}/{pkg}", repo.getName(), PACKAGE)
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse read(final Repo repo, final String path) throws Exception {
    return this.mockMvc
        .perform(
            get("/{repo}/" + path, repo.getName())
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  @Test
  @DisplayName("re-publishing a version is a 403 packageVersionAlreadyExists and changes nothing")
  void existingVersionIsRefused() throws Exception {
    final var repo = this.repoWithOverrideOff();
    final var tarballPath = PACKAGE + "/-/" + PACKAGE + "-1.0.0.tgz";

    final var first = this.publish(repo, "1.0.0", "original");
    assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(200);
    final var packumentBefore = this.read(repo, PACKAGE).getContentAsString();
    final var tarballBefore = this.read(repo, tarballPath).getContentAsByteArray();

    final var again = this.publish(repo, "1.0.0", "replacement");

    assertThat(again.getStatus()).isEqualTo(403);
    assertThat(again.getContentAsString()).contains("\"msgId\":\"packageVersionAlreadyExists\"");
    assertThat(this.read(repo, PACKAGE).getContentAsString()).isEqualTo(packumentBefore);
    assertThat(this.read(repo, tarballPath).getContentAsByteArray())
        .isEqualTo(tarballBefore)
        .isEqualTo("original".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("a new version of the same package is still accepted")
  void newVersionIsAccepted() throws Exception {
    final var repo = this.repoWithOverrideOff();

    assertThat(this.publish(repo, "1.0.0", "one").getStatus()).isEqualTo(200);

    final var second = this.publish(repo, "1.1.0", "two");

    assertThat(second.getStatus()).as(second.getContentAsString()).isEqualTo(200);
    assertThat(this.read(repo, PACKAGE).getContentAsString()).contains("1.0.0").contains("1.1.0");
  }
}
