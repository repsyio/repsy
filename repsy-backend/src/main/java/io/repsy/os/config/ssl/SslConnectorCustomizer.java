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

import io.repsy.libs.multiport.configs.RepsyConnectorSettings;
import io.repsy.os.config.ssl.RepsySslProperties.PortSslProperties;
import java.util.ArrayList;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@NullMarked
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class SslConnectorCustomizer
    implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

  private static final String FILE_PREFIX = "file:";

  private final RepsySslProperties sslProperties;
  private final ServerProperties serverProperties;
  private final int connectionTimeout;
  private final int maxPartCount;

  public SslConnectorCustomizer(
      final RepsySslProperties sslProperties,
      final ServerProperties serverProperties,
      @Value("${multiport.tomcat.connection-timeout:120000}") final int connectionTimeout,
      @Value("${multiport.tomcat.max-part-count:50}") final int maxPartCount) {
    this.sslProperties = sslProperties;
    this.serverProperties = serverProperties;
    this.connectionTimeout = connectionTimeout;
    this.maxPartCount = maxPartCount;
  }

  @Override
  public void customize(final TomcatServletWebServerFactory factory) {
    final var connectors = new ArrayList<Connector>();

    // Like the plain ports they mirror (see the comment on server.compression in application.yml),
    // the repo (protocol) listener compresses and the panel API listener does not.
    if (this.sslProperties.api().enabled()) {
      connectors.add(this.createSslConnector(this.sslProperties.api(), false));
    }

    if (this.sslProperties.repo().enabled()) {
      connectors.add(this.createSslConnector(this.sslProperties.repo(), true));
    }

    if (!connectors.isEmpty()) {
      factory.addAdditionalConnectors(connectors.toArray(new Connector[0]));
    }
  }

  private Connector createSslConnector(final PortSslProperties props, final boolean compress) {
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
    // Spring Boot's own customizers only ever reach the primary connector (RPS-1559): without the
    // encoded slash setting Tomcat refuses every scoped npm package (@scope%2Fname) over TLS, and
    // without max-part-count (RPS-1657) it keeps Tomcat's own default instead of the configured
    // one.
    RepsyConnectorSettings.apply(
        connector,
        this.connectionTimeout,
        this.maxPartCount,
        compress ? this.serverProperties.getCompression() : null);

    final var protocol = (Http11NioProtocol) connector.getProtocolHandler();
    protocol.setSSLEnabled(true);

    final var sslHostConfig = new SSLHostConfig();
    final var certConfig =
        new SSLHostConfigCertificate(sslHostConfig, SSLHostConfigCertificate.Type.RSA);

    certConfig.setCertificateKeystoreFile(this.stripFilePrefix(props.keyStore()));
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
