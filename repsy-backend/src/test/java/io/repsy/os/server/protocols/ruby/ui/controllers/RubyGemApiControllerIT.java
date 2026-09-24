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
import io.repsy.os.PagingAssertions;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Full-stack integration coverage for the Ruby gem-management API. */
@DisplayName("RubyGemApiController /api/ruby/gems/*")
class RubyGemApiControllerIT extends AbstractIntegrationTest {

  private String publisherToken;

  private Repo createRepo(final boolean privateRepo) {
    return this.seedRepo(RepoType.RUBY, uniqueRepoName("rubyrepo"), privateRepo, null);
  }

  /** Pushes a real RubyGems archive through the protocol port, like {@code gem push} does. */
  private void publish(
      final String repoName,
      final String name,
      final String version,
      final String platform,
      final String description)
      throws Exception {
    if (this.publisherToken == null) {
      this.publisherToken = this.adminProtocolBearerToken();
    }

    this.mockMvc
        .perform(
            post(PUBLISH_PATH, repoName)
                .with(protocolPort())
                .header(AUTHORIZATION, this.publisherToken)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(gem(name, version, platform, description)))
        .andExpect(status().isOk());
    this.entityManager.flush();
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
          .andExpect(status().isUnauthorized());
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
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.msgId").value("accessDenied"))
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

  @Nested
  @DisplayName("paging and sorting of the list endpoints")
  class PagingAndSorting {

    private static final String GEMS = "/api/ruby/gems/{repo}";
    private static final String VERSIONS = "/api/ruby/gems/{repo}/fixture-gem/versions";

    static Stream<String> endpoints() {
      return Stream.of(GEMS, VERSIONS);
    }

    static Stream<Arguments> acceptedSorts() {
      return Stream.concat(
          Stream.of("id", "name", "updatedAt").map(property -> Arguments.of(GEMS, property)),
          Stream.of("id", "version", "createdAt")
              .map(property -> Arguments.of(VERSIONS, property)));
    }

    static Stream<Arguments> invalidPagingOnEveryEndpoint() {
      return endpoints()
          .flatMap(
              path ->
                  PagingAssertions.invalidPagingParams()
                      .map(args -> Arguments.of(path, args.get()[0], args.get()[1])));
    }

    private record Seed(Repo repo, String token) {}

    private Seed seed() throws Exception {
      final var it = RubyGemApiControllerIT.this;
      final var user = it.createUser(uniqueUsername("ruby"), UserRole.USER);
      final var repo = it.createRepo(true);

      it.publish(repo.getName(), "fixture-gem", "1.0.0", "ruby", "stable fixture");
      it.publish(repo.getName(), "fixture-gem", "1.1.0", "ruby", "latest fixture");
      it.publish(repo.getName(), "other-gem", "0.1.0", "ruby", "other fixture");

      return new Seed(repo, it.bearerTokenFor(user));
    }

    private ResultActions list(
        final Seed seed, final String path, final String param, final String value)
        throws Exception {
      return RubyGemApiControllerIT.this.mockMvc.perform(
          get(path, seed.repo().getName())
              .param(param, value)
              .with(apiPort())
              .header(AUTHORIZATION, seed.token()));
    }

    @ParameterizedTest(name = "{0} sort={1}")
    @MethodSource("acceptedSorts")
    @DisplayName("accepts every documented sort property in both directions")
    void acceptsSort(final String path, final String property) throws Exception {
      final var seed = this.seed();

      this.list(seed, path, "sort", property + ",asc").andExpect(status().isOk());
      this.list(seed, path, "sort", property + ",desc").andExpect(status().isOk());
    }

    @Test
    @DisplayName("orders the gems and versions by the requested sort property")
    void ordersByRequestedProperty() throws Exception {
      final var seed = this.seed();

      this.list(seed, GEMS, "sort", "name,asc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].name").value("fixture-gem"));
      this.list(seed, GEMS, "sort", "name,desc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].name").value("other-gem"));
      this.list(seed, VERSIONS, "sort", "version,asc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].version").value("1.0.0"));
      this.list(seed, VERSIONS, "sort", "version,desc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].version").value("1.1.0"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 400 validationError naming sort for an unknown sort property")
    void unknownSortIs400(final String path) throws Exception {
      PagingAssertions.expectInvalidParameter(
          this.list(this.seed(), path, "sort", PagingAssertions.UNKNOWN_SORT), "sort");
    }

    @ParameterizedTest(name = "{0} {1}={2}")
    @MethodSource("invalidPagingOnEveryEndpoint")
    @DisplayName("returns 400 validationError naming the parameter for a bad page or size")
    void invalidPagingParam(final String path, final String param, final String value)
        throws Exception {
      PagingAssertions.expectInvalidParameter(this.list(this.seed(), path, param, value), param);
    }
  }
}
