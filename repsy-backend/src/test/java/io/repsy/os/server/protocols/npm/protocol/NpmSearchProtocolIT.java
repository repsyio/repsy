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
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.NpmSearchCandidateRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageKeywordRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageMaintainerRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.services.NpmSearchServiceImpl;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.npm.shared.search.NpmSearchQuery;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1329: {@code npm search} ({@code GET /-/v1/search}) used to end in {@code 404 unknownPath}.
 * Packages are published over the wire, so what is searched is what the real publish flow stored.
 */
@DisplayName("npm wire protocol GET /-/v1/search")
class NpmSearchProtocolIT extends AbstractIntegrationTest {

  private static final String SEARCH = "/{repo}/-/v1/search";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private ObjectMapper objectMapper;
  @Autowired private NpmSearchCandidateRepository candidateRepository;
  @Autowired private PackageKeywordRepository keywordRepository;
  @Autowired private PackageMaintainerRepository maintainerRepository;

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private void publish(
      final Repo repo, final String token, final String name, final Map<String, Object> extra)
      throws Exception {
    final var response =
        this.protocol(
            put("/{repo}/{name}", repo.getName(), name)
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    NpmPublishBodies.body(
                        this.objectMapper, repo.getName(), name, "1.0.0", extra)));

    assertThat(response.getStatus()).isEqualTo(200);
  }

  /** A repo with left-pad, @acme/right-pad, @acme/widget and is-odd. */
  private Repo seedPackages(final boolean privateRepo, final String token) throws Exception {
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("search"), privateRepo, null);

    this.publish(
        repo,
        token,
        "left-pad",
        Map.of(
            "description", "pads a string on the left",
            "keywords", List.of("pad", "string"),
            "homepage", "https://left-pad.example.com",
            "repository", Map.of("type", "git", "url", "https://git.example.com/left-pad.git"),
            "bugs", Map.of("url", "https://git.example.com/left-pad/issues"),
            "author", Map.of("name", "Ann", "email", "ann@example.com"),
            "maintainers", List.of(Map.of("name", "ann", "email", "ann@example.com"))));
    this.publish(
        repo,
        token,
        "@acme/right-pad",
        Map.of("description", "pads on the right", "keywords", List.of("pad")));
    this.publish(
        repo, token, "@acme/widget", Map.of("description", "a widget", "keywords", List.of("ui")));
    this.publish(repo, token, "is-odd", Map.of("description", "checks odd numbers"));

    return repo;
  }

  private String adminToken() {
    return this.protocolBearerTokenFor(createUser(uniqueUsername("search"), UserRole.ADMIN));
  }

  private String search(final Repo repo, final String... params) throws Exception {
    final var request = get(SEARCH, repo.getName());
    for (var i = 0; i < params.length; i += 2) {
      request.param(params[i], params[i + 1]);
    }
    final var response = this.protocol(request);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);

    return response.getContentAsString();
  }

  private static List<String> names(final String json) {
    final List<String> scopes = JsonPath.read(json, "$.objects[*].package.scope");
    final List<String> names = JsonPath.read(json, "$.objects[*].package.name");
    final var full = new java.util.ArrayList<String>();

    for (var i = 0; i < names.size(); i++) {
      full.add(
          "unscoped".equals(scopes.get(i))
              ? names.get(i)
              : "@" + scopes.get(i) + "/" + names.get(i));
    }

    return full;
  }

  @Test
  @DisplayName("writes the shape npm search reads")
  void shape() throws Exception {
    final var repo = this.seedPackages(false, this.adminToken());

    final var json = this.search(repo, "text", "left-pad");

    assertThat(JsonPath.<Integer>read(json, "$.total")).isEqualTo(1);
    assertThat(JsonPath.<String>read(json, "$.time")).matches("\\d{4}-\\d\\d-\\d\\dT.*Z");
    assertThat(JsonPath.<String>read(json, "$.objects[0].package.name")).isEqualTo("left-pad");
    assertThat(JsonPath.<String>read(json, "$.objects[0].package.scope")).isEqualTo("unscoped");
    assertThat(JsonPath.<String>read(json, "$.objects[0].package.version")).isEqualTo("1.0.0");
    assertThat(JsonPath.<String>read(json, "$.objects[0].package.description"))
        .isEqualTo("pads a string on the left");
    assertThat(JsonPath.<List<String>>read(json, "$.objects[0].package.keywords"))
        .containsExactlyInAnyOrder("pad", "string");
    assertThat(JsonPath.<String>read(json, "$.objects[0].package.date"))
        .matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(\\.\\d{1,3})?Z");
    assertThat(JsonPath.<String>read(json, "$.objects[0].package.links.homepage"))
        .isEqualTo("https://left-pad.example.com");
    assertThat(JsonPath.<String>read(json, "$.objects[0].package.links.repository"))
        .contains("left-pad");
    assertThat(JsonPath.<String>read(json, "$.objects[0].package.links.bugs")).contains("issues");
    assertThat(JsonPath.<String>read(json, "$.objects[0].package.author.name")).isEqualTo("Ann");
    assertThat(JsonPath.<String>read(json, "$.objects[0].package.publisher.username"))
        .isEqualTo("ann");
    assertThat(JsonPath.<String>read(json, "$.objects[0].package.maintainers[0].email"))
        .isEqualTo("ann@example.com");
    assertThat(JsonPath.<Double>read(json, "$.objects[0].score.final")).isEqualTo(1.0);
    assertThat(JsonPath.<Double>read(json, "$.objects[0].score.detail.quality")).isEqualTo(1.0);
    assertThat(JsonPath.<Double>read(json, "$.objects[0].searchScore")).isGreaterThan(1000.0);
  }

  @Test
  @DisplayName("always has maintainers, keywords and links, even for a package that has none")
  void alwaysHasArrays() throws Exception {
    final var repo = this.seedPackages(false, this.adminToken());

    final var json = this.search(repo, "text", "is-odd");

    assertThat(JsonPath.<List<Object>>read(json, "$.objects[0].package.maintainers")).isNotNull();
    assertThat(JsonPath.<List<Object>>read(json, "$.objects[0].package.keywords")).isEmpty();
    assertThat(JsonPath.<Map<String, Object>>read(json, "$.objects[0].package.links")).isNotNull();
  }

  @Test
  @DisplayName("an empty or missing text lists every package of the repo")
  void listsEverything() throws Exception {
    final var repo = this.seedPackages(false, this.adminToken());

    for (final var json : List.of(this.search(repo), this.search(repo, "text", ""))) {
      assertThat(JsonPath.<Integer>read(json, "$.total")).isEqualTo(4);
      assertThat(names(json))
          .containsExactlyInAnyOrder("left-pad", "@acme/right-pad", "@acme/widget", "is-odd");
    }
  }

  @Test
  @DisplayName("ranks the whole-name match first and requires every term")
  void ranksAndRequiresEveryTerm() throws Exception {
    final var repo = this.seedPackages(false, this.adminToken());

    assertThat(names(this.search(repo, "text", "pad")))
        .containsExactlyInAnyOrder("left-pad", "@acme/right-pad");
    assertThat(names(this.search(repo, "text", "pad string"))).containsExactly("left-pad");
    assertThat(names(this.search(repo, "text", "is-odd pad"))).isEmpty();
    assertThat(names(this.search(repo, "text", "@acme/widget"))).containsExactly("@acme/widget");
    assertThat(names(this.search(repo, "text", "odd numbers"))).containsExactly("is-odd");
  }

  @Test
  @DisplayName("filters on the scope and keywords qualifiers")
  void qualifiers() throws Exception {
    final var repo = this.seedPackages(false, this.adminToken());

    assertThat(names(this.search(repo, "text", "scope:acme")))
        .containsExactlyInAnyOrder("@acme/right-pad", "@acme/widget");
    assertThat(names(this.search(repo, "text", "scope:@acme pad")))
        .containsExactly("@acme/right-pad");
    assertThat(names(this.search(repo, "text", "keywords:ui"))).containsExactly("@acme/widget");
    assertThat(names(this.search(repo, "text", "keywords:ui,string")))
        .containsExactlyInAnyOrder("@acme/widget", "left-pad");
    assertThat(names(this.search(repo, "text", "is:shiny is-odd"))).containsExactly("is-odd");
  }

  @Test
  @DisplayName("pages with size and from, and total counts every match")
  void paging() throws Exception {
    final var repo = this.seedPackages(false, this.adminToken());

    final var first = this.search(repo, "size", "1", "from", "0");
    final var second = this.search(repo, "size", "1", "from", "1");
    final var beyond = this.search(repo, "size", "2", "from", "10");

    assertThat(JsonPath.<Integer>read(first, "$.total")).isEqualTo(4);
    assertThat(names(first)).hasSize(1);
    assertThat(names(second)).hasSize(1).doesNotContainAnyElementsOf(names(first));
    assertThat(names(beyond)).isEmpty();
    assertThat(JsonPath.<Integer>read(beyond, "$.total")).isEqualTo(4);
  }

  @Test
  @DisplayName("ignores parameters it does not know, such as the ranking weights of npms")
  void unknownParameters() throws Exception {
    final var repo = this.seedPackages(false, this.adminToken());

    final var json = this.search(repo, "quality", "0.5", "popularity", "1", "maintenance", "0");

    assertThat(names(json)).hasSize(4);
  }

  @Test
  @DisplayName("finds only the packages of the repo in the URL")
  void otherReposAreExcluded() throws Exception {
    final var token = this.adminToken();
    final var repo = this.seedPackages(false, token);
    final var other = this.seedRepo(RepoType.NPM, uniqueRepoName("search"), false, null);
    this.publish(other, token, "other-pad", Map.of("description", "pads elsewhere"));

    assertThat(names(this.search(repo, "text", "other-pad"))).isEmpty();
    assertThat(names(this.search(other, "text", "pad"))).containsExactly("other-pad");
    assertThat(names(this.search(other))).containsExactly("other-pad");
  }

  @Test
  @DisplayName("uses only the latest version of a package")
  void latestVersionOnly() throws Exception {
    final var token = this.adminToken();
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("search"), false, null);
    this.publish(repo, token, "evolving", Map.of("description", "the first version"));
    assertThat(
            this.protocol(
                    put("/{repo}/{name}", repo.getName(), "evolving")
                        .header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            NpmPublishBodies.body(
                                this.objectMapper,
                                repo.getName(),
                                "evolving",
                                "2.0.0",
                                Map.of("description", "the second version"))))
                .getStatus())
        .isEqualTo(200);

    final var json = this.search(repo, "text", "evolving");

    assertThat(JsonPath.<String>read(json, "$.objects[0].package.version")).isEqualTo("2.0.0");
    assertThat(names(this.search(repo, "text", "first version"))).isEmpty();
    assertThat(names(this.search(repo, "text", "second version"))).containsExactly("evolving");
  }

  @Test
  @DisplayName("takes % and _ in the text literally")
  void likeCharactersAreLiteral() throws Exception {
    final var repo = this.seedPackages(false, this.adminToken());

    assertThat(names(this.search(repo, "text", "%"))).isEmpty();
    assertThat(names(this.search(repo, "text", "_"))).isEmpty();
    assertThat(names(this.search(repo, "text", "left_pad"))).isEmpty();
    assertThat(names(this.search(repo, "text", "left-pad"))).containsExactly("left-pad");
  }

  @Test
  @DisplayName("of a private repo needs credentials")
  void privateRepo() throws Exception {
    final var token = this.adminToken();
    final var repo = this.seedPackages(true, token);

    final var anonymous = this.protocol(get(SEARCH, repo.getName()));
    final var authenticated =
        this.protocol(get(SEARCH, repo.getName()).header(AUTHORIZATION, token));

    assertThat(anonymous.getStatus()).isEqualTo(401);
    assertThat(anonymous.getHeader(WWW_AUTHENTICATE)).startsWith("Basic");
    assertThat(authenticated.getStatus()).isEqualTo(200);
    assertThat(JsonPath.<Integer>read(authenticated.getContentAsString(), "$.total")).isEqualTo(4);
  }

  @Test
  @DisplayName("does not shadow a package that is called search")
  void packageNamedSearch() throws Exception {
    final var token = this.adminToken();
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("search"), false, null);
    this.publish(repo, token, "search", Map.of("description", "searching"));

    final var packument = this.protocol(get("/{repo}/search", repo.getName()));

    assertThat(packument.getStatus()).isEqualTo(200);
    assertThat(JsonPath.<String>read(packument.getContentAsString(), "$.name")).isEqualTo("search");
    assertThat(names(this.search(repo, "text", "search"))).containsExactly("search");
  }

  private static List<String> names(
      final io.repsy.protocols.npm.shared.search.NpmSearchResult result) {
    return result.objects().stream().map(object -> object.pkg().name()).toList();
  }

  /** The service with a cap far below the number of packages of the repo. */
  private NpmSearchServiceImpl cappedService(final int cap) {
    return new NpmSearchServiceImpl(
        this.candidateRepository, this.keywordRepository, this.maintainerRepository, cap);
  }

  private static BaseRepoInfo<UUID> infoOf(final Repo repo) {
    return BaseRepoInfo.<UUID>builder().name(repo.getName()).storageKey(repo.getId()).build();
  }

  @Test
  @DisplayName("when the cap cuts the matches, the best ones stay and total counts every match")
  void capKeepsTheBestMatches() throws Exception {
    final var token = this.adminToken();
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("search"), false, null);
    for (final var name : List.of("aaa-pad", "pad", "padding", "zzz", "b-pad", "c-pad")) {
      this.publish(repo, token, name, Map.of("description", "a package"));
    }

    final var result =
        this.cappedService(2).search(infoOf(repo), NpmSearchQuery.parse("pad", "20", "0"));

    // Six packages, five of them match. Alphabetically the first two would be aaa-pad and b-pad.
    assertThat(result.total()).isEqualTo(5);
    assertThat(names(result)).containsExactly("pad", "padding");
    assertThat(
            names(
                this.cappedService(50)
                    .search(infoOf(repo), NpmSearchQuery.parse("pad", "20", "0"))))
        .containsExactly("pad", "padding", "aaa-pad", "b-pad", "c-pad");
  }

  @Test
  @DisplayName("the keywords filter is applied in the database, before the cap")
  void keywordsAreFilteredBeforeTheCap() throws Exception {
    final var token = this.adminToken();
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("search"), false, null);
    for (final var name : List.of("a1", "a2", "a3")) {
      this.publish(repo, token, name, Map.of("keywords", List.of("other")));
    }
    this.publish(repo, token, "z-ui", Map.of("keywords", List.of("UI")));

    final var result =
        this.cappedService(2).search(infoOf(repo), NpmSearchQuery.parse("keywords:ui", null, null));

    assertThat(names(result)).containsExactly("z-ui");
    assertThat(result.total()).isEqualTo(1);
  }

  @Test
  @DisplayName("the total of the wire answer is the true count of the matches")
  void totalOnTheWire() throws Exception {
    final var repo = this.seedPackages(false, this.adminToken());

    assertThat(JsonPath.<Integer>read(this.search(repo, "text", "pad", "size", "1"), "$.total"))
        .isEqualTo(2);
  }
}
