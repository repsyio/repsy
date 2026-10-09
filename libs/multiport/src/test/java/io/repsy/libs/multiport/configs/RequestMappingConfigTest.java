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
package io.repsy.libs.multiport.configs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.libs.multiport.configs.props.MultiPortProperties;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** The port-based handler mapping answers a wrong verb with 405 and leaves unknown paths alone. */
class RequestMappingConfigTest {

  private static final int MAIN_PORT = 8080;
  private static final int API_PORT = 8081;
  private static final int ALIAS_PORT = 8443;

  private RequestMappingHandlerMapping mapping;

  @BeforeEach
  void setUp() throws Exception {

    final var properties = new MultiPortProperties();
    properties.setMainPort(String.valueOf(MAIN_PORT));
    properties.setPorts(Map.of("api", API_PORT));
    properties.setPortAliases(Map.of(ALIAS_PORT, "api"));

    final var context = new StaticWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context.registerSingleton("panel", PanelController.class);
    context.registerSingleton("router", RouterController.class);
    context.refresh();

    this.mapping =
        new RequestMappingConfig(properties)
            .webMvcRegistrations()
            .getRequestMappingHandlerMapping();
    this.mapping.setApplicationContext(context);
    this.mapping.afterPropertiesSet();
  }

  @Test
  void aMatchingVerbIsServedByThePortLocalHandler() throws Exception {

    final var chain = this.mapping.getHandler(request(API_PORT, "GET", "/api/things"));

    assertThat(chain).isNotNull();
    assertThat(chain.getHandler().toString()).contains("PanelController#list");
  }

  @Test
  void aWrongVerbOnAMappedPanelPathRaisesMethodNotSupportedWithThePortLocalVerbs() {

    final var request = request(API_PORT, "DELETE", "/api/things");

    assertThatThrownBy(() -> this.mapping.getHandler(request))
        .isInstanceOfSatisfying(
            HttpRequestMethodNotSupportedException.class,
            e ->
                assertThat(e.getSupportedHttpMethods())
                    .containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST));
  }

  @Test
  void aWrongVerbOnAnAliasPortRaisesMethodNotSupported() {

    final var request = request(ALIAS_PORT, "DELETE", "/api/things");

    assertThatThrownBy(() -> this.mapping.getHandler(request))
        .isInstanceOf(HttpRequestMethodNotSupportedException.class);
  }

  @Test
  void aMediaTypeTheRouteCannotProduceRaisesNotAcceptable() {

    final var request = request(API_PORT, "GET", "/api/things");
    request.addHeader("Accept", "application/xml");

    assertThatThrownBy(() -> this.mapping.getHandler(request))
        .isInstanceOf(HttpMediaTypeNotAcceptableException.class);
  }

  @Test
  void anUnknownPathOnThePanelPortStaysUnhandledSoTheNextMappingServesIt() throws Exception {

    assertThat(this.mapping.getHandler(request(API_PORT, "DELETE", "/nothing/here"))).isNull();
  }

  @Test
  void thePortLocalRouterStillTakesEveryVerbOnTheMainPort() throws Exception {

    final var chain = this.mapping.getHandler(request(MAIN_PORT, "DELETE", "/api/things"));

    assertThat(chain).isNotNull();
    assertThat(chain.getHandler().toString()).contains("RouterController#route");
  }

  @Test
  void aPathOfAnotherPortIsNotAnAllowedVerbSourceForThisPort() throws Exception {

    // /protocol-only is mapped on the main port (GET), nothing of the panel port matches it.
    assertThat(this.mapping.getHandler(request(API_PORT, "POST", "/protocol-only"))).isNull();
  }

  private static MockHttpServletRequest request(
      final int port, final String method, final String path) {

    final var request = new MockHttpServletRequest(method, path);
    request.setLocalPort(port);
    return request;
  }

  @Controller
  @RestApiPort("api")
  static class PanelController {

    @GetMapping(path = "/api/things", produces = "application/json")
    public String list() {
      return "[]";
    }

    @PostMapping("/api/things")
    public String create() {
      return "{}";
    }
  }

  @Controller
  static class RouterController {

    @RequestMapping("/**")
    public String route() {
      return "routed";
    }
  }
}
