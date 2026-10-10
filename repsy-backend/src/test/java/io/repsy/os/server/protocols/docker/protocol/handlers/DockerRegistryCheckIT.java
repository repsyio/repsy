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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIT;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * RPS-2090: {@code GET|HEAD /v2/}, the registry ping every Docker and OCI client sends first.
 * Before this class only {@code DockerAuthChallengeTest} (a unit test of the header builder) and
 * the opt-in e2e suite covered it.
 *
 * <p>The Docker provider's handler is the only ping handler and serves every repo type (RPS-2103
 * removed the identical Helm one, which was unreachable), so a Helm OCI client gets the contract
 * pinned here. The ping addresses no repo or image, so its challenge names no scope (RPS-1588).
 * Both the 200 and the 401 carry {@code Docker-Distribution-API-Version: registry/2.0} on the
 * protocol port; the API port does not.
 *
 * <p>The realm is built from the request's scheme, server name and port, and from {@code
 * X-Forwarded-Host} / {@code X-Forwarded-Port} (RPS-1515: a port embedded in {@code
 * X-Forwarded-Host} must survive). The MockMvc here has no Tomcat {@code RemoteIpValve}, so the
 * scheme and host come from the request itself and the forwarded port headers are read raw.
 */
@DisplayName("Docker registry ping /v2/ (RPS-2090)")
class DockerRegistryCheckIT extends AbstractIT {

  private static final String SERVICE = "service=\"repsy\"";
  private static final String API_VERSION_HEADER = "Docker-Distribution-API-Version";

  private static RequestPostProcessor publicUrl(
      final String scheme, final String host, final int port) {

    return request -> {
      request.setScheme(scheme);
      request.setServerName(host);
      request.setServerPort(port);
      request.setLocalPort(PROTOCOL_PORT);
      request.setServletPath(request.getRequestURI());
      return request;
    };
  }

  private MockHttpServletResponse ping(final String path, final String token) throws Exception {
    final MockHttpServletRequestBuilder request = get(path).with(protocolPort());
    if (token != null) {
      request.header(AUTHORIZATION, token);
    }

    return this.mockMvc.perform(request).andReturn().getResponse();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"/v2/", "/v2"})
  @DisplayName("an anonymous ping is a 401 UNAUTHORIZED challenge with a scope-less Bearer realm")
  void anonymousPingIsChallenged(final String path) throws Exception {
    final var response = this.ping(path, null);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader(WWW_AUTHENTICATE))
        .startsWith("Bearer realm=\"")
        .endsWith("/v2/token\"," + SERVICE)
        .doesNotContain("scope=");
    final var body = response.getContentAsString(StandardCharsets.UTF_8);
    assertThat(JsonPath.<String>read(body, "$.errors[0].code")).isEqualTo("UNAUTHORIZED");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"/v2/", "/v2"})
  @DisplayName("an authenticated ping is a 200 with no body, with and without the trailing slash")
  void authenticatedPingIsOk(final String path) throws Exception {
    final var response = this.ping(path, this.adminProtocolBearerToken());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsByteArray()).isEmpty();
    assertThat(response.getHeader(WWW_AUTHENTICATE)).isNull();
    assertThat(response.getHeader(API_VERSION_HEADER)).isEqualTo("registry/2.0");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"/v2/", "/v2"})
  @DisplayName("the 401 challenge of the ping names the registry API version too (RPS-2103)")
  void challengeCarriesTheApiVersion(final String path) throws Exception {
    final var response = this.ping(path, null);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader(API_VERSION_HEADER)).isEqualTo("registry/2.0");
  }

  @Test
  @DisplayName("HEAD /v2/ carries the registry API version (RPS-2103)")
  void headCarriesTheApiVersion() throws Exception {
    final var response =
        this.mockMvc
            .perform(
                head("/v2/")
                    .header(AUTHORIZATION, this.adminProtocolBearerToken())
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader(API_VERSION_HEADER)).isEqualTo("registry/2.0");
  }

  @Test
  @DisplayName("the API port does not answer the registry API version (RPS-2103)")
  void apiPortHasNoApiVersion() throws Exception {
    final var response =
        this.mockMvc.perform(get("/v2/").with(apiPort())).andReturn().getResponse();

    assertThat(response.getHeader(API_VERSION_HEADER)).isNull();
  }

  @Test
  @DisplayName("HEAD /v2/ answers like GET: 401 challenge anonymously, 200 with a token")
  void headAnswersLikeGet() throws Exception {
    final var anonymous =
        this.mockMvc.perform(head("/v2/").with(protocolPort())).andReturn().getResponse();
    final var authenticated =
        this.mockMvc
            .perform(
                head("/v2/")
                    .header(AUTHORIZATION, this.adminProtocolBearerToken())
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(anonymous.getStatus()).isEqualTo(401);
    assertThat(anonymous.getHeader(WWW_AUTHENTICATE))
        .isEqualTo(this.ping("/v2/", null).getHeader(WWW_AUTHENTICATE));
    assertThat(authenticated.getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("only GET and HEAD are the ping: POST /v2/ is not answered 200")
  void postIsNotThePing() throws Exception {
    final var response =
        this.mockMvc
            .perform(
                post("/v2/")
                    .header(AUTHORIZATION, this.adminProtocolBearerToken())
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isNotEqualTo(200).isGreaterThanOrEqualTo(400);
  }

  @Test
  @DisplayName("the realm is the https host the client called, without the default port")
  void realmIsTheCalledHost() throws Exception {
    final var response =
        this.mockMvc
            .perform(get("/v2/").with(publicUrl("https", "registry.example.com", 443)))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader(WWW_AUTHENTICATE))
        .isEqualTo("Bearer realm=\"https://registry.example.com/v2/token\"," + SERVICE);
  }

  @Test
  @DisplayName("the realm keeps a non-default port of the called host")
  void realmKeepsTheCalledPort() throws Exception {
    final var response =
        this.mockMvc
            .perform(get("/v2/").with(publicUrl("http", "registry.example.com", 9090)))
            .andReturn()
            .getResponse();

    assertThat(response.getHeader(WWW_AUTHENTICATE))
        .isEqualTo("Bearer realm=\"http://registry.example.com:9090/v2/token\"," + SERVICE);
  }

  @Test
  @DisplayName("X-Forwarded-Port is kept in the realm")
  void realmKeepsTheForwardedPort() throws Exception {
    final var response =
        this.mockMvc
            .perform(
                get("/v2/")
                    .header("X-Forwarded-Port", "8443")
                    .with(publicUrl("https", "registry.example.com", 443)))
            .andReturn()
            .getResponse();

    assertThat(response.getHeader(WWW_AUTHENTICATE))
        .isEqualTo("Bearer realm=\"https://registry.example.com:8443/v2/token\"," + SERVICE);
  }

  /**
   * RPS-1515: a proxy that puts the port inside {@code X-Forwarded-Host} and sends no {@code
   * X-Forwarded-Port} used to lose it; {@code RequestBaseUrlUtils} now recovers it from the raw
   * header, so the realm a client follows keeps the port.
   */
  @Test
  @DisplayName("a port embedded in X-Forwarded-Host is kept in the realm (RPS-1515)")
  void realmKeepsThePortOfForwardedHost() throws Exception {
    final var response =
        this.mockMvc
            .perform(
                get("/v2/")
                    .header("X-Forwarded-Host", "registry.example.com:8443")
                    .with(publicUrl("https", "registry.example.com", 443)))
            .andReturn()
            .getResponse();

    assertThat(response.getHeader(WWW_AUTHENTICATE))
        .isEqualTo("Bearer realm=\"https://registry.example.com:8443/v2/token\"," + SERVICE);
  }
}
