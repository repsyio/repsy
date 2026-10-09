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

import io.repsy.libs.multiport.configs.props.MultiPortProperties;
import java.util.Map;
import org.apache.coyote.http11.Http11NioProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.test.util.ReflectionTestUtils;

class TomcatMultiPortConfigurationTest {

  private static final int API_PORT = 8081;
  private static final int TIMEOUT = 45_000;
  private static final int MAX_PART_COUNT = 7;

  @Test
  void additionalPlainConnectorShouldGetTheSharedConnectorSettings() {

    final var properties = new MultiPortProperties();
    properties.setMainPort("9090");
    properties.setPorts(Map.of("api", API_PORT));
    final var configuration = new TomcatMultiPortConfig(properties);
    ReflectionTestUtils.setField(configuration, "connectionTimeout", TIMEOUT);
    ReflectionTestUtils.setField(configuration, "maxPartCount", MAX_PART_COUNT);
    final var factory = new TomcatServletWebServerFactory();

    configuration.repsyTomcatCustomizer().customize(factory);

    assertThat(factory.getAdditionalConnectors()).hasSize(1);
    final var connector = factory.getAdditionalConnectors().get(0);
    assertThat(connector.getPort()).isEqualTo(API_PORT);
    assertThat(connector.getScheme()).isEqualTo("http");
    assertThat(connector.getEncodedSolidusHandling()).isEqualTo("decode");
    assertThat(((Http11NioProtocol) connector.getProtocolHandler()).getConnectionTimeout())
        .isEqualTo(TIMEOUT);
    assertThat(connector.getMaxPartCount()).isEqualTo(MAX_PART_COUNT);
    assertThat(factory.getConnectorCustomizers()).hasSize(1);
  }
}
