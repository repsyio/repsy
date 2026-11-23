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
package io.repsy.os.server.protocols.docker.protocol.handlers;

import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.imageManifest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIT;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code GET /v2/_catalog} is no route of the Docker server: no handler is registered, so the
 * router's catch-all answers {@code 404 NAME_UNKNOWN / unknownPath} to every caller, before
 * authentication (so not even a {@code 401} challenge). {@code crane catalog} and {@code regctl
 * repo ls} therefore cannot enumerate a Repsy registry. The tests pin that behaviour at its current
 * state: a story that implements the catalog flips them on purpose.
 */
@DisplayName("Docker _catalog is no route")
class DockerCatalogIT extends AbstractIT {

  private static final String IMAGE = "app";

  @Autowired private WebApplicationContext webApplicationContext;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private String adminToken;

  @BeforeEach
  void setUpToken() {
    this.adminToken = this.adminProtocolBearerToken();
  }

  private Repo pushedRepo() throws Exception {
    final var repo = this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"), true, null);
    final var wire =
        new DockerWire(this.mockMvc, this.webApplicationContext, protocolPort(), this.adminToken);

    wire.pushBlobsOf(repo, IMAGE, "layer");
    assertThat(wire.putImage(repo, IMAGE, "v1", imageManifest("layer")).getStatus()).isEqualTo(201);

    return repo;
  }

  private MockHttpServletResponse fetch(final String path, final String authorization)
      throws Exception {
    final var request = get(path).with(protocolPort());

    if (authorization != null) {
      request.header(AUTHORIZATION, authorization);
    }

    return this.mockMvc.perform(request).andReturn().getResponse();
  }

  private static void assertNoRoute(final MockHttpServletResponse response) throws Exception {
    final var body = response.getContentAsString();

    assertThat(response.getStatus()).as(body).isEqualTo(404);
    assertThat(JsonPath.<String>read(body, "$.errors[0].code")).isEqualTo("NAME_UNKNOWN");
    assertThat(JsonPath.<String>read(body, "$.errors[0].message")).isEqualTo("unknownPath");
    assertThat(JsonPath.<String>read(body, "$.errors[0].detail")).isEqualTo("unknownPath");
    assertThat(response.getHeader(WWW_AUTHENTICATE))
        .as("no auth challenge on an unknown route")
        .isNull();
  }

  @Test
  @DisplayName("an admin gets 404 NAME_UNKNOWN for _catalog, with and without paging")
  void catalogIsNoRouteForAnAdmin() throws Exception {
    this.pushedRepo();

    assertNoRoute(this.fetch("/v2/_catalog", this.adminToken));
    assertNoRoute(this.fetch("/v2/_catalog?n=5", this.adminToken));
  }

  @Test
  @DisplayName("an anonymous caller gets the same 404, not a 401 challenge")
  void catalogIsNoRouteForAnonymous() throws Exception {
    final var repo = this.pushedRepo();

    assertNoRoute(this.fetch("/v2/_catalog", null));
    assertNoRoute(this.fetch("/v2/%s/_catalog".formatted(repo.getName()), null));
  }

  @Test
  @DisplayName("the token endpoint still issues a catalog-scope token, which nothing reads")
  void tokenEndpointIssuesACatalogScopeToken() throws Exception {
    final var admin = this.createUser(uniqueUsername("admin"), UserRole.ADMIN);

    final var response =
        this.mockMvc
            .perform(
                post("/v2/token")
                    .param("scope", "registry:catalog:*")
                    .header(AUTHORIZATION, basicAuth(admin.getUsername(), VALID_PASSWORD))
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.token")).isNotBlank();
  }
}
