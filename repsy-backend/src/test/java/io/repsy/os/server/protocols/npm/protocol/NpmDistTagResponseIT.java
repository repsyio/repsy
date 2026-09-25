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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1362: {@code PUT /-/package/<pkg>/dist-tags/<tag>} answered 200 with an empty body, and yarn
 * classic takes an answer without an {@code ok} field for a failure ({@code Couldn't add tag}),
 * although the tag was set. The add and the remove answer JSON now.
 */
@DisplayName("npm wire protocol dist-tag answers")
class NpmDistTagResponseIT extends AbstractIntegrationTest {

  private static final String DIST_TAG = "/{repo}/-/package/{name}/dist-tags/{tag}";
  private static final String DIST_TAGS = "/{repo}/-/package/{name}/dist-tags";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private ObjectMapper objectMapper;

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private void publish(final Repo repo, final String token, final String name, final String version)
      throws Exception {
    final var response =
        this.protocol(
            put("/{repo}/{name}", repo.getName(), name)
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    NpmPublishBodies.body(
                        this.objectMapper, repo.getName(), name, version, Map.of())));

    assertThat(response.getStatus()).isEqualTo(200);
  }

  private MockHttpServletResponse addTag(
      final Repo repo,
      final String token,
      final String name,
      final String tag,
      final String version)
      throws Exception {
    return this.protocol(
        put(DIST_TAG, repo.getName(), name, tag)
            .header(AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("\"" + version + "\""));
  }

  private Map<String, String> tagsOf(final Repo repo, final String name) throws Exception {
    final var response = this.protocol(get(DIST_TAGS, repo.getName(), name));

    assertThat(response.getStatus()).isEqualTo(200);

    return JsonPath.read(response.getContentAsString(), "$");
  }

  @Test
  @DisplayName("the add answers ok, the package id and its tags as JSON")
  void addAnswersJson() throws Exception {
    final var token =
        this.protocolBearerTokenFor(this.createUser(uniqueUsername("tags"), UserRole.ADMIN));
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("rps1362"), false, null);
    this.publish(repo, token, "left-pad", "1.0.0");
    this.publish(repo, token, "left-pad", "2.0.0");

    final var response = this.addTag(repo, token, "left-pad", "next", "1.0.0");
    final var body = response.getContentAsString();

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    assertThat(JsonPath.<Boolean>read(body, "$.ok")).isTrue();
    assertThat(JsonPath.<String>read(body, "$.id")).isEqualTo("left-pad");
    assertThat(JsonPath.<Map<String, String>>read(body, "$['dist-tags']"))
        .containsEntry("next", "1.0.0")
        .containsEntry("latest", "2.0.0");
    assertThat(this.tagsOf(repo, "left-pad")).isEqualTo(Map.of("latest", "2.0.0", "next", "1.0.0"));
  }

  @Test
  @DisplayName("the add names a scoped package with its scope")
  void addAnswersTheScopedId() throws Exception {
    final var token =
        this.protocolBearerTokenFor(this.createUser(uniqueUsername("tags"), UserRole.ADMIN));
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("rps1362"), false, null);
    this.publish(repo, token, "@acme/widget", "1.0.0");

    final var response = this.addTag(repo, token, "@acme/widget", "beta", "1.0.0");

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(JsonPath.<Boolean>read(response.getContentAsString(), "$.ok")).isTrue();
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.id"))
        .isEqualTo("@acme/widget");
  }

  @Test
  @DisplayName("the remove answers ok, the package id and the tags that are left as JSON")
  void removeAnswersJson() throws Exception {
    final var token =
        this.protocolBearerTokenFor(this.createUser(uniqueUsername("tags"), UserRole.ADMIN));
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("rps1362"), false, null);
    this.publish(repo, token, "left-pad", "1.0.0");
    assertThat(this.addTag(repo, token, "left-pad", "next", "1.0.0").getStatus()).isEqualTo(200);

    final var response =
        this.protocol(
            delete(DIST_TAG, repo.getName(), "left-pad", "next").header(AUTHORIZATION, token));
    final var body = response.getContentAsString();

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    assertThat(JsonPath.<Boolean>read(body, "$.ok")).isTrue();
    assertThat(JsonPath.<String>read(body, "$.id")).isEqualTo("left-pad");
    assertThat(JsonPath.<Map<String, String>>read(body, "$['dist-tags']"))
        .isEqualTo(Map.of("latest", "1.0.0"));
    assertThat(this.tagsOf(repo, "left-pad")).isEqualTo(Map.of("latest", "1.0.0"));
  }

  @Test
  @DisplayName("a refused change still answers its error, not ok")
  void refusedChangeIsNotOk() throws Exception {
    final var token =
        this.protocolBearerTokenFor(this.createUser(uniqueUsername("tags"), UserRole.ADMIN));
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("rps1362"), false, null);
    this.publish(repo, token, "left-pad", "1.0.0");

    final var unknownVersion = this.addTag(repo, token, "left-pad", "next", "9.9.9");
    final var removeLatest =
        this.protocol(
            delete(DIST_TAG, repo.getName(), "left-pad", "latest").header(AUTHORIZATION, token));

    assertThat(unknownVersion.getStatus()).isGreaterThanOrEqualTo(400);
    assertThat(unknownVersion.getContentAsString()).doesNotContain("\"ok\"");
    assertThat(removeLatest.getStatus()).isEqualTo(400);
    assertThat(removeLatest.getContentAsString()).doesNotContain("\"ok\"");
  }
}
