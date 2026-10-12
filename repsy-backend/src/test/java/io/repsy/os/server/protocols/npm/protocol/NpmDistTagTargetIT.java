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
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-2219 (follow-up of RPS-2210): a dist-tag value that names no version of the package is a 400
 * like npm's registry answers, not a dangling tag; a {@code deprecated} that is not a string is a
 * 400 on the deprecate path, not a ClassCastException (500).
 */
@DisplayName("npm publish dist-tag targets and deprecate value types (RPS-2219)")
class NpmDistTagTargetIT extends AbstractIT {

  private static final String NAME = "left-pad";

  @Autowired private ObjectMapper objectMapper;

  private Map<String, Object> bodyOf(final String repoName, final String version) {
    return this.objectMapper.readValue(
        NpmPublishBodies.body(this.objectMapper, repoName, NAME, version, Map.of()),
        new TypeReference<LinkedHashMap<String, Object>>() {});
  }

  private MockHttpServletResponse publish(
      final String repoName, final String token, final Map<String, Object> body) throws Exception {
    return this.mockMvc
        .perform(
            put("/{repo}/{name}", repoName, NAME)
                .with(protocolPort())
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(this.objectMapper.writeValueAsBytes(body)))
        .andReturn()
        .getResponse();
  }

  private int packageRows(final String repoName) {
    return this.jdbcTemplate.queryForObject(
        "select count(*) from npm_package p join repo r on r.id = p.repo_id where r.name = ?",
        Integer.class,
        repoName);
  }

  private String token() {
    return this.protocolBearerTokenFor(this.createUser(uniqueUsername("rps2219"), UserRole.ADMIN));
  }

  @Test
  @DisplayName("a tag on a package's first publish that names an unknown version is a 400")
  void danglingTagOnFirstPublish() throws Exception {
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("npm"));
    final var body = this.bodyOf(repo.getName(), "1.0.0");
    body.put("dist-tags", Map.of("latest", "9.9.9"));

    final var response = this.publish(repo.getName(), this.token(), body);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("badRequest");
    assertThat(this.packageRows(repo.getName())).isZero();
  }

  @Test
  @DisplayName("an additional tag that names an unknown version is a 400, the publish is not kept")
  void danglingSecondTag() throws Exception {
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("npm"));
    final var body = this.bodyOf(repo.getName(), "1.0.0");
    body.put("dist-tags", new LinkedHashMap<>(Map.of("latest", "1.0.0", "next", "2.0.0")));

    assertThat(this.publish(repo.getName(), this.token(), body).getStatus()).isEqualTo(400);
    assertThat(this.packageRows(repo.getName())).isZero();
  }

  @Test
  @DisplayName("a tag on a later publish may name a stored version, not an unknown one")
  void tagMayNameStoredVersion() throws Exception {
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("npm"));
    final var token = this.token();

    assertThat(
            this.publish(repo.getName(), token, this.bodyOf(repo.getName(), "1.0.0")).getStatus())
        .isEqualTo(200);

    final var dangling = this.bodyOf(repo.getName(), "1.1.0");
    dangling.put("dist-tags", Map.of("latest", "1.1.0", "beta", "7.0.0"));
    assertThat(this.publish(repo.getName(), token, dangling).getStatus()).isEqualTo(400);

    final var stored = this.bodyOf(repo.getName(), "1.1.0");
    stored.put("dist-tags", Map.of("latest", "1.1.0", "stable", "1.0.0"));
    assertThat(this.publish(repo.getName(), token, stored).getStatus()).isEqualTo(200);
  }

  static Stream<Arguments> nonStringDeprecated() {
    return Stream.of(
        Arguments.of("number", 5),
        Arguments.of("boolean", true),
        Arguments.of("object", Map.of("message", "old")),
        Arguments.of("list", java.util.List.of("old")));
  }

  @ParameterizedTest(name = "deprecated as {0} is a 400")
  @MethodSource("nonStringDeprecated")
  @DisplayName("a deprecated that is not a string is a 400, not a 500")
  void nonStringDeprecatedIsBadRequest(final String label, final Object value) throws Exception {
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("npm"));
    final var token = this.token();

    assertThat(
            this.publish(repo.getName(), token, this.bodyOf(repo.getName(), "1.0.0")).getStatus())
        .isEqualTo(200);

    final var read =
        this.mockMvc
            .perform(
                get("/{repo}/{name}", repo.getName(), NAME)
                    .queryParam("write", "true")
                    .with(protocolPort())
                    .header(AUTHORIZATION, token))
            .andReturn()
            .getResponse();
    final Map<String, Object> packument =
        this.objectMapper.readValue(
            read.getContentAsString(StandardCharsets.UTF_8), new TypeReference<>() {});
    @SuppressWarnings("unchecked")
    final var versions = (Map<String, Map<String, Object>>) packument.get("versions");
    versions.get("1.0.0").put("deprecated", value);

    final var response = this.publish(repo.getName(), token, packument);

    assertThat(response.getStatus()).as(label).isEqualTo(400);

    final var deprecated =
        this.jdbcTemplate.queryForObject(
            "select count(*) from npm_package_version v join npm_package p on p.id = v.package_id"
                + " join repo r on r.id = p.repo_id where r.name = ? and v.deprecated",
            Integer.class,
            repo.getName());
    assertThat(deprecated).isZero();
  }
}
