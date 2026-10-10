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
package io.repsy.os.shared.error_handling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.CACHE_CONTROL;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIT;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * RPS-1778: a failed panel request is answered with an RFC 9457 {@code application/problem+json}
 * document, while the protocol routes keep the error format of their own client.
 */
@DisplayName("Panel API problem+json errors")
class PanelProblemJsonIT extends AbstractIT {

  private static final String NOT_VALID_TEXT = "Incoming data couldn't be validated.";

  @Test
  @DisplayName("a validation failure is a problem with the offending fields in errors[]")
  void validationError() throws Exception {
    final var response =
        this.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse();

    final var body = response.getContentAsString();

    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(JsonPath.<Integer>read(body, "$.status")).isEqualTo(400);
    assertThat(JsonPath.<String>read(body, "$.title")).isEqualTo("Bad Request");
    assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("validationError");
    assertThat(JsonPath.<String>read(body, "$.detail")).isEqualTo(NOT_VALID_TEXT);
    assertThat(JsonPath.<String>read(body, "$.instance")).isEqualTo("/api/auth/login");
    assertThat(JsonPath.<String>read(body, "$.traceId")).matches(UUID_PATTERN);
    assertThat(JsonPath.<List<String>>read(body, "$.errors[*].field")).contains("username");
    assertThat(JsonPath.<List<String>>read(body, "$.errors[*].code")).isNotEmpty();
    assertThat(JsonPath.<Map<String, Object>>read(body, "$"))
        .doesNotContainKeys("msgId", "errorCode", "text", "data");
  }

  @Test
  @DisplayName("an unknown repository is a 404 problem")
  void notFound() throws Exception {
    expectError(
        this.perform(
            get("/api/repos/{repo}/security-detail", "nope" + randomTag())
                .header(AUTHORIZATION, this.adminBearerToken())),
        HttpStatus.NOT_FOUND,
        "repoNotFound",
        "repoNotFound",
        "Repository not found");
  }

  @Test
  @DisplayName("a signed-in user who lacks the permission gets a 403 problem")
  void forbidden() throws Exception {
    expectForbidden(this.perform(get("/api/users").header(AUTHORIZATION, this.userBearerToken())));
  }

  @Test
  @DisplayName("a missing credential is a 401 problem that keeps the WWW-Authenticate challenge")
  void unauthorized() throws Exception {
    final var result = this.perform(get("/api/users"));

    result.andExpect(header().exists(WWW_AUTHENTICATE));
    expectError(
        result,
        HttpStatus.UNAUTHORIZED,
        "missingRequestHeader",
        "Authorization",
        "A required request header is missing.");
  }

  @Test
  @DisplayName("a protocol route keeps its own error format, not problem+json")
  void protocolRouteKeepsItsOwnFormat() throws Exception {
    final var repo = this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"), false, null);

    final MockHttpServletRequestBuilder request =
        get("/v2/{repo}/app/manifests/latest", repo.getName())
            .header(AUTHORIZATION, this.adminProtocolBearerToken());
    final MockHttpServletResponse response =
        this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(response.getContentType()).doesNotContain("problem+json");
    assertThat(JsonPath.<Map<String, Object>>read(response.getContentAsString(), "$"))
        .containsOnlyKeys("errors");
  }

  @Test
  @DisplayName("a response that carries a secret is not stored by any cache")
  void secretResponseIsNoStore() throws Exception {
    final var username = uniqueUsername("login");
    this.createUser(username, io.repsy.os.shared.user.entities.UserRole.ADMIN);

    this.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\"" + username + "\",\"password\":\"" + VALID_PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string(CACHE_CONTROL, "no-store"));
  }
}
