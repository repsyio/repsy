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
package io.repsy.os.server.protocols.ruby.ui.controllers;

import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.PUBLISH_PATH;
import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.gem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.ruby.shared.utils.CompactIndexFormatter;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * Deleting one gem version through the panel removes that version only (RPS-1426): the gem goes
 * with its last remaining version, yanked or not, and what the gem still serves stays consistent.
 */
@DisplayName("RubyGemApiController DELETE .../versions/{version} keeps the other versions")
class RubyGemVersionDeleteIT extends AbstractIntegrationTest {

  private static final String GEM = "keep-gem";
  private static final String VERSION_PATH = "/api/ruby/gems/{repo}/{gem}/versions/{version}";
  private static final String VERSIONS_PATH = "/api/ruby/gems/{repo}/{gem}/versions";

  private String protocolToken;
  private String panelToken;
  private Repo repo;

  private void seed(final String... versions) throws Exception {
    this.protocolToken = this.adminProtocolBearerToken();
    this.panelToken = this.bearerTokenFor(this.createUser(uniqueUsername("ruby"), UserRole.ADMIN));
    this.repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("rubydel"), true, null);
    for (final var version : versions) {
      this.protocol(
              post(PUBLISH_PATH, this.repo.getName())
                  .header(AUTHORIZATION, this.protocolToken)
                  .contentType(MediaType.APPLICATION_OCTET_STREAM)
                  .content(gem(GEM, version, "ruby", "fixture " + version)))
          .andExpect(status().isOk());
      this.entityManager.flush();
    }
  }

  private ResultActions protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort()));
  }

  private void yank(final String version) throws Exception {
    this.protocol(
            delete("/{repo}/api/v1/gems/yank", this.repo.getName())
                .header(AUTHORIZATION, this.protocolToken)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("gem_name", GEM)
                .param("version", version))
        .andExpect(status().isOk());
    this.syncPersistenceContext();
  }

  private ResultActions deleteVersion(final String version, final String platform)
      throws Exception {
    final var result =
        this.mockMvc.perform(
            delete(VERSION_PATH, this.repo.getName(), GEM, version)
                .param("platform", platform)
                .with(apiPort())
                .header(AUTHORIZATION, this.panelToken));
    this.syncPersistenceContext();
    return result;
  }

  private ResultActions deleteVersion(final String version) throws Exception {
    return this.deleteVersion(version, "ruby");
  }

  /**
   * The bulk statements behind yank do not refresh entities the test's one shared persistence
   * context already holds (each real request has its own), so drop them before reading.
   */
  private void syncPersistenceContext() {
    this.entityManager.flush();
    this.entityManager.clear();
  }

  private ResultActions panelGet(final String path, final Object... vars) throws Exception {
    return this.mockMvc.perform(
        get(path, vars).param("size", "10").with(apiPort()).header(AUTHORIZATION, this.panelToken));
  }

  private String protocolGet(final String path) throws Exception {
    return this.protocol(get(path, this.repo.getName()).header(AUTHORIZATION, this.protocolToken))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  @DisplayName("deleting a yanked version keeps the live version, listed and installable")
  void deletingYankedVersionKeepsLiveVersion() throws Exception {
    this.seed("1.0.0", "2.0.0");
    this.yank("2.0.0");

    this.deleteVersion("2.0.0")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.msgId").value("gemVersionDeleted"));

    this.panelGet(VERSIONS_PATH, this.repo.getName(), GEM)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content", hasSize(1)))
        .andExpect(jsonPath("$.data.content[0].version").value("1.0.0"))
        .andExpect(jsonPath("$.data.content[0].yanked").value(false));
    this.panelGet("/api/ruby/gems/{repo}", this.repo.getName())
        .andExpect(jsonPath("$.data.content", hasSize(1)))
        .andExpect(jsonPath("$.data.content[0].latest").value("1.0.0"));

    assertThat(this.protocolGet("/{repo}/info/" + GEM)).contains("1.0.0").doesNotContain("2.0.0");
    this.protocol(
            get("/{repo}/gems/{gem}-1.0.0.gem", this.repo.getName(), GEM)
                .header(AUTHORIZATION, this.protocolToken))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("deleting the live version keeps the yanked one and the gem")
  void deletingLiveVersionKeepsYankedVersion() throws Exception {
    this.seed("1.0.0", "2.0.0");
    this.yank("2.0.0");

    this.deleteVersion("1.0.0").andExpect(status().isOk());

    this.panelGet(VERSIONS_PATH, this.repo.getName(), GEM)
        .andExpect(jsonPath("$.data.content", hasSize(1)))
        .andExpect(jsonPath("$.data.content[0].version").value("2.0.0"))
        .andExpect(jsonPath("$.data.content[0].yanked").value(true));
    // The gem is still listed, and its latest names a version that still exists.
    this.panelGet("/api/ruby/gems/{repo}", this.repo.getName())
        .andExpect(jsonPath("$.data.content", hasSize(1)))
        .andExpect(jsonPath("$.data.content[0].latest").value("2.0.0"));
    assertThat(this.protocolGet("/{repo}/versions")).contains(GEM + " -2.0.0 ");
  }

  @Test
  @DisplayName("deleting the only version, yanked or not, removes the gem")
  void deletingOnlyYankedVersionRemovesGem() throws Exception {
    this.seed("1.0.0");
    this.yank("1.0.0");

    this.deleteVersion("1.0.0").andExpect(status().isOk());

    this.panelGet("/api/ruby/gems/{repo}", this.repo.getName())
        .andExpect(jsonPath("$.data.content", hasSize(0)));
    this.panelGet(VERSIONS_PATH, this.repo.getName(), GEM).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("deleting the latest version moves the gem's latest to the newest one left")
  void deletingLatestVersionRecomputesLatest() throws Exception {
    this.seed("1.0.0", "2.0.0", "3.0.0");

    this.deleteVersion("3.0.0").andExpect(status().isOk());

    // The gem list joins on latest, so a stale one would drop the gem from it.
    this.panelGet("/api/ruby/gems/{repo}", this.repo.getName())
        .andExpect(jsonPath("$.data.content", hasSize(1)))
        .andExpect(jsonPath("$.data.content[0].latest").value("2.0.0"));
  }

  @Test
  @DisplayName("deleting one platform of the latest version keeps latest")
  void deletingOnePlatformKeepsLatest() throws Exception {
    this.seed("1.0.0");
    this.protocol(
            post(PUBLISH_PATH, this.repo.getName())
                .header(AUTHORIZATION, this.protocolToken)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(gem(GEM, "1.0.0", "java", "java variant")))
        .andExpect(status().isOk());
    this.entityManager.flush();

    this.deleteVersion("1.0.0", "java").andExpect(status().isOk());

    this.panelGet("/api/ruby/gems/{repo}", this.repo.getName())
        .andExpect(jsonPath("$.data.content", hasSize(1)))
        .andExpect(jsonPath("$.data.content[0].latest").value("1.0.0"));
  }

  @Test
  @DisplayName("/versions serves the checksum of the /info that is left after a delete")
  void versionsChecksumFollowsInfoAfterDelete() throws Exception {
    this.seed("1.0.0", "2.0.0");

    this.deleteVersion("2.0.0").andExpect(status().isOk());

    final var info = this.protocolGet("/{repo}/info/" + GEM);
    final var line =
        this.protocolGet("/{repo}/versions")
            .lines()
            .filter(l -> l.startsWith(GEM + " "))
            .findFirst()
            .orElseThrow();
    assertThat(line).isEqualTo(GEM + " 1.0.0 " + CompactIndexFormatter.md5Hex(info));
  }

  @Test
  @DisplayName("deleting a version the gem does not have is a 404 and removes nothing")
  void deletingUnknownVersionIsNotFound() throws Exception {
    this.seed("1.0.0", "2.0.0");

    this.deleteVersion("9.9.9")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.msgId").value("gemVersionNotFound"));
    this.deleteVersion("1.0.0", "java")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.msgId").value("gemVersionNotFound"));

    this.panelGet(VERSIONS_PATH, this.repo.getName(), GEM)
        .andExpect(jsonPath("$.data.content", hasSize(2)));
  }
}
