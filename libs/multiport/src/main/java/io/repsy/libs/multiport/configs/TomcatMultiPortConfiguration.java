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

import io.repsy.libs.multiport.configs.props.MultiPortProperties;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnClass(TomcatServletWebServerFactory.class)
public class TomcatMultiPortConfiguration {

  private final @NonNull MultiPortProperties multiPortProperties;

  @Value("${multiport.tomcat.connection-timeout:120000}")
  private int connectionTimeout;

  @Bean
  public WebServerFactoryCustomizer<@NonNull TomcatServletWebServerFactory>
      repsyTomcatCustomizer() {

    this.multiPortProperties.validatePorts();

    final var additionalConnectors = this.createAdditionalConnectors();

    return factory -> this.customizeTomcat(factory, additionalConnectors);
  }

  private void customizeTomcat(
      final @NonNull TomcatServletWebServerFactory factory,
      final @NonNull Connector[] additionalConnectors) {

    factory.addConnectorCustomizers(
        connector -> connector.setEncodedSolidusHandling(EncodedSolidusHandling.DECODE.getValue()));

    if (additionalConnectors.length > 0) {
      factory.addAdditionalConnectors(additionalConnectors);
    }
  }

  private @NonNull Connector[] createAdditionalConnectors() {

    final var connectors = new ArrayList<Connector>();

    // ports without the main port.
    final var additionalPorts = this.multiPortProperties.getAdditionalPorts();

    for (final var entry : additionalPorts.entrySet()) {
      final var port = entry.getValue();

      connectors.add(this.createConnector(port));
    }

    return connectors.toArray(new Connector[0]);
  }

  private @NonNull Connector createConnector(final int port) {

    final var connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");

    connector.setScheme("http");
    connector.setPort(port);
    connector.setProperty("connectionTimeout", String.valueOf(this.connectionTimeout));

    return connector;
  }
}
