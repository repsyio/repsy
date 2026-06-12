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
package io.repsy.os.config.ssl;

import io.repsy.os.config.ssl.RepsySslProperties.PortSslProperties;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@NullMarked
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
@RequiredArgsConstructor
public class SslConnectorCustomizer
    implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

  private static final String FILE_PREFIX = "file:";

  private final RepsySslProperties sslProperties;

  @Override
  public void customize(final TomcatServletWebServerFactory factory) {
    final var connectors = new ArrayList<Connector>();

    if (this.sslProperties.api().enabled()) {
      connectors.add(this.createSslConnector(this.sslProperties.api()));
    }

    if (this.sslProperties.repo().enabled()) {
      connectors.add(this.createSslConnector(this.sslProperties.repo()));
    }

    if (!connectors.isEmpty()) {
      factory.addAdditionalConnectors(connectors.toArray(new Connector[0]));
    }
  }

  private Connector createSslConnector(final PortSslProperties props) {
    log.warn(
        "Creating SSL connector — port: {}, keyStore: {}, keyStoreType: {}, keyAlias: {}",
        props.port(),
        props.keyStore(),
        props.keyStoreType(),
        props.keyAlias());
    final var connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
    connector.setScheme("https");
    connector.setSecure(true);
    connector.setPort(props.port());

    final var protocol = (Http11NioProtocol) connector.getProtocolHandler();
    protocol.setSSLEnabled(true);

    final var sslHostConfig = new SSLHostConfig();
    final var certConfig =
        new SSLHostConfigCertificate(sslHostConfig, SSLHostConfigCertificate.Type.RSA);

    certConfig.setCertificateKeystoreFile(stripFilePrefix(props.keyStore()));
    certConfig.setCertificateKeystorePassword(props.keyStorePassword());
    certConfig.setCertificateKeystoreType(props.keyStoreType());
    certConfig.setCertificateKeyAlias(props.keyAlias());

    if (props.keyPassword() != null && !props.keyPassword().isBlank()) {
      certConfig.setCertificateKeyPassword(props.keyPassword());
    }

    sslHostConfig.addCertificate(certConfig);
    protocol.addSslHostConfig(sslHostConfig);

    return connector;
  }

  private @Nullable String stripFilePrefix(final @Nullable String path) {
    if (path == null) {
      return null;
    }
    return path.startsWith(FILE_PREFIX) ? path.substring(FILE_PREFIX.length()) : path;
  }
}
