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
import static org.springframework.http.HttpHeaders.ACCEPT;
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
 * RPS-1390: the publish, the unpublish of one version and the delete of a package answered 200 with
 * an empty body, although the public registry answers {@code {"ok": true, ...}}, and a client that
 * reads the body may take an empty one for a failure (yarn classic did for the dist-tags,
 * RPS-1362). The abbreviated packument derives {@code hasInstallScript} from the scripts of a
 * version.
 */
@DisplayName("npm wire protocol publish and delete answers")
class NpmWriteResponseIT extends AbstractIntegrationTest {

  private static final String PACKAGE_PATH = "/{repo}/{name}";
  private static final String ABBREVIATED = "application/vnd.npm.install-v1+json";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private ObjectMapper objectMapper;

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private MockHttpServletResponse publish(
      final Repo repo,
      final String token,
      final String name,
      final String version,
      final Map<String, Object> extra)
      throws Exception {
    return this.protocol(
        put(PACKAGE_PATH, repo.getName(), name)
            .header(AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                NpmPublishBodies.body(this.objectMapper, repo.getName(), name, version, extra)));
  }

  private void assertOk(final MockHttpServletResponse response, final String id) throws Exception {
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    assertThat(JsonPath.<Boolean>read(response.getContentAsString(), "$.ok")).isTrue();
    assertThat(JsonPath.<Boolean>read(response.getContentAsString(), "$.success")).isTrue();
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.id")).isEqualTo(id);
  }

  private String adminToken() {
    return this.protocolBearerTokenFor(this.createUser(uniqueUsername("write"), UserRole.ADMIN));
  }

  private Repo npmRepo() {
    return this.seedRepo(RepoType.NPM, uniqueRepoName("rps1390"), false, null);
  }

  @Test
  @DisplayName("a publish answers ok, success and the package id as JSON")
  void publishAnswersJson() throws Exception {
    final var repo = this.npmRepo();

    this.assertOk(this.publish(repo, this.adminToken(), "left-pad", "1.0.0", Map.of()), "left-pad");
  }

  @Test
  @DisplayName("a publish names a scoped package with its scope")
  void publishAnswersTheScopedId() throws Exception {
    final var repo = this.npmRepo();

    this.assertOk(
        this.publish(repo, this.adminToken(), "@acme/widget", "1.0.0", Map.of()), "@acme/widget");
  }

  @Test
  @DisplayName("the delete of a package answers ok, success and the package id as JSON")
  void deletePackageAnswersJson() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    this.publish(repo, token, "@acme/gone", "1.0.0", Map.of());

    final var response =
        this.protocol(
            delete("/{repo}/@acme/gone/-rev/1-abc", repo.getName()).header(AUTHORIZATION, token));

    this.assertOk(response, "@acme/gone");
  }

  @Test
  @DisplayName("the delete of a tarball that ends an unpublish answers JSON")
  void deleteTarballAnswersJson() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    this.publish(repo, token, "left-pad", "1.0.0", Map.of());
    this.publish(repo, token, "left-pad", "2.0.0", Map.of());

    // The packument PUT of an unpublish takes 1.0.0 away, then the tarball delete ends it.
    final var body =
        Map.of(
            "name",
            "left-pad",
            "dist-tags",
            Map.of("latest", "2.0.0"),
            "versions",
            this.versionsWithout(repo, token, "left-pad", "1.0.0"));
    final var put =
        this.protocol(
            put("/{repo}/left-pad/-rev/1-abc", repo.getName())
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(this.objectMapper.writeValueAsBytes(body)));
    this.assertOk(put, "left-pad");

    final var response =
        this.protocol(
            delete("/{repo}/left-pad/-/left-pad-1.0.0.tgz/-rev/1-abc", repo.getName())
                .header(AUTHORIZATION, token));

    this.assertOk(response, "left-pad");
  }

  private Map<String, Object> versionsWithout(
      final Repo repo, final String token, final String name, final String version)
      throws Exception {
    final var response =
        this.protocol(
            get(PACKAGE_PATH, repo.getName(), name)
                .queryParam("write", "true")
                .header(AUTHORIZATION, token));
    assertThat(response.getStatus()).isEqualTo(200);

    final Map<String, Object> versions = JsonPath.read(response.getContentAsString(), "$.versions");
    versions.remove(version);

    return versions;
  }

  @Test
  @DisplayName("the abbreviated packument derives hasInstallScript from the scripts of a version")
  void abbreviatedDerivesHasInstallScript() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    this.publish(repo, token, "native", "1.0.0", Map.of("scripts", Map.of("postinstall", "x")));
    this.publish(repo, token, "native", "1.1.0", Map.of("scripts", Map.of("test", "jest")));

    final var response =
        this.protocol(
            get(PACKAGE_PATH, repo.getName(), "native")
                .header(ACCEPT, ABBREVIATED)
                .header(AUTHORIZATION, token));

    assertThat(response.getStatus()).isEqualTo(200);
    final var body = response.getContentAsString();
    assertThat(JsonPath.<Boolean>read(body, "$.versions['1.0.0'].hasInstallScript")).isTrue();
    assertThat(JsonPath.<Map<String, Object>>read(body, "$.versions['1.1.0']"))
        .doesNotContainKey("hasInstallScript");
  }
}
