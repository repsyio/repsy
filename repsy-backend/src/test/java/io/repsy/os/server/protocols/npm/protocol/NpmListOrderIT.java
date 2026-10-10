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

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIT;
import io.repsy.os.HeapOrder;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
 * RPS-1614: the queries behind the dist-tags of a package and the keywords and maintainers of a
 * version had no {@code ORDER BY}, so what a client or the panel read came in the order the
 * database kept the rows. The tags are listed by name (as {@code npm dist-tag ls} sorts them), the
 * keywords and maintainers in the order they were published in, which is the order of {@code
 * package.json}.
 */
@DisplayName("npm lists of tags, keywords and maintainers are ordered (RPS-1614)")
class NpmListOrderIT extends AbstractIT {

  private static final String PACKAGE = "order-pkg";
  private static final String DIST_TAG = "/{repo}/-/package/{name}/dist-tags/{tag}";
  private static final String DIST_TAGS = "/{repo}/-/package/{name}/dist-tags";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private ObjectMapper objectMapper;

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private void publish(
      final Repo repo, final String token, final String version, final Map<String, Object> extra)
      throws Exception {
    final var response =
        this.protocol(
            put("/{repo}/{name}", repo.getName(), PACKAGE)
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    NpmPublishBodies.body(
                        this.objectMapper, repo.getName(), PACKAGE, version, extra)));

    assertThat(response.getStatus()).isEqualTo(200);
  }

  private void addTag(final Repo repo, final String token, final String tag, final String version)
      throws Exception {
    final var response =
        this.protocol(
            put(DIST_TAG, repo.getName(), PACKAGE, tag)
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("\"" + version + "\""));

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("the dist-tags are listed by name on the wire and in the panel")
  void distTagsAreListedByName() throws Exception {
    final var token =
        this.protocolBearerTokenFor(this.createUser(uniqueUsername("order"), UserRole.ADMIN));
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("rps1614"), false, null);

    this.publish(repo, token, "1.0.0", Map.of());
    // Added as latest, zulu, beta: neither that order nor the one of a HashMap is by name.
    this.addTag(repo, token, "zulu", "1.0.0");
    this.addTag(repo, token, "beta", "1.0.0");

    final var wire = this.protocol(get(DIST_TAGS, repo.getName(), PACKAGE));

    assertThat(wire.getStatus()).isEqualTo(200);
    assertThat(JsonPath.<Map<String, String>>read(wire.getContentAsString(), "$").keySet())
        .containsExactly("beta", "latest", "zulu");

    final var panel =
        this.perform(
                get("/api/npm/packages/{repo}/{package}/tags", repo.getName(), PACKAGE)
                    .header(AUTHORIZATION, this.adminBearerToken()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(JsonPath.<List<String>>read(panel, "$[*].tag"))
        .containsExactly("beta", "latest", "zulu");
  }

  @Test
  @DisplayName("the keywords and maintainers of a version follow the time they were stored at")
  void keywordsAndMaintainersFollowTheirTimestamps() throws Exception {
    final var token =
        this.protocolBearerTokenFor(this.createUser(uniqueUsername("order"), UserRole.ADMIN));
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("rps1614"), false, null);

    this.publish(
        repo,
        token,
        "1.0.0",
        Map.of(
            "keywords", List.of("zeta", "alpha", "mid"),
            "maintainers",
                List.of(
                    Map.of("name", "zed", "email", "zed@example.com"),
                    Map.of("name", "amy", "email", "amy@example.com"),
                    Map.of("name", "moe", "email", "moe@example.com"))));

    // The rows are stored in package.json order. Give them times apart, in that order, and leave
    // the heap in the reverse of it: only an ORDER BY on the time gets the list back.
    this.orderRows("npm_package_keyword", "keyword", List.of("zeta", "alpha", "mid"), repo);
    this.orderRows("npm_package_maintainer", "name", List.of("zed", "amy", "moe"), repo);

    final var panel =
        this.perform(
                get(
                        "/api/npm/packages/{repo}/{package}/versions/{version}",
                        repo.getName(),
                        PACKAGE,
                        "1.0.0")
                    .header(AUTHORIZATION, this.adminBearerToken()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(JsonPath.<List<String>>read(panel, "$.keywords[*].keyword"))
        .containsExactly("zeta", "alpha", "mid");
    assertThat(JsonPath.<List<String>>read(panel, "$.maintainers[*].name"))
        .containsExactly("zed", "amy", "moe");
  }

  /** Stamps the rows of the repo's package with rising times in {@code values} order. */
  private void orderRows(
      final String table, final String column, final List<String> values, final Repo repo) {
    final var start = Instant.now().minusSeconds(3600);
    final var ids = new ArrayList<UUID>();

    for (var i = 0; i < values.size(); i++) {
      final var id =
          this.jdbcTemplate.queryForObject(
              "select t.\"id\" from \"%s\" t join \"npm_package_version\" v on v.\"id\" = t.\"package_version_id\""
                      .formatted(table)
                  + " join \"npm_package\" p on p.\"id\" = v.\"package_id\""
                  + " where p.\"repo_id\" = ? and t.\"%s\" = ?".formatted(column),
              UUID.class,
              repo.getId(),
              values.get(i));

      this.jdbcTemplate.update(
          "update \"%s\" set \"created_at\" = ? where \"id\" = ?".formatted(table),
          Timestamp.from(start.plusSeconds(i)),
          id);
      ids.add(id);
    }

    HeapOrder.rewriteInOrder(this.jdbcTemplate, table, ids.reversed());
  }
}
