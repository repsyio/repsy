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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.services.RubyGemServiceImpl;
import io.repsy.os.server.protocols.ruby.shared.storage.services.RubyStorageService;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.ruby.shared.gem.dtos.GemDependency;
import io.repsy.protocols.ruby.shared.gem.dtos.GemMetadata;
import io.repsy.protocols.ruby.shared.utils.CompactIndexFormatter;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Full-stack integration coverage for the Ruby gem-management API. */
@DisplayName("RubyGemApiController /api/ruby/gems/*")
class RubyGemApiControllerIT extends AbstractIntegrationTest {

  @Autowired private RepoTxService repoTxService;
  @Autowired private RubyGemServiceImpl rubyGemService;
  @Autowired private RubyStorageService rubyStorageService;

  private static String unique(final String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  private RepoInfo createRepo(final boolean privateRepo) {
    return this.createRepo(RepoType.RUBY, privateRepo);
  }

  private RepoInfo createRepo(final RepoType type, final boolean privateRepo) {
    final var repo = this.repoTxService.createRepo(unique("rubyrepo"), type, privateRepo, null);
    this.rubyStorageService.createRepo(repo.getId());
    return repo;
  }

  /** Seeds a real RubyGems archive through the storage and metadata services. */
  private void publish(
      final String repoName,
      final String name,
      final String version,
      final String platform,
      final String description)
      throws Exception {
    final var repo = this.repoTxService.getRepoByName(repoName);
    final var bytes = gem(name, version, platform, description);
    this.rubyStorageService.writeGem(repo.getId(), repoName, name, version, platform, bytes);
    this.rubyGemService.publishGem(
        repo,
        GemMetadata.builder()
            .name(name)
            .version(version)
            .platform(platform)
            .description(description)
            .authors("Alice, Bob")
            .homepage("https://example.test/" + name)
            .requiredRubyVersion(">= 3.1.0")
            .runtimeDependencies(
                java.util.List.of(
                    GemDependency.builder()
                        .name("rack")
                        .requirements(">= 3.0.0")
                        .type("runtime")
                        .build()))
            .developmentDependencies(
                java.util.List.of(
                    GemDependency.builder()
                        .name("rake")
                        .requirements(">= 13.0.0")
                        .type("development")
                        .build()))
            .build(),
        CompactIndexFormatter.sha256Hex(bytes));
    this.entityManager.flush();
  }

  /**
   * Creates the minimal outer tar required by {@code GemspecParser}, with representative metadata.
   */
  private static byte[] gem(
      final String name, final String version, final String platform, final String description)
      throws IOException {
    final var metadata =
        "name: "
            + name
            + "\n"
            + "version:\n"
            + "  version: "
            + version
            + "\n"
            + "platform: "
            + platform
            + "\n"
            + "description: "
            + description
            + "\n"
            + "authors:\n"
            + "- Alice\n"
            + "- Bob\n"
            + "homepage: https://example.test/"
            + name
            + "\n"
            + "required_ruby_version:\n"
            + "  requirements:\n"
            + "  - - \">=\"\n"
            + "    - version: 3.1.0\n"
            + "dependencies:\n"
            + "- name: rack\n"
            + "  type: runtime\n"
            + "  requirement:\n"
            + "    requirements:\n"
            + "    - - \">=\"\n"
            + "      - version: 3.0.0\n"
            + "- name: rake\n"
            + "  type: development\n"
            + "  requirement:\n"
            + "    requirements:\n"
            + "    - - \">=\"\n"
            + "      - version: 13.0.0\n";
    final var metadataGz = gzip(metadata.getBytes(StandardCharsets.UTF_8));
    final var dataGz = gzip(new byte[0]);
    final var output = new ByteArrayOutputStream();
    try (var tar = new TarArchiveOutputStream(output)) {
      add(tar, "metadata.gz", metadataGz);
      add(tar, "data.tar.gz", dataGz);
      tar.finish();
    }
    return output.toByteArray();
  }

  private static byte[] gzip(final byte[] bytes) throws IOException {
    final var output = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(output)) {
      gzip.write(bytes);
    }
    return output.toByteArray();
  }

  private static void add(final TarArchiveOutputStream tar, final String name, final byte[] bytes)
      throws IOException {
    final var entry = new TarArchiveEntry(name);
    entry.setSize(bytes.length);
    tar.putArchiveEntry(entry);
    tar.write(bytes);
    tar.closeArchiveEntry();
  }

  @Nested
  @DisplayName("GET endpoints")
  class GetEndpoints {

    @Test
    @DisplayName("returns gem list, versions, and complete version metadata")
    void returnsGemViews() throws Exception {
      final var user =
          RubyGemApiControllerIT.this.createUser(uniqueUsername("ruby"), UserRole.USER);
      final var repo = RubyGemApiControllerIT.this.createRepo(true);
      final var token = RubyGemApiControllerIT.this.bearerTokenFor(user);
      RubyGemApiControllerIT.this.publish(
          repo.getName(), "fixture-gem", "1.0.0", "ruby", "stable fixture");
      RubyGemApiControllerIT.this.publish(
          repo.getName(), "fixture-gem", "1.1.0", "ruby", "latest fixture");
      RubyGemApiControllerIT.this.publish(
          repo.getName(), "fixture-gem", "1.1.0", "x86_64-linux", "native fixture");

      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              get("/api/ruby/gems/{repo}", repo.getName())
                  .param("name", "fixture")
                  .param("size", "10")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.msgId").value("gemsFetched"))
          .andExpect(jsonPath("$.type").value("SUCCESS"))
          .andExpect(jsonPath("$.errorCode").value(nullValue()))
          .andExpect(jsonPath("$.text").value("Gems fetched."))
          .andExpect(jsonPath("$.data.content", hasSize(1)))
          .andExpect(jsonPath("$.data.content[0].name").value("fixture-gem"))
          .andExpect(jsonPath("$.data.content[0].latest").value("1.1.0"))
          .andExpect(jsonPath("$.data.content[0].updatedAt").isNotEmpty())
          .andExpect(jsonPath("$.data.page.size").value(10))
          .andExpect(jsonPath("$.data.page.number").value(0))
          .andExpect(jsonPath("$.data.page.totalElements").value(1))
          .andExpect(jsonPath("$.data.page.totalPages").value(1));

      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              get("/api/ruby/gems/{repo}/{gem}/versions", repo.getName(), "fixture-gem")
                  .param("version", "1.1")
                  .param("size", "10")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("gemVersionsFetched"))
          .andExpect(jsonPath("$.data.content", hasSize(2)))
          .andExpect(jsonPath("$.data.content[0].version").value("1.1.0"))
          .andExpect(jsonPath("$.data.content[0].platform").value("ruby"))
          .andExpect(jsonPath("$.data.content[0].yanked").value(false))
          .andExpect(jsonPath("$.data.content[1].platform").value("x86_64-linux"));

      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              get(
                      "/api/ruby/gems/{repo}/{gem}/versions/{version}",
                      repo.getName(),
                      "fixture-gem",
                      "1.1.0")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.msgId").value("gemVersionFetched"))
          .andExpect(jsonPath("$.data.version").value("1.1.0"))
          .andExpect(jsonPath("$.data.platform").value("ruby"))
          .andExpect(jsonPath("$.data.checksum").value(matchesPattern("[0-9a-f]{64}")))
          .andExpect(jsonPath("$.data.authors").value("Alice, Bob"))
          .andExpect(jsonPath("$.data.description").value("latest fixture"))
          .andExpect(jsonPath("$.data.homepage").value("https://example.test/fixture-gem"))
          .andExpect(jsonPath("$.data.requiredRubyVersion").value(">= 3.1.0"))
          .andExpect(jsonPath("$.data.runtimeDependencies[0].name").value("rack"))
          .andExpect(jsonPath("$.data.runtimeDependencies[0].requirements").value(">= 3.0.0"))
          .andExpect(jsonPath("$.data.runtimeDependencies[0].type").value("runtime"))
          .andExpect(jsonPath("$.data.developmentDependencies[0].name").value("rake"))
          .andExpect(jsonPath("$.data.yanked").value(false))
          .andExpect(jsonPath("$.data.createdAt").isNotEmpty());

      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              get(
                      "/api/ruby/gems/{repo}/{gem}/versions/{version}",
                      repo.getName(),
                      "fixture-gem",
                      "1.1.0")
                  .param("platform", "x86_64-linux")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.platform").value("x86_64-linux"))
          .andExpect(jsonPath("$.data.description").value("native fixture"));
    }

    @Test
    @DisplayName("allows anonymous reads only for public repositories")
    void publicReadAccess() throws Exception {
      final var repo = RubyGemApiControllerIT.this.createRepo(false);
      RubyGemApiControllerIT.this
          .mockMvc
          .perform(get("/api/ruby/gems/{repo}", repo.getName()).with(apiPort()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("gemsFetched"));
    }

    @Test
    @DisplayName("allows authenticated private reads and returns unknown repositories")
    void privateReadAndUnknownItems() throws Exception {
      final var repo = RubyGemApiControllerIT.this.createRepo(true);
      final var user =
          RubyGemApiControllerIT.this.createUser(uniqueUsername("ruby"), UserRole.USER);

      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              get("/api/ruby/gems/{repo}", repo.getName())
                  .with(apiPort())
                  .header(AUTHORIZATION, RubyGemApiControllerIT.this.bearerTokenFor(user)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("gemsFetched"));

      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              get("/api/ruby/gems/{repo}", "missing-ruby-repo")
                  .with(apiPort())
                  .header(AUTHORIZATION, RubyGemApiControllerIT.this.bearerTokenFor(user)))
          .andExpect(status().isNotFound());

      // Anonymous callers cannot tell a missing repo from a private one (RPS-887).
      RubyGemApiControllerIT.this
          .mockMvc
          .perform(get("/api/ruby/gems/{repo}", "missing-ruby-repo").with(apiPort()))
          .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("supports filtering, pagination, and empty results")
    void filtersAndPaginatesGemList() throws Exception {
      final var user =
          RubyGemApiControllerIT.this.createUser(uniqueUsername("ruby"), UserRole.USER);
      final var repo = RubyGemApiControllerIT.this.createRepo(true);
      final var token = RubyGemApiControllerIT.this.bearerTokenFor(user);
      RubyGemApiControllerIT.this.publish(repo.getName(), "alpha-gem", "1.0.0", "ruby", "alpha");
      RubyGemApiControllerIT.this.publish(repo.getName(), "beta-gem", "1.0.0", "ruby", "beta");
      RubyGemApiControllerIT.this.publish(repo.getName(), "gamma-gem", "1.0.0", "ruby", "gamma");

      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              get("/api/ruby/gems/{repo}", repo.getName())
                  .param("name", "beta")
                  .param("page", "0")
                  .param("size", "1")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(1)))
          .andExpect(jsonPath("$.data.content[0].name").value("beta-gem"))
          .andExpect(jsonPath("$.data.page.number").value(0))
          .andExpect(jsonPath("$.data.page.size").value(1))
          .andExpect(jsonPath("$.data.page.totalElements").value(1));

      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              get("/api/ruby/gems/{repo}", repo.getName())
                  .param("page", "1")
                  .param("size", "2")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(1)))
          .andExpect(jsonPath("$.data.page.totalElements").value(3))
          .andExpect(jsonPath("$.data.page.totalPages").value(2));

      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              get("/api/ruby/gems/{repo}", repo.getName())
                  .param("name", "does-not-match")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(0)))
          .andExpect(jsonPath("$.data.page.totalElements").value(0));
    }

    @Test
    @DisplayName("returns not found for unknown gems, versions, and platforms")
    void unknownGemVersionAndPlatformAreNotFound() throws Exception {
      final var repo = RubyGemApiControllerIT.this.createRepo(false);
      RubyGemApiControllerIT.this.publish(repo.getName(), "known", "1.0.0", "ruby", "known");

      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              get("/api/ruby/gems/{repo}/{gem}/versions", repo.getName(), "missing")
                  .with(apiPort()))
          .andExpect(status().isNotFound());
      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              get(
                      "/api/ruby/gems/{repo}/{gem}/versions/{version}",
                      repo.getName(),
                      "known",
                      "9.9.9")
                  .with(apiPort()))
          .andExpect(status().isNotFound());
      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              get(
                      "/api/ruby/gems/{repo}/{gem}/versions/{version}",
                      repo.getName(),
                      "known",
                      "1.0.0")
                  .param("platform", "missing-platform")
                  .with(apiPort()))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("rejects missing, malformed, expired, and unknown-user authorization")
    void rejectsInvalidAuthorization() throws Exception {
      final var repo = RubyGemApiControllerIT.this.createRepo(true);
      final var user =
          RubyGemApiControllerIT.this.createUser(uniqueUsername("ruby"), UserRole.USER);
      final var expired =
          AuthUtils.AUTH_BEARER
              + RubyGemApiControllerIT.this.jwtUtils.createPanelAccessToken(
                  user.getId(), user.getUsername(), Duration.ofSeconds(-30));
      final var unknown =
          AuthUtils.AUTH_BEARER
              + RubyGemApiControllerIT.this.jwtUtils.createPanelAccessToken(
                  UUID.randomUUID(), "unknown-user", Duration.ofMinutes(30));

      RubyGemApiControllerIT.this
          .mockMvc
          .perform(get("/api/ruby/gems/{repo}", repo.getName()).with(apiPort()))
          .andExpect(status().isUnauthorized());
      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              get("/api/ruby/gems/{repo}", repo.getName())
                  .with(apiPort())
                  .header(AUTHORIZATION, "Bearer garbage"))
          .andExpect(status().isUnauthorized());
      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              get("/api/ruby/gems/{repo}", repo.getName())
                  .with(apiPort())
                  .header(AUTHORIZATION, expired))
          .andExpect(status().isUnauthorized());
      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              get("/api/ruby/gems/{repo}", repo.getName())
                  .with(apiPort())
                  .header(AUTHORIZATION, unknown))
          .andExpect(status().isNotFound());
    }
  }

  @Nested
  @DisplayName("DELETE endpoints")
  class DeleteEndpoints {

    @Test
    @DisplayName("deletes one platform, then the complete gem, and rejects a repeated delete")
    void deletesVersionAndGem() throws Exception {
      final var admin =
          RubyGemApiControllerIT.this.createUser(uniqueUsername("ruby"), UserRole.ADMIN);
      final var repo = RubyGemApiControllerIT.this.createRepo(true);
      final var token = RubyGemApiControllerIT.this.bearerTokenFor(admin);
      RubyGemApiControllerIT.this.publish(repo.getName(), "delete-me", "1.0.0", "ruby", "first");
      RubyGemApiControllerIT.this.publish(
          repo.getName(), "delete-me", "1.0.0", "java", "java variant");
      RubyGemApiControllerIT.this.publish(repo.getName(), "delete-me", "2.0.0", "ruby", "second");

      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              delete(
                      "/api/ruby/gems/{repo}/{gem}/versions/{version}",
                      repo.getName(),
                      "delete-me",
                      "1.0.0")
                  .param("platform", "java")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.msgId").value("gemVersionDeleted"))
          .andExpect(jsonPath("$.type").value("SUCCESS"))
          .andExpect(jsonPath("$.data").value(nullValue()))
          .andExpect(jsonPath("$.errorCode").value(nullValue()));

      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              get("/api/ruby/gems/{repo}/{gem}/versions", repo.getName(), "delete-me")
                  .param("size", "10")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(2)))
          .andExpect(jsonPath("$.data.content[0].platform").value("ruby"))
          .andExpect(jsonPath("$.data.content[1].version").value("2.0.0"));

      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              delete("/api/ruby/gems/{repo}/{gem}", repo.getName(), "delete-me")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("gemDeleted"))
          .andExpect(jsonPath("$.data").value(nullValue()));

      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              get("/api/ruby/gems/{repo}", repo.getName())
                  .param("size", "10")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(0)))
          .andExpect(jsonPath("$.data.page.totalElements").value(0));

      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              delete("/api/ruby/gems/{repo}/{gem}", repo.getName(), "delete-me")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.errorCode").value(matchesPattern(UUID_PATTERN)));
    }

    @Test
    @DisplayName("deleting the last version also removes its gem")
    void deletingLastVersionRemovesGem() throws Exception {
      final var admin =
          RubyGemApiControllerIT.this.createUser(uniqueUsername("ruby"), UserRole.ADMIN);
      final var repo = RubyGemApiControllerIT.this.createRepo(true);
      final var token = RubyGemApiControllerIT.this.bearerTokenFor(admin);
      RubyGemApiControllerIT.this.publish(repo.getName(), "last-version", "1.0.0", "ruby", "only");

      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              delete(
                      "/api/ruby/gems/{repo}/{gem}/versions/{version}",
                      repo.getName(),
                      "last-version",
                      "1.0.0")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("gemVersionDeleted"))
          .andExpect(jsonPath("$.data").value(nullValue()));

      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              get("/api/ruby/gems/{repo}", repo.getName())
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(0)));
    }

    @Test
    @DisplayName("does not allow a read-only caller to delete")
    void readOnlyCallerCannotDelete() throws Exception {
      final var repo = RubyGemApiControllerIT.this.createRepo(true);
      final var owner =
          RubyGemApiControllerIT.this.createUser(uniqueUsername("ruby"), UserRole.ADMIN);
      final var user =
          RubyGemApiControllerIT.this.createUser(uniqueUsername("ruby"), UserRole.USER);
      RubyGemApiControllerIT.this.publish(repo.getName(), "existing", "1.0.0", "ruby", "fixture");
      RubyGemApiControllerIT.this
          .mockMvc
          .perform(
              delete("/api/ruby/gems/{repo}/{gem}", repo.getName(), "existing")
                  .with(apiPort())
                  .header(AUTHORIZATION, RubyGemApiControllerIT.this.bearerTokenFor(user)))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.errorCode").value(matchesPattern(UUID_PATTERN)));
    }

    @Test
    @DisplayName("rejects unsupported methods on the Ruby gem API")
    void unsupportedMethodsAreRejected() throws Exception {
      final var repo = RubyGemApiControllerIT.this.createRepo(false);

      RubyGemApiControllerIT.this
          .mockMvc
          .perform(post("/api/ruby/gems/{repo}", repo.getName()).with(apiPort()))
          .andExpect(status().isNotFound());
      RubyGemApiControllerIT.this
          .mockMvc
          .perform(post("/api/ruby/gems/{repo}/{gem}", repo.getName(), "missing").with(apiPort()))
          .andExpect(status().isNotFound());
    }
  }
}
