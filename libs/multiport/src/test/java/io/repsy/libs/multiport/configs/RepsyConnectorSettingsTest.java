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

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.Compression;
import org.springframework.util.unit.DataSize;

class RepsyConnectorSettingsTest {

  private static Connector newConnector() {
    return new Connector("org.apache.coyote.http11.Http11NioProtocol");
  }

  private static Http11NioProtocol protocolOf(final Connector connector) {
    return (Http11NioProtocol) connector.getProtocolHandler();
  }

  @Test
  void applyShouldDecodeEncodedSlashes() {

    final var connector = newConnector();

    RepsyConnectorSettings.apply(connector, 1_000, null);

    assertThat(connector.getEncodedSolidusHandling()).isEqualTo("decode");
  }

  @Test
  void applyShouldSetTheConnectionTimeout() {

    final var connector = newConnector();

    RepsyConnectorSettings.apply(connector, 120_000, null);

    assertThat(protocolOf(connector).getConnectionTimeout()).isEqualTo(120_000);
  }

  @Test
  void applyShouldLeaveCompressionOffWithoutCompressionSettings() {

    final var connector = newConnector();

    RepsyConnectorSettings.apply(connector, 1_000, null);

    assertThat(protocolOf(connector).getCompression()).isEqualTo("off");
  }

  @Test
  void applyShouldLeaveCompressionOffWhenTheSettingsAreDisabled() {

    final var connector = newConnector();
    final var compression = new Compression();
    compression.setEnabled(false);

    RepsyConnectorSettings.apply(connector, 1_000, compression);

    assertThat(protocolOf(connector).getCompression()).isEqualTo("off");
  }

  @Test
  void applyShouldTurnCompressionOnLikeServerCompression() {

    final var connector = newConnector();
    final var compression = new Compression();
    compression.setEnabled(true);
    compression.setMimeTypes(
        new String[] {"application/json", "application/vnd.npm.install-v1+json"});
    compression.setMinResponseSize(DataSize.ofKilobytes(1));

    RepsyConnectorSettings.apply(connector, 1_000, compression);

    final var protocol = protocolOf(connector);
    assertThat(protocol.getCompression()).isEqualTo("on");
    assertThat(protocol.getCompressionMinSize()).isEqualTo(1024);
    assertThat(protocol.getCompressibleMimeType())
        .isEqualTo("application/json,application/vnd.npm.install-v1+json");
  }
}
