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

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.config.ssl.RepsySslProperties.PortSslProperties;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.util.unit.DataSize;

/**
 * RPS-1559: the TLS listeners take the settings of the plain ones, which Spring Boot only applies
 * to the primary connector. {@code SslConnectorTlsIT} proves it on a running server.
 */
class SslConnectorCustomizerTest {

  private static final int API_SSL_PORT = 18443;
  private static final int REPO_SSL_PORT = 19443;
  private static final int TIMEOUT = 45_000;
  private static final int MAX_PART_COUNT = 7;

  private static PortSslProperties port(final boolean enabled, final int port) {
    return new PortSslProperties(enabled, port, "/no/keystore.p12", "pw", "PKCS12", "repsy", null);
  }

  private static ServerProperties serverProperties(final boolean compression) {
    final var properties = new ServerProperties();
    properties.getCompression().setEnabled(compression);
    properties.getCompression().setMimeTypes(new String[] {"application/json"});
    properties.getCompression().setMinResponseSize(DataSize.ofKilobytes(1));
    return properties;
  }

  private static TomcatServletWebServerFactory customize(
      final PortSslProperties api, final PortSslProperties repo, final boolean compression) {
    final var factory = new TomcatServletWebServerFactory();
    new SslConnectorCustomizer(
            new RepsySslProperties(api, repo),
            serverProperties(compression),
            TIMEOUT,
            MAX_PART_COUNT)
        .customize(factory);
    return factory;
  }

  private static Http11NioProtocol protocolOf(final Connector connector) {
    return (Http11NioProtocol) connector.getProtocolHandler();
  }

  private static Connector connectorOn(
      final TomcatServletWebServerFactory factory, final int port) {
    return factory.getAdditionalConnectors().stream()
        .filter(connector -> connector.getPort() == port)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void customizeShouldAddNoConnectorWhenNoPortIsEnabled() {

    final var factory = customize(port(false, API_SSL_PORT), port(false, REPO_SSL_PORT), true);

    assertThat(factory.getAdditionalConnectors()).isEmpty();
  }

  @Test
  void customizeShouldGiveEveryTlsConnectorTheEncodedSlashAndTimeoutSettings() {

    final var factory = customize(port(true, API_SSL_PORT), port(true, REPO_SSL_PORT), true);

    assertThat(factory.getAdditionalConnectors()).hasSize(2);
    for (final var connector : factory.getAdditionalConnectors()) {
      assertThat(connector.getScheme()).isEqualTo("https");
      assertThat(connector.getSecure()).isTrue();
      assertThat(connector.getEncodedSolidusHandling()).isEqualTo("decode");
      assertThat(protocolOf(connector).isSSLEnabled()).isTrue();
      assertThat(protocolOf(connector).getConnectionTimeout()).isEqualTo(TIMEOUT);
      assertThat(connector.getMaxPartCount()).isEqualTo(MAX_PART_COUNT);
    }
  }

  @Test
  void customizeShouldCompressOnTheRepoConnectorOnly() {

    final var factory = customize(port(true, API_SSL_PORT), port(true, REPO_SSL_PORT), true);

    final var repo = protocolOf(connectorOn(factory, REPO_SSL_PORT));
    assertThat(repo.getCompression()).isEqualTo("on");
    assertThat(repo.getCompressionMinSize()).isEqualTo(1024);
    assertThat(repo.getCompressibleMimeType()).isEqualTo("application/json");
    assertThat(protocolOf(connectorOn(factory, API_SSL_PORT)).getCompression()).isEqualTo("off");
  }

  @Test
  void customizeShouldNotCompressWhenServerCompressionIsDisabled() {

    final var factory = customize(port(false, API_SSL_PORT), port(true, REPO_SSL_PORT), false);

    assertThat(protocolOf(connectorOn(factory, REPO_SSL_PORT)).getCompression()).isEqualTo("off");
  }
}
