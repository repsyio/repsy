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
package io.repsy.os.server.protocols.shared.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Full-stack integration tests for the repository collection of the panel API (RPS-1268): {@code
 * GET /api/repos}, {@code GET /api/repos/counts} and {@code POST /api/repos}, served by {@code
 * RepoCollectionController}.
 *
 * <p>The database holds the default repos the application seeds at startup and whatever other
 * classes committed, so every list assertion narrows the list with a unique {@code q} and every
 * count is compared with a baseline read in the same transaction. The embedded-H2 counterpart of
 * the query is {@code H2RepoCollectionIT}.
 */
@DisplayName("RepoCollectionController /api/repos")
class RepoCollectionControllerIT extends AbstractIntegrationTest {

  private static final String REPOS = "/api/repos";
  private static final String COUNTS = "/api/repos/counts";
  private static final String[] REPO_LIST_KEYS = {
    "name", "type", "privateRepo", "diskUsage", "createdAt", "description"
  };
  private static final Instant TIED_AT = Instant.parse("2026-03-04T05:06:07Z");

  @MockitoBean private UsageUpdateService usageUpdateService;

  // ---------------------------------------------------------------------------------------------
  // Fixtures and helpers
  // ---------------------------------------------------------------------------------------------

  /** A tag that is in no other repo name, so {@code q=<tag>} selects exactly this test's repos. */
  private static String tag() {
    return "t" + randomTag();
  }

  private Repo insertRepo(final String name, final RepoType type) {
    return this.insertRepo(name, type, 0);
  }

  private Repo insertRepo(final String name, final RepoType type, final long diskUsage) {
    final var repo = new Repo();
    repo.setName(name);
    repo.setType(type);
    repo.setPrivateRepo(false);
    repo.setAllowOverride(true);
    repo.setSecurityScanEnabled(true);
    repo.setDiskUsage(diskUsage);

    return this.repoRepository.saveAndFlush(repo);
  }

  private static long directoryCount(final RepoType type) throws IOException {
    try (var children = Files.list(STORAGE_ROOT.resolve(protocolDir(type)))) {
      return children.count();
    }
  }

  private ResultActions list(final String authHeader, final String... params) throws Exception {
    final var request = get(REPOS).header(AUTHORIZATION, authHeader);
    for (var i = 0; i < params.length; i += 2) {
      request.param(params[i], params[i + 1]);
    }

    return this.perform(request);
  }

  private String listBody(final String... params) throws Exception {
    return expectSuccess(
        this.list(this.userBearerToken(), params), "reposFetched", "Repos fetched.");
  }

  private List<String> names(final String body) {
    return JsonPath.read(body, "$.data.content[*].name");
  }

  private List<String> listedNames(final String... params) throws Exception {
    return this.names(this.listBody(params));
  }

  private static MockHttpServletRequestBuilder json(
      final MockHttpServletRequestBuilder request, final String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static String createBody(final String name, final String type) {
    return "{\"name\":\"%s\",\"type\":%s}"
        .formatted(name, type == null ? "null" : "\"" + type + "\"");
  }

  private ResultActions create(final String authHeader, final String body) throws Exception {
    return this.perform(json(post(REPOS), body).header(AUTHORIZATION, authHeader));
  }

  private static void expectValidationError(final ResultActions result, final String data)
      throws Exception {
    expectError(
        result,
        HttpStatus.BAD_REQUEST,
        "validationError",
        data,
        "Incoming data couldn't be validated.");
  }

  // ---------------------------------------------------------------------------------------------
  // GET /api/repos
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("GET /api/repos")
  class ListRepos {

    @Test
    @DisplayName("lists repos of every type with the list item shape and the page metadata")
    void shape() throws Exception {
      final var tag = tag();
      final var repo = RepoCollectionControllerIT.this.insertRepo(tag + "-shape", RepoType.NPM, 42);
      repo.setDescription("shaped");
      RepoCollectionControllerIT.this.repoRepository.saveAndFlush(repo);

      final var body = RepoCollectionControllerIT.this.listBody("q", tag);

      assertThat(JsonPath.<Map<String, Object>>read(body, "$.data.content[0]"))
          .containsOnlyKeys(REPO_LIST_KEYS)
          .containsEntry("name", tag + "-shape")
          .containsEntry("type", "NPM")
          .containsEntry("privateRepo", false)
          .containsEntry("diskUsage", 42)
          .containsEntry("description", "shaped");
      assertThat(JsonPath.<String>read(body, "$.data.content[0].createdAt")).isNotBlank();
      assertThat(JsonPath.<Map<String, Object>>read(body, "$.data.page"))
          .containsEntry("size", 10)
          .containsEntry("number", 0)
          .containsEntry("totalElements", 1)
          .containsEntry("totalPages", 1);
    }

    @Test
    @DisplayName("filters by type, by name, and by both")
    void filters() throws Exception {
      final var tag = tag();
      RepoCollectionControllerIT.this.insertRepo(tag + "-m1", RepoType.MAVEN);
      RepoCollectionControllerIT.this.insertRepo(tag + "-m2", RepoType.MAVEN);
      RepoCollectionControllerIT.this.insertRepo(tag + "-n1", RepoType.NPM);

      assertThat(RepoCollectionControllerIT.this.listedNames("q", tag))
          .containsExactlyInAnyOrder(tag + "-m1", tag + "-m2", tag + "-n1");
      assertThat(RepoCollectionControllerIT.this.listedNames("q", tag, "type", "MAVEN"))
          .containsExactlyInAnyOrder(tag + "-m1", tag + "-m2");
      assertThat(RepoCollectionControllerIT.this.listedNames("q", tag, "type", "NPM"))
          .containsExactly(tag + "-n1");
      assertThat(RepoCollectionControllerIT.this.listedNames("q", tag + "-m", "type", "NPM"))
          .isEmpty();
      assertThat(RepoCollectionControllerIT.this.listedNames("q", tag + "-M1"))
          .as("q ignores case")
          .containsExactly(tag + "-m1");
    }

    @Test
    @DisplayName("filters by type alone and returns only that type")
    void typeAlone() throws Exception {
      final var tag = tag();
      RepoCollectionControllerIT.this.insertRepo(tag + "-h", RepoType.HELM);

      final var body = RepoCollectionControllerIT.this.listBody("type", "HELM", "size", "100");

      assertThat(JsonPath.<List<String>>read(body, "$.data.content[*].type"))
          .isNotEmpty()
          .containsOnly("HELM");
      assertThat(RepoCollectionControllerIT.this.names(body)).contains(tag + "-h");
    }

    @ParameterizedTest(name = "type={0}")
    @ValueSource(strings = {"maven", "Maven", "MAVEN"})
    @DisplayName("accepts the type in any case")
    void typeIsCaseInsensitive(final String type) throws Exception {
      final var tag = tag();
      RepoCollectionControllerIT.this.insertRepo(tag + "-m", RepoType.MAVEN);
      RepoCollectionControllerIT.this.insertRepo(tag + "-n", RepoType.NPM);

      assertThat(RepoCollectionControllerIT.this.listedNames("q", tag, "type", type))
          .containsExactly(tag + "-m");
    }

    @Test
    @DisplayName("lists every type when type is blank")
    void blankType() throws Exception {
      final var tag = tag();
      RepoCollectionControllerIT.this.insertRepo(tag + "-m", RepoType.MAVEN);
      RepoCollectionControllerIT.this.insertRepo(tag + "-n", RepoType.NPM);

      assertThat(RepoCollectionControllerIT.this.listedNames("q", tag, "type", ""))
          .containsExactlyInAnyOrder(tag + "-m", tag + "-n");
    }

    @ParameterizedTest(name = "type={0}")
    @ValueSource(strings = {"FOO", "mvn", "MAVEN,NPM", "1"})
    @DisplayName("returns 400 validationError naming type for an unknown type")
    void unknownType(final String type) throws Exception {
      expectValidationError(
          RepoCollectionControllerIT.this.list(
              RepoCollectionControllerIT.this.userBearerToken(), "type", type),
          "type");
    }

    @Test
    @DisplayName("takes the % _ and backslash of q literally, not as LIKE wildcards")
    void queryWildcardsAreLiteral() throws Exception {
      final var tag = tag();
      RepoCollectionControllerIT.this.insertRepo(tag + "a_c", RepoType.MAVEN);
      RepoCollectionControllerIT.this.insertRepo(tag + "abc", RepoType.MAVEN);
      RepoCollectionControllerIT.this.insertRepo(tag + "a-c", RepoType.MAVEN);

      assertThat(RepoCollectionControllerIT.this.listedNames("q", tag + "a_c"))
          .as("_ matches an underscore only, not any character")
          .containsExactly(tag + "a_c");
      assertThat(RepoCollectionControllerIT.this.listedNames("q", tag + "a%"))
          .as("names contain no %, so a literal % matches nothing")
          .isEmpty();
      assertThat(RepoCollectionControllerIT.this.listedNames("q", "%" + tag))
          .as("a leading % is not a wildcard either")
          .isEmpty();
      assertThat(RepoCollectionControllerIT.this.listedNames("q", tag + "a\\"))
          .as("a backslash is not an escape character")
          .isEmpty();
      assertThat(RepoCollectionControllerIT.this.listedNames("q", "_", "size", "100"))
          .as("a lone _ matches the names that contain an underscore")
          .contains(tag + "a_c")
          .doesNotContain(tag + "abc", tag + "a-c");
      assertThat(RepoCollectionControllerIT.this.listedNames("q", "%"))
          .as("a lone % is not every repo")
          .isEmpty();
    }

    @ParameterizedTest(name = "q=\"{0}\"")
    @ValueSource(strings = {"", " ", "   "})
    @DisplayName("lists every repo when q is empty or blank")
    void blankQuery(final String q) throws Exception {
      final var tag = tag();
      RepoCollectionControllerIT.this.insertRepo(tag + "-x", RepoType.CARGO);

      final var body = RepoCollectionControllerIT.this.listBody("q", q, "size", "100");

      assertThat(JsonPath.<Number>read(body, "$.data.page.totalElements").longValue())
          .isEqualTo(RepoCollectionControllerIT.this.repoRepository.count());
    }

    @Test
    @DisplayName("shows every repo of a tied sort key once, in the same order on every read")
    void tiedRowsPageStably() throws Exception {
      final var tag = tag();
      final var expected = new ArrayList<String>();
      for (var i = 0; i < 27; i++) {
        final var name = tag + "-" + i;
        RepoCollectionControllerIT.this.insertRepo(name, RepoType.values()[i % 9]);
        expected.add(name);
      }
      RepoCollectionControllerIT.this.jdbcTemplate.update(
          "update repo set created_at = ? where name like ?", Timestamp.from(TIED_AT), tag + "-%");
      RepoCollectionControllerIT.this.entityManager.clear();

      final var first = this.readAllPages(tag);
      final var second = this.readAllPages(tag);

      assertThat(first).hasSameSizeAs(expected).doesNotHaveDuplicates();
      assertThat(first).containsExactlyInAnyOrderElementsOf(expected);
      assertThat(second).isEqualTo(first);
    }

    private List<String> readAllPages(final String tag) throws Exception {
      final var names = new ArrayList<String>();
      var pages = 1;

      for (var page = 0; page < pages; page++) {
        final var body =
            RepoCollectionControllerIT.this.listBody(
                "q", tag, "page", String.valueOf(page), "size", "10");

        names.addAll(RepoCollectionControllerIT.this.names(body));
        pages = JsonPath.<Integer>read(body, "$.data.page.totalPages");
      }

      return names;
    }

    @Test
    @DisplayName("pages with the requested size and reports the totals")
    void paging() throws Exception {
      final var tag = tag();
      for (var i = 0; i < 5; i++) {
        RepoCollectionControllerIT.this.insertRepo(tag + "-" + i, RepoType.GOLANG);
      }

      final var last = RepoCollectionControllerIT.this.listBody("q", tag, "page", "2", "size", "2");

      assertThat(RepoCollectionControllerIT.this.names(last)).hasSize(1);
      assertThat(JsonPath.<Map<String, Object>>read(last, "$.data.page"))
          .containsEntry("size", 2)
          .containsEntry("number", 2)
          .containsEntry("totalElements", 5)
          .containsEntry("totalPages", 3);
      assertThat(RepoCollectionControllerIT.this.listedNames("q", tag, "page", "3", "size", "2"))
          .isEmpty();
    }

    @Test
    @DisplayName("defaults to the newest repos first, 10 to a page")
    void defaultOrderAndSize() throws Exception {
      final var tag = tag();
      for (var i = 0; i < 12; i++) {
        final var repo = RepoCollectionControllerIT.this.insertRepo(tag + "-" + i, RepoType.RUBY);
        RepoCollectionControllerIT.this.jdbcTemplate.update(
            "update repo set created_at = ? where id = ?",
            Timestamp.from(TIED_AT.plusSeconds(i)),
            repo.getId());
      }
      RepoCollectionControllerIT.this.entityManager.clear();

      final var names = RepoCollectionControllerIT.this.listedNames("q", tag);

      assertThat(names).hasSize(10).first().isEqualTo(tag + "-11");
      assertThat(names).last().isEqualTo(tag + "-2");
    }

    @Test
    @DisplayName("sorts by name, type, diskUsage and createdAt in the requested direction")
    void allowedSorts() throws Exception {
      final var tag = tag();
      RepoCollectionControllerIT.this.insertRepo(tag + "-b", RepoType.NPM, 300);
      RepoCollectionControllerIT.this.insertRepo(tag + "-c", RepoType.MAVEN, 100);
      RepoCollectionControllerIT.this.insertRepo(tag + "-a", RepoType.PYPI, 200);

      assertThat(RepoCollectionControllerIT.this.listedNames("q", tag, "sort", "name,asc"))
          .containsExactly(tag + "-a", tag + "-b", tag + "-c");
      assertThat(RepoCollectionControllerIT.this.listedNames("q", tag, "sort", "name,desc"))
          .containsExactly(tag + "-c", tag + "-b", tag + "-a");
      assertThat(RepoCollectionControllerIT.this.listedNames("q", tag, "sort", "diskUsage,desc"))
          .containsExactly(tag + "-b", tag + "-a", tag + "-c");
      assertThat(RepoCollectionControllerIT.this.listedNames("q", tag, "sort", "diskUsage,asc"))
          .containsExactly(tag + "-c", tag + "-a", tag + "-b");
      assertThat(RepoCollectionControllerIT.this.listedNames("q", tag, "sort", "type,asc"))
          .containsExactly(tag + "-c", tag + "-b", tag + "-a");
      assertThat(RepoCollectionControllerIT.this.listedNames("q", tag, "sort", "createdAt,asc"))
          .hasSize(3);
    }

    @ParameterizedTest(name = "sort={0}")
    @ValueSource(strings = {"id,asc", "privateRepo,asc", "description", "nope,desc", "name,asc,id"})
    @DisplayName("returns 400 validationError naming sort for a property that is not allowed")
    void rejectedSort(final String sort) throws Exception {
      expectValidationError(
          RepoCollectionControllerIT.this.list(
              RepoCollectionControllerIT.this.userBearerToken(), "sort", sort),
          "sort");
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("badPaging")
    @DisplayName("returns 400 validationError naming the parameter for a bad page or size")
    void badPaging(final String parameter, final String value) throws Exception {
      expectValidationError(
          RepoCollectionControllerIT.this.list(
              RepoCollectionControllerIT.this.userBearerToken(), parameter, value),
          parameter);
    }

    static Stream<Arguments> badPaging() {
      return Stream.of(
          Arguments.of("size", "101"),
          Arguments.of("size", "0"),
          Arguments.of("size", "-1"),
          Arguments.of("size", "ten"),
          Arguments.of("page", "-1"),
          Arguments.of("page", "one"));
    }

    @Test
    @DisplayName("accepts the largest size, 100")
    void maxSize() throws Exception {
      final var body = RepoCollectionControllerIT.this.listBody("size", "100");

      assertThat(JsonPath.<Integer>read(body, "$.data.page.size")).isEqualTo(100);
    }

    @Test
    @DisplayName("gives a USER and an ADMIN the same list")
    void sameListForEveryRole() throws Exception {
      final var tag = tag();
      RepoCollectionControllerIT.this.insertRepo(tag + "-a", RepoType.DOCKER);
      final var hidden = RepoCollectionControllerIT.this.insertRepo(tag + "-p", RepoType.DOCKER);
      hidden.setPrivateRepo(true);
      RepoCollectionControllerIT.this.repoRepository.saveAndFlush(hidden);

      final var asUser =
          expectSuccess(
              RepoCollectionControllerIT.this.list(
                  RepoCollectionControllerIT.this.userBearerToken(), "q", tag, "sort", "name,asc"),
              "reposFetched",
              "Repos fetched.");
      final var asAdmin =
          expectSuccess(
              RepoCollectionControllerIT.this.list(
                  RepoCollectionControllerIT.this.adminBearerToken(), "q", tag, "sort", "name,asc"),
              "reposFetched",
              "Repos fetched.");

      assertThat(JsonPath.<Object>read(asUser, "$.data"))
          .isEqualTo(JsonPath.read(asAdmin, "$.data"));
      assertThat(RepoCollectionControllerIT.this.names(asUser))
          .containsExactly(tag + "-a", tag + "-p");
    }

    @Test
    @DisplayName("keeps a renamed repo in its place under its new name, and drops a deleted one")
    void followsRenameAndDelete() throws Exception {
      final var tag = tag();
      final var admin = RepoCollectionControllerIT.this.adminBearerToken();
      final var oldest = RepoCollectionControllerIT.this.seedRepo(RepoType.CARGO, tag + "-zulu");
      final var middle = RepoCollectionControllerIT.this.seedRepo(RepoType.CARGO, tag + "-alpha");
      final var newest = RepoCollectionControllerIT.this.seedRepo(RepoType.CARGO, tag + "-bravo");
      final var renamed = tag + "-aardvark";

      assertThat(RepoCollectionControllerIT.this.listedNames("q", tag))
          .containsExactly(newest.getName(), middle.getName(), oldest.getName());

      expectSuccess(
          RepoCollectionControllerIT.this.perform(
              json(
                      patch("/api/repos/" + oldest.getName() + "/name"),
                      "{\"name\":\"%s\"}".formatted(renamed))
                  .header(AUTHORIZATION, admin)),
          "repoRenamed",
          "Repo renamed.");

      assertThat(RepoCollectionControllerIT.this.listedNames("q", tag))
          .containsExactly(newest.getName(), middle.getName(), renamed);

      expectSuccess(
          RepoCollectionControllerIT.this.perform(
              delete("/api/repos/" + middle.getName()).header(AUTHORIZATION, admin)),
          "repoDeleted",
          "Repo deleted.");

      assertThat(RepoCollectionControllerIT.this.listedNames("q", tag))
          .containsExactly(newest.getName(), renamed);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // GET /api/repos/counts
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("GET /api/repos/counts")
  class Counts {

    private Map<String, Object> counts(final String authHeader) throws Exception {
      final var body =
          expectSuccess(
              RepoCollectionControllerIT.this.perform(
                  get(COUNTS).header(AUTHORIZATION, authHeader)),
              "repoCountsFetched",
              "Repo counts fetched.");

      return JsonPath.read(body, "$.data");
    }

    @Test
    @DisplayName("has a key for every repo type, with the real count of each")
    void everyTypeWithItsCount() throws Exception {
      final var before = new EnumMap<RepoType, Long>(RepoType.class);
      for (final var type : RepoType.values()) {
        before.put(type, RepoCollectionControllerIT.this.repoRepository.countAllByType(type));
      }
      final var tag = tag();
      RepoCollectionControllerIT.this.insertRepo(tag + "-1", RepoType.NUGET);
      RepoCollectionControllerIT.this.insertRepo(tag + "-2", RepoType.NUGET);
      RepoCollectionControllerIT.this.insertRepo(tag + "-3", RepoType.HELM);

      final var counts = this.counts(RepoCollectionControllerIT.this.adminBearerToken());

      assertThat(counts)
          .containsOnlyKeys(
              Arrays.stream(RepoType.values()).map(Enum::name).toArray(String[]::new));
      for (final var type : RepoType.values()) {
        final var added = type == RepoType.NUGET ? 2 : type == RepoType.HELM ? 1 : 0;
        assertThat(((Number) counts.get(type.name())).longValue())
            .as(type.name())
            .isEqualTo(before.get(type) + added);
      }
    }

    @Test
    @DisplayName("has every type as a key, with 0, when there is no repo at all")
    void zerosForEmptyTypes() throws Exception {
      RepoCollectionControllerIT.this.deleteDefaultRepos();

      final var counts = this.counts(RepoCollectionControllerIT.this.userBearerToken());

      assertThat(counts).hasSize(9);
      assertThat(counts.values()).allSatisfy(count -> assertThat(count).isEqualTo(0));
    }

    @Test
    @DisplayName("follows a create and a delete")
    void followsCreateAndDelete() throws Exception {
      final var admin = RepoCollectionControllerIT.this.adminBearerToken();
      final var before = ((Number) this.counts(admin).get("NPM")).longValue();
      final var first =
          RepoCollectionControllerIT.this.seedRepo(RepoType.NPM, uniqueRepoName("cnt1"));
      RepoCollectionControllerIT.this.seedRepo(RepoType.NPM, uniqueRepoName("cnt2"));

      assertThat(((Number) this.counts(admin).get("NPM")).longValue()).isEqualTo(before + 2);

      expectSuccess(
          RepoCollectionControllerIT.this.perform(
              delete("/api/repos/" + first.getName()).header(AUTHORIZATION, admin)),
          "repoDeleted",
          "Repo deleted.");

      assertThat(((Number) this.counts(admin).get("NPM")).longValue()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("gives a USER and an ADMIN the same counts")
    void sameCountsForEveryRole() throws Exception {
      assertThat(this.counts(RepoCollectionControllerIT.this.userBearerToken()))
          .isEqualTo(this.counts(RepoCollectionControllerIT.this.adminBearerToken()));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // POST /api/repos
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("POST /api/repos")
  class Create {

    @ParameterizedTest(name = "{0}")
    @EnumSource(RepoType.class)
    @DisplayName("creates the repo and its storage directory and returns the created list item")
    void createsEveryType(final RepoType type) throws Exception {
      final var name = uniqueRepoName(type.name().toLowerCase(Locale.ROOT));

      final var body =
          expectSuccess(
              RepoCollectionControllerIT.this.create(
                  RepoCollectionControllerIT.this.adminBearerToken(),
                  "{\"name\":\"%s\",\"type\":\"%s\",\"privateRepo\":true,\"description\":\"made\"}"
                      .formatted(name, type.name())),
              "repoCreated",
              "Repo created.");

      assertThat(JsonPath.<Map<String, Object>>read(body, "$.data"))
          .containsOnlyKeys(REPO_LIST_KEYS)
          .containsEntry("name", name)
          .containsEntry("type", type.name())
          .containsEntry("privateRepo", true)
          .containsEntry("diskUsage", 0)
          .containsEntry("description", "made");
      assertThat(JsonPath.<String>read(body, "$.data.createdAt")).isNotBlank();

      final var repo = RepoCollectionControllerIT.this.reloadRepo(name);
      assertThat(repo.getType()).isEqualTo(type);
      assertThat(repo.isPrivateRepo()).isTrue();
      assertThat(repo.getDescription()).isEqualTo("made");
      assertThat(repo.isAllowOverride()).isTrue();
      assertThat(storageDirOf(repo)).isDirectory();
      verifyNoInteractions(RepoCollectionControllerIT.this.usageUpdateService);
    }

    @ParameterizedTest(name = "type={0}")
    @ValueSource(strings = {"maven", "Maven", "mAvEn", "MAVEN", " maven "})
    @DisplayName("accepts the type in the body in any case and answers with the upper-case type")
    void typeInTheBodyIsCaseInsensitive(final String type) throws Exception {
      final var name = uniqueRepoName("case");

      final var body =
          expectSuccess(
              RepoCollectionControllerIT.this.create(
                  RepoCollectionControllerIT.this.adminBearerToken(), createBody(name, type)),
              "repoCreated",
              "Repo created.");

      assertThat(JsonPath.<String>read(body, "$.data.type")).isEqualTo("MAVEN");
      assertThat(RepoCollectionControllerIT.this.reloadRepo(name).getType())
          .isEqualTo(RepoType.MAVEN);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(RepoType.class)
    @DisplayName("accepts the lower-case slug of every type in the body")
    void lowerCaseBodyForEveryType(final RepoType type) throws Exception {
      final var name = uniqueRepoName("lower");

      final var body =
          expectSuccess(
              RepoCollectionControllerIT.this.create(
                  RepoCollectionControllerIT.this.adminBearerToken(),
                  createBody(name, type.name().toLowerCase(Locale.ROOT))),
              "repoCreated",
              "Repo created.");

      assertThat(JsonPath.<String>read(body, "$.data.type")).isEqualTo(type.name());
      assertThat(RepoCollectionControllerIT.this.reloadRepo(name).getType()).isEqualTo(type);
    }

    @Test
    @DisplayName("creates a public repo without a description when only name and type are sent")
    void defaults() throws Exception {
      final var name = uniqueRepoName("defaults");

      final var body =
          expectSuccess(
              RepoCollectionControllerIT.this.create(
                  RepoCollectionControllerIT.this.adminBearerToken(), createBody(name, "MAVEN")),
              "repoCreated",
              "Repo created.");

      assertThat(JsonPath.<Map<String, Object>>read(body, "$.data"))
          .containsEntry("privateRepo", false)
          .doesNotContainKey("description");
    }

    @Test
    @DisplayName("lists the new repo, and the repo routes and DELETE reach it")
    void listedAndUsable() throws Exception {
      final var name = uniqueRepoName("madenew");
      final var admin = RepoCollectionControllerIT.this.adminBearerToken();

      expectSuccess(
          RepoCollectionControllerIT.this.create(admin, createBody(name, "CARGO")),
          "repoCreated",
          "Repo created.");

      assertThat(RepoCollectionControllerIT.this.listedNames("q", name, "type", "CARGO"))
          .containsExactly(name);
      RepoCollectionControllerIT.this
          .perform(get("/api/repos/" + name + "/settings").header(AUTHORIZATION, admin))
          .andExpect(status().isOk());
      RepoCollectionControllerIT.this
          .perform(delete("/api/repos/" + name).header(AUTHORIZATION, admin))
          .andExpect(status().isOk());
      assertThat(RepoCollectionControllerIT.this.repoRepository.findByName(name)).isEmpty();
    }

    @Test
    @DisplayName("creates nothing through the removed POST /api/repos/{repoType}")
    void removedPerTypeCreateCreatesNothing() throws Exception {
      final var name = uniqueRepoName("oldurl");
      final var dirsBefore = directoryCount(RepoType.MAVEN);

      expectError(
          RepoCollectionControllerIT.this.perform(
              json(post("/api/repos/MAVEN"), "{\"name\":\"%s\"}".formatted(name))
                  .header(AUTHORIZATION, RepoCollectionControllerIT.this.adminBearerToken())),
          HttpStatus.NOT_FOUND,
          "itemNotFound",
          null,
          "The requested item is not found.");

      assertThat(RepoCollectionControllerIT.this.repoRepository.findByName(name)).isEmpty();
      assertThat(directoryCount(RepoType.MAVEN)).isEqualTo(dirsBefore);
    }

    @ParameterizedTest(name = "privateRepo={0}")
    @ValueSource(strings = {"true", "false", "null"})
    @DisplayName("honors an explicit private flag, and treats null as false")
    void explicitPrivateFlag(final String flag) throws Exception {
      final var name = uniqueRepoName("flag");

      expectSuccess(
          RepoCollectionControllerIT.this.create(
              RepoCollectionControllerIT.this.adminBearerToken(),
              "{\"name\":\"%s\",\"type\":\"DOCKER\",\"privateRepo\":%s}".formatted(name, flag)),
          "repoCreated",
          "Repo created.");

      assertThat(RepoCollectionControllerIT.this.reloadRepo(name).isPrivateRepo())
          .isEqualTo(Boolean.parseBoolean(flag));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"a", "Repo_Name-1", "UPPER", "under_score", "hy-phen", "1234567890"})
    @DisplayName("accepts every character the name pattern allows")
    void acceptsAllowedNames(final String name) throws Exception {
      final var unique = name + "-" + randomTag();

      expectSuccess(
          RepoCollectionControllerIT.this.create(
              RepoCollectionControllerIT.this.adminBearerToken(), createBody(unique, "MAVEN")),
          "repoCreated",
          "Repo created.");

      assertThat(RepoCollectionControllerIT.this.reloadRepo(unique).getName()).isEqualTo(unique);
    }

    @Test
    @DisplayName("accepts a name of exactly 25 characters")
    void acceptsMaxLengthName() throws Exception {
      final var name = "a".repeat(24) + randomTag().charAt(0);

      expectSuccess(
          RepoCollectionControllerIT.this.create(
              RepoCollectionControllerIT.this.adminBearerToken(), createBody(name, "MAVEN")),
          "repoCreated",
          "Repo created.");

      assertThat(RepoCollectionControllerIT.this.reloadRepo(name).getName()).hasSize(25);
    }

    @Test
    @DisplayName("stores a description of 500 characters and rejects one of 501")
    void descriptionOverColumnLength() throws Exception {
      final var admin = RepoCollectionControllerIT.this.adminBearerToken();
      final var ok = uniqueRepoName("d500");
      expectSuccess(
          RepoCollectionControllerIT.this.create(
              admin,
              "{\"name\":\"%s\",\"type\":\"NPM\",\"description\":\"%s\"}"
                  .formatted(ok, "d".repeat(500))),
          "repoCreated",
          "Repo created.");
      assertThat(RepoCollectionControllerIT.this.reloadRepo(ok).getDescription()).hasSize(500);

      final var tooLong = uniqueRepoName("d501");
      final var dirsBefore = directoryCount(RepoType.NPM);
      expectValidationError(
          RepoCollectionControllerIT.this.create(
              admin,
              "{\"name\":\"%s\",\"type\":\"NPM\",\"description\":\"%s\"}"
                  .formatted(tooLong, "d".repeat(501))),
          null);
      assertThat(RepoCollectionControllerIT.this.repoRepository.findByName(tooLong)).isEmpty();
      assertThat(directoryCount(RepoType.NPM)).isEqualTo(dirsBefore);
    }

    @Test
    @DisplayName("returns 403 accessDenied for a plain USER and creates nothing")
    void userCannotCreate() throws Exception {
      final var name = uniqueRepoName("byuser");

      expectError(
          RepoCollectionControllerIT.this.create(
              RepoCollectionControllerIT.this.userBearerToken(), createBody(name, "MAVEN")),
          HttpStatus.FORBIDDEN,
          "accessDenied",
          "accessDenied",
          "Access Denied. Please check your credentials.");

      assertThat(RepoCollectionControllerIT.this.repoRepository.findByName(name)).isEmpty();
    }

    @Test
    @DisplayName("returns 409 repoExists for a name taken by a repo of the same or another type")
    void duplicate() throws Exception {
      final var existing =
          RepoCollectionControllerIT.this.seedRepo(RepoType.MAVEN, uniqueRepoName("dup"));
      final var admin = RepoCollectionControllerIT.this.adminBearerToken();

      for (final var type : List.of("MAVEN", "NPM")) {
        expectError(
            RepoCollectionControllerIT.this.create(admin, createBody(existing.getName(), type)),
            HttpStatus.CONFLICT,
            "repoExists",
            "repoExists",
            "The repository exists. Please try another name.");
      }

      assertThat(RepoCollectionControllerIT.this.reloadRepo(existing.getName()).getType())
          .isEqualTo(RepoType.MAVEN);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(RepoType.class)
    @DisplayName("returns 409 repoExists for the default repo names seeded at startup")
    void startupDefaultNamesAreTaken(final RepoType type) throws Exception {
      final var defaultName = type == RepoType.GOLANG ? "go" : type.name().toLowerCase(Locale.ROOT);
      assertThat(RepoCollectionControllerIT.this.repoRepository.findByName(defaultName))
          .as("startup-seeded default repo '%s'", defaultName)
          .isPresent();

      // Use a different type than the default's, so only the name can clash.
      final var otherType = type == RepoType.MAVEN ? "NPM" : "MAVEN";

      expectError(
          RepoCollectionControllerIT.this.create(
              RepoCollectionControllerIT.this.adminBearerToken(),
              createBody(defaultName, otherType)),
          HttpStatus.CONFLICT,
          "repoExists",
          "repoExists",
          "The repository exists. Please try another name.");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(
        strings = {
          "login",
          "profile",
          "repositories",
          "users",
          "security",
          "not-found",
          "api",
          "assets",
          "counts",
          "security-summary",
          "LOGIN",
          "Users",
          "Counts",
          "SECURITY-SUMMARY"
        })
    @DisplayName("returns 400 repoNameReserved for a reserved name and creates nothing")
    void reservedName(final String name) throws Exception {
      final var dirsBefore = directoryCount(RepoType.MAVEN);

      expectError(
          RepoCollectionControllerIT.this.create(
              RepoCollectionControllerIT.this.adminBearerToken(), createBody(name, "MAVEN")),
          HttpStatus.BAD_REQUEST,
          "repoNameReserved",
          "repoNameReserved",
          "This name is reserved for the panel. Please try another name.");

      assertThat(RepoCollectionControllerIT.this.repoRepository.findByName(name)).isEmpty();
      assertThat(directoryCount(RepoType.MAVEN)).isEqualTo(dirsBefore);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    @DisplayName("returns 400 validationError for an invalid body and creates nothing")
    void invalidBody(final String label, final String body) throws Exception {
      final var rowsBefore = RepoCollectionControllerIT.this.repoRepository.count();
      final var dirsBefore = directoryCount(RepoType.MAVEN);

      expectValidationError(
          RepoCollectionControllerIT.this.create(
              RepoCollectionControllerIT.this.adminBearerToken(), body),
          null);

      assertThat(RepoCollectionControllerIT.this.repoRepository.count()).isEqualTo(rowsBefore);
      assertThat(directoryCount(RepoType.MAVEN)).isEqualTo(dirsBefore);
    }

    static Stream<Arguments> invalidBodies() {
      final var name = "invalid-body";

      return Stream.of(
          Arguments.of("missing type", "{\"name\":\"%s\"}".formatted(name)),
          Arguments.of("null type", createBody(name, null)),
          Arguments.of("unknown type", createBody(name, "FOO")),
          Arguments.of("unknown lower-case type", createBody(name, "mvn")),
          Arguments.of("blank type", createBody(name, " ")),
          Arguments.of("type of the wrong kind", "{\"name\":\"%s\",\"type\":7}".formatted(name)),
          Arguments.of("missing name", "{\"type\":\"MAVEN\"}"),
          Arguments.of("empty object", "{}"),
          Arguments.of("null name", "{\"name\":null,\"type\":\"MAVEN\"}"),
          Arguments.of("blank name", createBody("", "MAVEN")),
          Arguments.of("space in the name", createBody("has space", "MAVEN")),
          Arguments.of("26 characters", createBody("a".repeat(26), "MAVEN")),
          Arguments.of("slash", createBody("a/b", "MAVEN")),
          Arguments.of("dot", createBody("dot.name", "MAVEN")),
          Arguments.of("non-ascii letter", createBody("café", "MAVEN")),
          Arguments.of("path traversal", createBody("..", "MAVEN")),
          Arguments.of(
              "privateRepo of the wrong type",
              "{\"name\":\"ok\",\"type\":\"MAVEN\",\"privateRepo\":\"maybe\"}"),
          Arguments.of("malformed JSON", "{not json"),
          Arguments.of("empty body", ""),
          Arguments.of("array instead of object", "[]"));
    }

    @Test
    @DisplayName("returns 415 unsupportedMediaType when the body has no JSON content type")
    void unsupportedMediaType() throws Exception {
      expectError(
          RepoCollectionControllerIT.this.perform(
              post(REPOS)
                  .content(createBody(uniqueRepoName("nomedia"), "MAVEN"))
                  .header(AUTHORIZATION, RepoCollectionControllerIT.this.adminBearerToken())),
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "unsupportedMediaType",
          null,
          "Unsupported media type.");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Authentication (all three endpoints)
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("authentication")
  class Authentication {

    static Stream<Arguments> endpoints() {
      return Stream.of(
          Arguments.of(
              "GET /api/repos", (Supplier<MockHttpServletRequestBuilder>) () -> get(REPOS)),
          Arguments.of(
              "GET /api/repos/counts", (Supplier<MockHttpServletRequestBuilder>) () -> get(COUNTS)),
          Arguments.of(
              "POST /api/repos",
              (Supplier<MockHttpServletRequestBuilder>)
                  () -> json(post(REPOS), createBody("anon-repo", "MAVEN"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 401 missingRequestHeader without an Authorization header")
    void anonymous(final String label, final Supplier<MockHttpServletRequestBuilder> request)
        throws Exception {
      expectError(
          RepoCollectionControllerIT.this.perform(request.get()),
          HttpStatus.UNAUTHORIZED,
          "missingRequestHeader",
          "Authorization",
          "A required request header is missing.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("does not accept HTTP Basic credentials, not even the seeded admin's")
    void basicIsRefused(final String label, final Supplier<MockHttpServletRequestBuilder> request)
        throws Exception {
      final var admin = RepoCollectionControllerIT.this.seededAdmin();
      assertThat(admin.getUsername()).isEqualTo(SEEDED_ADMIN_USERNAME);

      expectError(
          RepoCollectionControllerIT.this.perform(
              request
                  .get()
                  .header(AUTHORIZATION, basicAuth(SEEDED_ADMIN_USERNAME, SEEDED_ADMIN_PASSWORD))),
          HttpStatus.UNAUTHORIZED,
          "accessNotAllowed",
          "accessNotAllowed",
          "Access isn't allowed.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 401 for a garbage bearer token")
    void garbageToken(final String label, final Supplier<MockHttpServletRequestBuilder> request)
        throws Exception {
      expectError(
          RepoCollectionControllerIT.this.perform(
              request.get().header(AUTHORIZATION, "Bearer not-a-jwt")),
          HttpStatus.UNAUTHORIZED,
          "accessNotAllowed",
          "accessNotAllowed",
          "Access isn't allowed.");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Reserved names of repos that already exist
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("a repo that already carries a name now reserved")
  class ExistingReservedNames {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"counts", "security-summary"})
    @DisplayName("is listed, and DELETE /api/repos/{repoName} still reaches it")
    void stillReachable(final String name) throws Exception {
      final var admin = RepoCollectionControllerIT.this.adminBearerToken();
      RepoCollectionControllerIT.this.deleteDefaultRepos();
      RepoCollectionControllerIT.this.insertRepo(name, RepoType.MAVEN);

      assertThat(RepoCollectionControllerIT.this.listedNames("q", name)).containsExactly(name);

      // The literal sibling GET wins over nothing else: the counts still come from the endpoint.
      if ("counts".equals(name)) {
        final var counts =
            RepoCollectionControllerIT.this
                .perform(get(COUNTS).header(AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.<Number>read(counts, "$.data.MAVEN").longValue()).isEqualTo(1L);
      }

      RepoCollectionControllerIT.this
          .perform(delete("/api/repos/" + name).header(AUTHORIZATION, admin))
          .andExpect(status().isOk());

      assertThat(RepoCollectionControllerIT.this.repoRepository.findByName(name)).isEmpty();
    }
  }
}
